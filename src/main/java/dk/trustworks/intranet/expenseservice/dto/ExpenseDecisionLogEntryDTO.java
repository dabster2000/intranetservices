package dk.trustworks.intranet.expenseservice.dto;

import java.time.OffsetDateTime;

public record ExpenseDecisionLogEntryDTO(
    String uuid, OffsetDateTime occurredAt,
    String actorRole, String actorUuid, String actorName,
    String action, String reasonText,
    String fromReviewState, String toReviewState,
    String aiRuleId) {}
