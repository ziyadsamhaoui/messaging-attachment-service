package com.ziyadsamhaoui.messagingattachmentservice.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "badrlink.attachment")
public record AttachmentProperties(
        long maxFileSizeBytes,
        int defaultPageSize,
        int maxPageSize,
        Duration uploadUrlTtl,
        Duration downloadUrlTtl,
        Scan scan,
        Minio minio) {

    public record Scan(Duration interval, int batchSize, int maxAttempts) {
    }

    public record Minio(String endpoint, String accessKey, String secretKey, String bucket,
            String quarantineBucket, boolean initializeBuckets) {
    }
}
