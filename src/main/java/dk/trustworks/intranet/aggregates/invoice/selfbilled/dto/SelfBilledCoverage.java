package dk.trustworks.intranet.aggregates.invoice.selfbilled.dto;

/**
 * AC3 coverage invariant, normalized positive, partitioned by voucher status:
 * captured = assigned + sameCompany + unassigned + ignored (ignored is ~always 0:
 * net-zero vouchers). Surfaced as the workbench progress bar — nothing is silently dropped.
 */
public record SelfBilledCoverage(double captured, double assigned, double sameCompany,
                                 double unassigned, double ignored,
                                 int documentCount, int placedCount) {}
