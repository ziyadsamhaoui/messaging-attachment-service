package com.ziyadsamhaoui.messagingattachmentservice.observability;

import com.ziyadsamhaoui.messagingattachmentservice.config.AttachmentProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports object storage as a real dependency.
 *
 * <p>A JVM-only health check would say "UP" while every upload confirmation fails, because the
 * storage bucket is unreachable or was never created. The check performs the cheapest read the
 * MinIO API offers (a bucket existence HEAD) so readiness reflects what the service needs, and
 * fails as soon as the bucket disappears.
 */
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient client;
    private final String bucket;

    public MinioHealthIndicator(MinioClient client, AttachmentProperties properties) {
        this.client = client;
        this.bucket = properties.minio().bucket();
    }

    @Override
    public Health health() {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                return Health.down()
                        .withDetail("bucket", bucket)
                        .withDetail("reason", "bucket is missing")
                        .build();
            }
            return Health.up().withDetail("bucket", bucket).build();
        } catch (Exception ex) {
            return Health.down(ex).withDetail("bucket", bucket).build();
        }
    }
}
