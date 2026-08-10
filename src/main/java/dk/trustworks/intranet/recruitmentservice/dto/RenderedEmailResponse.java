package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * A rendered subject/body pair for the compose dialog (P15).
 * {@code unresolvedFields} lists merge-field tokens that had no value —
 * the frontend warns before sending.
 */
public record RenderedEmailResponse(
        String subject,
        String body,
        /** How to read {@code body} — the compose surfaces always get HTML. */
        String bodyFormat,
        List<String> unresolvedFields
) {
}
