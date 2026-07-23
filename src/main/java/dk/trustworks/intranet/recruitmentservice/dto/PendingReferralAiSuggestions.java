package dk.trustworks.intranet.recruitmentservice.dto;

import java.time.LocalDateTime;

/**
 * AI triage suggestions riding on one pending-referral row (P9, contract
 * §6.3) — derived from the latest referral-variant
 * {@code AI_SUGGESTIONS_GENERATED} event for the referral and re-validated
 * at read time: a since-deactivated practice or no-longer-leading teamlead
 * nulls that field (the names next to the uuids are resolved batched).
 * The whole object is {@code null} on rows without suggestions and when
 * {@code recruitment.ai.referral-triage.enabled} is off.
 */
public record PendingReferralAiSuggestions(
        String practiceUuid,
        String practiceName,
        String experienceLevel,
        String relevantTeamleadUuid,
        String teamleadName,
        Rationales rationales,
        LocalDateTime generatedAt
) {

    /** The per-field one-line Danish rationales. */
    public record Rationales(String practice, String experienceLevel, String teamlead) {
    }
}
