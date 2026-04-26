package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.util.List;

/**
 * Typed projection of the {@code AiArtifact.output} JSON payload produced by the
 * ROLE_BRIEF AI generator (spec §6.2 / prompt-catalog v1 {@code role-brief-v1}).
 *
 * <p>This record is the contract the frontend deserialises against; the
 * {@link dk.trustworks.intranet.recruitmentservice.application.handlers.RoleBriefApplyHandler}
 * uses the same shape when patching {@code OpenRole.hiringReason} on accept.
 *
 * @param responsibilities  Bullet-point responsibilities for the role.
 * @param mustHaves         Required skills / qualifications.
 * @param niceToHaves       Optional or preferred skills.
 * @param adCopyDraft       Markdown-formatted advert draft suitable for posting.
 * @param risks             Hiring risks the operator should be aware of.
 */
public record RoleBriefOutput(
        List<String> responsibilities,
        List<String> mustHaves,
        List<String> niceToHaves,
        String adCopyDraft,
        List<String> risks
) {}
