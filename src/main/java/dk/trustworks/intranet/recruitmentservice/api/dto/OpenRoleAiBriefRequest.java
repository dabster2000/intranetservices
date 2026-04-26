package dk.trustworks.intranet.recruitmentservice.api.dto;

import java.util.Map;

/**
 * Request body for {@code POST /api/recruitment/roles/{uuid}/ai/role-brief} (spec §6.2).
 *
 * <p>The endpoint composes its prompt from the role's persisted state, so the body is
 * usually empty. {@code extraInputs} is an optional escape hatch for callers that want
 * to seed the prompt with additional context (e.g. operator hints) without changing the
 * role aggregate. Whatever is provided here is folded into the digest input map, so two
 * different {@code extraInputs} payloads produce two different artifacts even for the
 * same role.</p>
 */
public record OpenRoleAiBriefRequest(Map<String, Object> extraInputs) {}
