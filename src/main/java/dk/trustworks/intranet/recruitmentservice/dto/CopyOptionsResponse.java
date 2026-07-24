package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Everyone the compose dialog may copy for one candidate, with the picked
 * template's defaults already applied ({@code selected}) — one request,
 * no client-side fan-out and no client-side policy logic.
 * <p>
 * {@code copyMode} is the template's mode (BCC unless the template opts
 * into a visible CC); {@code replyTo} is the address the candidate's reply
 * will actually reach, so the dialog can state it plainly instead of
 * leaving the recruiter to guess.
 */
public record CopyOptionsResponse(
        List<CopyRecipientResponse> recipients,
        String copyMode,
        String replyTo
) {
}
