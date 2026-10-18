package com.ziyadsamhaoui.messagingattachmentservice.service;

import com.ziyadsamhaoui.messagingattachmentservice.exception.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AttachmentCursorCodec {

    private static final String SEPARATOR = "|";

    private final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    private final Base64.Decoder decoder = Base64.getUrlDecoder();

    public String encode(Instant uploadedAt, UUID id) {
        return encoder.encodeToString(
                (uploadedAt.toEpochMilli() + SEPARATOR + id).getBytes(StandardCharsets.UTF_8));
    }

    public Optional<Cursor> decode(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return Optional.empty();
        }

        String raw;
        try {
            raw = new String(decoder.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw malformedCursor();
        }

        int separatorIndex = raw.indexOf(SEPARATOR);
        if (separatorIndex < 1 || separatorIndex == raw.length() - 1) {
            throw malformedCursor();
        }

        try {
            Instant uploadedAt = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, separatorIndex)));
            UUID id = UUID.fromString(raw.substring(separatorIndex + 1));
            return Optional.of(new Cursor(uploadedAt, id));
        } catch (IllegalArgumentException exception) {
            throw malformedCursor();
        }
    }

    private InvalidRequestException malformedCursor() {
        return new InvalidRequestException("INVALID_CURSOR", "The supplied cursor is malformed");
    }

    public record Cursor(Instant uploadedAt, UUID id) {
    }
}
