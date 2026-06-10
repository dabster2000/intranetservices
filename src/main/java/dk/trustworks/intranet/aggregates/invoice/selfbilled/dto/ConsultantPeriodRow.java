package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * Consultants-tab review lens (spec §5.3): one row per cross-company
 * (consultant, work-period). All amounts normalized positive. workValue is the
 * registered-work CROSS-CHECK ONLY (never a settlement basis — AC1).
 * consultantUuid/Name null when masked (no users:read).
 */
public record ConsultantPeriodRow(String consultantUuid, String consultantName,
                                  int workYear, int workMonth,
                                  String issuerCompanyUuid, String issuerCompanyName,
                                  double assigned, double settled, double delta, double workValue,
                                  boolean canSettle, int unlinkedCandidates) {}
