# messaging-attachment-service

Sprint 8. Owns attachment metadata for BadrLink and the object-storage lifecycle
behind it. Files live in MinIO; PostgreSQL stores metadata only.

- Port `8086`, database `attachment_db`, Flyway migrations `V1`–`V3`.
- Two private buckets: `${MINIO_ATTACHMENT_BUCKET}` (live) and
  `${MINIO_QUARANTINE_BUCKET}` (rejected). Both are created by
  `MinioConfig` at startup when `MINIO_INITIALIZE_BUCKETS=true`, and the compose
  stack also creates them with `minio/mc`.
- Kafka topic `badrlink.attachment.file.v1` (`FILE_UPLOADED`, `FILE_READY`,
  `FILE_REJECTED`) through the shared transactional outbox. Consumes
  `MESSAGE_DELETED` from `badrlink.chat.message.v1` as group
  `attachment-service` and removes the message's objects from both buckets.

## Lifecycle

```
PENDING_UPLOAD --confirm (real HEAD + size match)--> PENDING_SCAN
PENDING_SCAN --stub malware SAFE + moderation SFW--> READY
PENDING_SCAN --either check fails / scan attempts exhausted--> REJECTED
REJECTED --copy to quarantine + delete live object--> quarantinedAt set
READY --Chat PATCH /internal/messages/{messageId}/attachments--> chatSyncedAt set
```

`READY` is the only downloadable state. `REJECTED` rows are never issued a URL.
Retries are scheduled: un-synced ready rows, un-quarantined rejected rows, and
scanner failures (up to `ATTACHMENT_SCAN_MAX_ATTEMPTS`, then rejected).

Malware scanning and content moderation are **stubs**: `StubMalwareScanner`
returns `SAFE`, `StubContentModerator` returns `SFW`. A ClamAV `clamd` client and
a real classifier replace those two beans without changing the lifecycle.

## Endpoints

| Method | Path | Notes |
| ------ | ---- | ----- |
| `POST` | `/attachments/intents` | Body `{roomId, messageId, fileName, fileType, fileSizeBytes}`. Authorizes via Chat `GET /rooms/{roomId}` with the caller's JWT, validates type/size, stores `PENDING_UPLOAD`, returns a presigned PUT URL. |
| `POST` | `/attachments/{id}/confirm` | Re-authorizes the room, stats the object, checks the declared size, moves to `PENDING_SCAN`, emits `FILE_UPLOADED`. |
| `GET` | `/attachments/{id}` | Authorized metadata; `url` is a short-lived presigned GET only for `READY`. |
| `GET` | `/attachments/{id}/download` | Authorized, `READY` only, short-lived download URL. |
| `POST` | `/attachments/lookup` | Body `{ids}` (max 100). Groups by room and authorizes each room once; a room the caller cannot access fails the request with `403`. |
| `GET` | `/attachments/rooms/{roomId}` | Gallery: `READY` only, newest first, `limit` 1–100 (default 30), keyset cursor. `totalCount` is included on the first page only; `nextCursor` is `base64url(uploadedAtMillis|id)`. |

Every room-scoped endpoint asks Chat for membership on every request; there is no
local membership cache. A Chat `403` becomes this service's `403`
(`ROOM_ACCESS_DENIED`); other Chat failures return `502`
(`CHAT_SERVICE_UNAVAILABLE`). Errors use the shared BadrLink body:
`{timestamp, status, code, message, path}`.

Presigned URLs: uploads use `ATTACHMENT_UPLOAD_URL_TTL` (default `5m`), reads use
`ATTACHMENT_DOWNLOAD_URL_TTL` (default `10m`). Object keys are
`attachments/{attachmentId}` — the client filename never reaches the object path.

## Configuration

See `.env.example` for every variable. Key values:

| Property | Env | Default |
| -------- | --- | ------- |
| `server.port` | `ATTACHMENT_SERVICE_PORT` | `8086` |
| `badrlink.attachment.max-file-size-bytes` | `ATTACHMENT_MAX_FILE_SIZE_BYTES` | `26214400` |
| `badrlink.attachment.minio.*` | `MINIO_*` | `http://localhost:9000`, buckets `badrlink-attachments` / `badrlink-quarantine` |
| `badrlink.chat-service.base-url` | `CHAT_SERVICE_BASE_URL` | `http://localhost:8083` |
| `badrlink.chat-service.internal-token-header` | fixed | `X-BadrLink-Internal-Token` |
| `badrlink.chat-service.internal-token` | `CHAT_INTERNAL_SERVICE_TOKEN` | required for Chat sync |

Chat uses the distinct internal header `X-BadrLink-Internal-Token`; it is sent
with the configured token and is never exposed to the browser.

## Tests

`./mvnw test` runs unit tests with Mockito; no Docker, Postgres or MinIO is
needed. They cover the non-participant `403` with no saved row, confirmation
against a missing object and a size mismatch, duplicate confirmation, the full
`PENDING_UPLOAD → PENDING_SCAN → READY` transition with the Chat PATCH, rejection
with the quarantine move, scanner retry/exhaustion, gallery authorization and
first-page-only `totalCount`, cursor stability, and idempotent deletion.

Not covered by automated tests in this environment: a live MinIO round trip and
a live Kafka/Redis end-to-end flow.
