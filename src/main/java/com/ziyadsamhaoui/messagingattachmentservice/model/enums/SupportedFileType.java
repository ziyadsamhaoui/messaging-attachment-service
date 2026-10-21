package com.ziyadsamhaoui.messagingattachmentservice.model.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public enum SupportedFileType {
    JPEG("image/jpeg", "jpg", Map.of("image/jpg", true, "image/pjpeg", true)),
    PNG("image/png", "png", Map.of()),
    GIF("image/gif", "gif", Map.of()),
    WEBP("image/webp", "webp", Map.of()),
    PDF("application/pdf", "pdf", Map.of()),
    TXT("text/plain", "txt", Map.of()),
    CSV("text/csv", "csv", Map.of()),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx", Map.of()),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx", Map.of()),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx", Map.of()),
    ZIP("application/zip", "zip", Map.of("application/x-zip-compressed", true)),
    MP4("video/mp4", "mp4", Map.of("audio/mp4", true, "video/x-m4v", true)),
    WEBM("video/webm", "webm", Map.of("audio/webm", true)),
    MP3("audio/mpeg", "mp3", Map.of("audio/mp3", true)),
    OGG("audio/ogg", "ogg", Map.of("application/ogg", true, "audio/opus", true)),
    WAV("audio/wav", "wav", Map.of("audio/x-wav", true, "audio/wave", true));

    private final String mimeType;
    private final String extension;
    private final Map<String, Boolean> aliases;

    SupportedFileType(String mimeType, String extension, Map<String, Boolean> aliases) {
        this.mimeType = mimeType;
        this.extension = extension;
        this.aliases = aliases;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static String normalize(String declared) {
        if (declared == null) {
            return "";
        }
        String normalized = declared.trim().toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) {
            normalized = normalized.substring(0, parameters).trim();
        }
        return normalized;
    }

    public static Optional<SupportedFileType> fromMimeType(String declared) {
        String normalized = normalize(declared);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Arrays.stream(values())
                .filter(type -> type.mimeType.equals(normalized) || type.aliases.containsKey(normalized))
                .findFirst();
    }
}
