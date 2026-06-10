package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

import java.math.BigDecimal;

/**
 * One assignment in an assign request. shareAmount is NORMALIZED POSITIVE
 * (UI convention); null on a single assignment means "the whole voucher net".
 */
public record AssignmentInput(String consultantUuid, Integer workYear, Integer workMonth,
                              BigDecimal shareAmount) {}
