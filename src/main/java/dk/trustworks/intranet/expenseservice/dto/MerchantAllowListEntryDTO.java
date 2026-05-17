package dk.trustworks.intranet.expenseservice.dto;

import java.time.LocalDateTime;

public record MerchantAllowListEntryDTO(
    String uuid,
    String ruleId,
    String merchantNamePattern,
    String matchKind,
    String notes,
    String addedByUuid,
    LocalDateTime createdAt
) {}
