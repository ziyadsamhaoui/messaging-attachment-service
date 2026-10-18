package com.ziyadsamhaoui.messagingattachmentservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.ziyadsamhaoui.messagingattachmentservice.model.enums.AttachmentKind;
import com.ziyadsamhaoui.messagingattachmentservice.model.enums.SupportedFileType;
import org.junit.jupiter.api.Test;

class SupportedFileTypesTest {

    @Test
    void browserRecordingTypesMapOntoTheSupportedContainers() {
        assertThat(SupportedFileType.fromMimeType("audio/webm;codecs=opus"))
                .contains(SupportedFileType.WEBM);
        assertThat(SupportedFileType.fromMimeType("audio/webm")).contains(SupportedFileType.WEBM);
        assertThat(SupportedFileType.fromMimeType("video/webm")).contains(SupportedFileType.WEBM);
        assertThat(SupportedFileType.fromMimeType("audio/ogg")).contains(SupportedFileType.OGG);
        assertThat(SupportedFileType.fromMimeType("application/ogg")).contains(SupportedFileType.OGG);
        assertThat(SupportedFileType.fromMimeType("AUDIO/MOZILLA-OPUS")).isEmpty();
    }

    @Test
    void declaredParametersAndCaseAreNormalized() {
        assertThat(SupportedFileType.fromMimeType("  Image/PNG ; charset=binary "))
                .contains(SupportedFileType.PNG);
        assertThat(SupportedFileType.fromMimeType("image/jpg")).contains(SupportedFileType.JPEG);
        assertThat(SupportedFileType.fromMimeType("application/vnd.openxmlformats-officedocument."
                + "wordprocessingml.document")).contains(SupportedFileType.DOCX);
    }

    @Test
    void unsupportedOrMissingTypesAreRejected() {
        assertThat(SupportedFileType.fromMimeType("application/x-msdownload")).isEmpty();
        assertThat(SupportedFileType.fromMimeType("")).isEmpty();
        assertThat(SupportedFileType.fromMimeType(null)).isEmpty();
    }

    @Test
    void audioWebmIsAnAudioAttachmentNotAVideoOne() {
        assertThat(AttachmentKind.fromMimeType("audio/webm;codecs=opus")).isEqualTo(AttachmentKind.AUDIO);
        assertThat(AttachmentKind.fromMimeType("video/webm")).isEqualTo(AttachmentKind.VIDEO);
        assertThat(AttachmentKind.fromMimeType("image/jpeg")).isEqualTo(AttachmentKind.IMAGE);
        assertThat(AttachmentKind.fromMimeType("application/pdf")).isEqualTo(AttachmentKind.FILE);
    }
}
