package com.ziyadsamhaoui.messagingattachmentservice.scanner;

import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;
import org.springframework.stereotype.Component;

@Component
public class StubContentModerator implements ContentModerator {

    @Override
    public ModerationStatus moderate(Attachment attachment) {
        return ModerationStatus.SFW;
    }
}
