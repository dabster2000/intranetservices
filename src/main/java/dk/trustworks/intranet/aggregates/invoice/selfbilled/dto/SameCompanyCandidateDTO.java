package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * One same-company bulk-accept candidate (Feature 1a): an UNASSIGNED voucher whose
 * suggested consultant's company as-of the suggested work period equals the source's
 * debtor (agreement) company — so accepting it never crosses companies (AC2). Unlike
 * {@link SelfBilledAssignmentService#acceptSuggestedSameCompany}'s ≥90 auto-path, the
 * preview returns candidates at ANY confidence so a human can review the long tail
 * before an explicit accept. amount is normalized positive; consultant identity is
 * masked at the resource without {@code users:read} (mirrors maskDocuments).
 */
public record SameCompanyCandidateDTO(String lineUuid, int voucherNumber, String bookingDate,
                                      double amount, String suggestedCode,
                                      String consultantUuid, String consultantName,
                                      int workYear, int workMonth, int confidence) {}
