package com.ziyadsamhaoui.messagingattachmentservice.storage;

import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import io.minio.CopyObjectArgs;
import io.minio.CopySource;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class MinioObjectStorage implements ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(MinioObjectStorage.class);

    private static final Set<String> MISSING_CODES = Set.of(
            "NoSuchKey", "NoSuchObject", "NoSuchBucket", "XMinioInvalidObjectName");

    private final MinioClient client;
    private final String bucket;
    private final String quarantineBucket;

    /**
     * The MinIO SDK signs URLs locally and performs its HEAD/GET calls through OkHttp, so the
     * agent can only see the network calls. These spans describe the logical storage operation,
     * which is what a slow or failing upload actually needs to be diagnosed.
     */
    private final Tracer tracer = GlobalOpenTelemetry.get().getTracer("messaging-attachment-service");

    public MinioObjectStorage(MinioClient client, AttachmentProperties properties) {
        this.client = client;
        this.bucket = properties.minio().bucket();
        this.quarantineBucket = properties.minio().quarantineBucket();
    }

    @Override
    public String presignedPutUrl(String objectKey, Duration ttl) {
        return presigned(Method.PUT, bucket, objectKey, ttl);
    }

    @Override
    public String presignedGetUrl(String objectKey, Duration ttl) {
        return presigned(Method.GET, bucket, objectKey, ttl);
    }

    @Override
    public String presignedQuarantineGetUrl(String objectKey, Duration ttl) {
        return presigned(Method.GET, quarantineBucket, objectKey, ttl);
    }

    private String presigned(Method method, String targetBucket, String objectKey, Duration ttl) {
        Span span = tracer.spanBuilder("attachment.presign")
                .setAttribute("attachment.storage.operation", "presign")
                .setAttribute("attachment.storage.bucket", targetBucket)
                .setAttribute("attachment.storage.method", method.name())
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(targetBucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception exception) {
            span.setStatus(StatusCode.ERROR, "presign failed");
            span.recordException(exception);
            throw new ObjectStorageException("Could not sign a URL for " + objectKey, exception);
        } finally {
            span.end();
        }
    }

    @Override
    public Optional<StoredObject> stat(String objectKey) {
        Span span = tracer.spanBuilder("attachment.stat")
                .setAttribute("attachment.storage.operation", "stat")
                .setAttribute("attachment.storage.bucket", bucket)
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            span.setAttribute("attachment.storage.object_present", true);
            return Optional.of(new StoredObject(response.size(), response.contentType()));
        } catch (ErrorResponseException exception) {
            if (MISSING_CODES.contains(exception.errorResponse().code())) {
                span.setAttribute("attachment.storage.object_present", false);
                return Optional.empty();
            }
            span.setStatus(StatusCode.ERROR, "stat failed");
            span.recordException(exception);
            throw new ObjectStorageException("Could not stat " + objectKey, exception);
        } catch (Exception exception) {
            span.setStatus(StatusCode.ERROR, "stat failed");
            span.recordException(exception);
            throw new ObjectStorageException("Could not stat " + objectKey, exception);
        } finally {
            span.end();
        }
    }

    @Override
    public void copyToQuarantine(String objectKey) {
        try {
            client.copyObject(CopyObjectArgs.builder()
                    .bucket(quarantineBucket)
                    .object(objectKey)
                    .source(CopySource.builder().bucket(bucket).object(objectKey).build())
                    .build());
        } catch (ErrorResponseException exception) {
            if (MISSING_CODES.contains(exception.errorResponse().code())) {
                log.warn("Object {} was already absent when quarantining", objectKey);
                return;
            }
            throw new ObjectStorageException("Could not quarantine " + objectKey, exception);
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not quarantine " + objectKey, exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        remove(bucket, objectKey);
    }

    @Override
    public void deleteFromQuarantine(String objectKey) {
        remove(quarantineBucket, objectKey);
    }

    private void remove(String targetBucket, String objectKey) {
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(targetBucket).object(objectKey).build());
        } catch (ErrorResponseException exception) {
            if (!MISSING_CODES.contains(exception.errorResponse().code())) {
                throw new ObjectStorageException("Could not delete " + objectKey, exception);
            }
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not delete " + objectKey, exception);
        }
    }
}
