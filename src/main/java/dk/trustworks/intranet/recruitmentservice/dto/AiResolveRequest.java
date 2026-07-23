package dk.trustworks.intranet.recruitmentservice.dto;

/**
 * Request body for
 * {@code POST /recruitment/candidates/{uuid}/ai/suggestions/resolve} (P9,
 * contract §6.2): the recruiter's accept/dismiss decision on one AI
 * suggestion. Validated explicitly in the resource (bean validation is
 * inert in this module): both fields are required.
 */
public record AiResolveRequest(String suggestionId, Boolean accepted) {
}
