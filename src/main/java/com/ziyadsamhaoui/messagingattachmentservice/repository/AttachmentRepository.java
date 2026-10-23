package com.ziyadsamhaoui.messagingattachmentservice.repository;

import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.FileStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<Attachment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Attachment a where a.id = :id")
    Optional<Attachment> lockById(@Param("id") UUID id);

    @Query(value = """
            select id from attachments
            where status = 'PENDING_SCAN'
            order by created_at asc
            limit :batch
            """, nativeQuery = true)
    List<UUID> findPendingScanIds(@Param("batch") int batch);

    List<Attachment> findByMessageIdOrderByCreatedAtAsc(String messageId);

    long countByRoomIdAndStatus(String roomId, FileStatus status);

    @Query(value = """
            select * from attachments
            where room_id = :roomId
              and status = 'READY'
              and (cast(:cursorAt as timestamptz) is null
                   or (uploaded_at, id) < (cast(:cursorAt as timestamptz), cast(:cursorId as uuid)))
            order by uploaded_at desc, id desc
            limit :batch
            """, nativeQuery = true)
    List<Attachment> findReadyPage(@Param("roomId") String roomId,
            @Param("cursorAt") Instant cursorAt,
            @Param("cursorId") UUID cursorId,
            @Param("batch") int batch);

    @Query(value = """
            select * from attachments
            where status = 'READY' and chat_synced_at is null
            order by scanned_at asc
            limit :batch
            """, nativeQuery = true)
    List<Attachment> findUnsyncedReady(@Param("batch") int batch);

    @Query(value = """
            select * from attachments
            where status = 'REJECTED' and quarantined_at is null
            order by scanned_at asc
            limit :batch
            """, nativeQuery = true)
    List<Attachment> findUnquarantinedRejected(@Param("batch") int batch);
}
