package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/** Template list for /recruitment/settings and the compose picker (P15). */
public record EmailTemplatesResponse(
        List<EmailTemplateResponse> templates,
        int totalCount
) {
}
