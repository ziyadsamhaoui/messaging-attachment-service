package com.ziyadsamhaoui.messagingattachmentservice.repository;

import com.ziyadsamhaoui.messagingattachmentservice.model.ProcessedEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
}
