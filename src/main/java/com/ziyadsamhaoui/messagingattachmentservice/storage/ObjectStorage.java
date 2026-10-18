package com.ziyadsamhaoui.messagingattachmentservice.storage;

import java.time.Duration;
import java.util.Optional;

public interface ObjectStorage {

    String presignedPutUrl(String objectKey, Duration ttl);

    String presignedGetUrl(String objectKey, Duration ttl);

    String presignedQuarantineGetUrl(String objectKey, Duration ttl);

    Optional<StoredObject> stat(String objectKey);

    void copyToQuarantine(String objectKey);

    void delete(String objectKey);

    void deleteFromQuarantine(String objectKey);
}
