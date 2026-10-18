package com.ziyadsamhaoui.messagingattachmentservice.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
public class MinioConfig {

    private static final Logger log = LoggerFactory.getLogger(MinioConfig.class);

    @Bean
    MinioClient minioClient(AttachmentProperties properties) {
        AttachmentProperties.Minio minio = properties.minio();
        if (!StringUtils.hasText(minio.endpoint())
                || !StringUtils.hasText(minio.accessKey())
                || !StringUtils.hasText(minio.secretKey())
                || !StringUtils.hasText(minio.bucket())
                || !StringUtils.hasText(minio.quarantineBucket())) {
            throw new IllegalStateException(
                    "badrlink.attachment.minio endpoint, credentials and both bucket names are required");
        }
        return MinioClient.builder()
                .endpoint(minio.endpoint())
                .credentials(minio.accessKey(), minio.secretKey())
                .build();
    }

    @Bean
    ApplicationRunner attachmentBucketInitializer(MinioClient client, AttachmentProperties properties) {
        return arguments -> {
            if (!properties.minio().initializeBuckets()) {
                return;
            }
            ensureBucket(client, properties.minio().bucket());
            ensureBucket(client, properties.minio().quarantineBucket());
        };
    }

    private void ensureBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created private bucket {}", bucket);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Could not initialize bucket " + bucket, exception);
        }
    }
}
