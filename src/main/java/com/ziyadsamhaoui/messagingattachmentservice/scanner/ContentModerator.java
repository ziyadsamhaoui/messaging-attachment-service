package com.ziyadsamhaoui.messagingattachmentservice.scanner;

import com.ziyadsamhaoui.messagingattachmentservice.model.Attachment;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.ModerationStatus;

public interface ContentModerator {

    ModerationStatus moderate(Attachment attachment);
}
