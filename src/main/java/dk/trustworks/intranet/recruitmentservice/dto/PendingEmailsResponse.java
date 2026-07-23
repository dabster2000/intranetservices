package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/** The review-before-send queue, oldest first (P15). */
public record PendingEmailsResponse(
        List<PendingEmailResponse> pending,
        int totalCount
) {
}
