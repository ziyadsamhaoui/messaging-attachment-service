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
        try {
            return client.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(method)
                    .bucket(targetBucket)
                    .object(objectKey)
                    .expiry((int) ttl.toSeconds(), TimeUnit.SECONDS)
                    .build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not sign a URL for " + objectKey, exception);
        }
    }

    @Override
    public Optional<StoredObject> stat(String objectKey) {
        try {
            StatObjectResponse response = client.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .build());
            return Optional.of(new StoredObject(response.size(), response.contentType()));
        } catch (ErrorResponseException exception) {
            if (MISSING_CODES.contains(exception.errorResponse().code())) {
                return Optional.empty();
            }
            throw new ObjectStorageException("Could not stat " + objectKey, exception);
        } catch (Exception exception) {
            throw new ObjectStorageException("Could not stat " + objectKey, exception);
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
