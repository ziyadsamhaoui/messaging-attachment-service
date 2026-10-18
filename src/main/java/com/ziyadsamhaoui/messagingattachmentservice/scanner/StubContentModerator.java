package com.ziyadsamhaoui.messagingattachmentservice.scanner;

import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;
import org.springframework.stereotype.Component;

/**
 * Sprint 8 stub: every file is reported SFW. A real classifier replaces this bean
 * without changing the scan lifecycle.
 */
@Component
public class StubContentModerator implements ContentModerator {

    @Override
    public ModerationStatus moderate(Attachment attachment) {
        return ModerationStatus.SFW;
    }
}
