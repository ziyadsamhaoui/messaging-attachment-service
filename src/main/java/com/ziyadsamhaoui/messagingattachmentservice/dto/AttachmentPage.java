package com.ziyadsamhaoui.messagingattachmentservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentPage(List<AttachmentResponse> items, String nextCursor, boolean hasMore,
        Long totalCount) {

    public static AttachmentPage of(List<AttachmentResponse> items, String nextCursor, boolean hasMore,
            Long totalCount) {
        return new AttachmentPage(items, nextCursor, hasMore, totalCount);
    }
}
