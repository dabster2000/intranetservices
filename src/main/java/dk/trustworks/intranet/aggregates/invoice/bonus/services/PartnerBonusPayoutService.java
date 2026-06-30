package dk.trustworks.intranet.aggregates.invoice.bonus.services;

import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerProductionBonusCalculator;
import dk.trustworks.intranet.aggregates.invoice.bonus.calculator.PartnerSalesBonusCalculator;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PartnerBonusBackfillReport;
import dk.trustworks.intranet.aggregates.invoice.bonus.dto.PayoutResultDTO;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.PartnerBonusPayout;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.aggregates.users.services.SalaryLumpSumService;
import dk.trustworks.intranet.aggregates.users.services.SalarySupplementService;
import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
import dk.trustworks.intranet.domain.user.entity.SalarySupplement;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.userservice.model.enums.LumpSumSalaryType;
import dk.trustworks.intranet.userservice.model.enums.SalarySupplementType;
import io.quarkus.hibernate.orm.panache.Panache;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

/**
 * Partner-bonus payout — recomputes amounts server-side and guarantees that an invoice's APPROVED
 * bonus rows can only ever fund ONE payout.
 *
 * <p>The sales bonus is a per-GROUP figure: the APPROVED {@link dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus}
 * rows in the fiscal-year window form a basis that the nonlinear {@link PartnerSalesBonusCalculator}
 * transforms and splits per partner. To make "fund once" enforceable we freeze that basis in a
 * {@link PartnerBonusPayout} event per (group, FY) and stamp the consumed bonus rows
 * ({@code InvoiceBonus.payoutUuid}); the basis recompute only ever sums un-stamped rows. The
 * production bonus is revenue-derived (not invoice-linked), so it stays per-consultant×FY and is
 * deduped via the {@code salary_lump_sum.source_reference} unique index.</p>
 */
@ApplicationScoped
public class PartnerBonusPayoutService {

    @Inject SalaryLumpSumService salaryLumpSumService;
    @Inject SalarySupplementService salarySupplementService;
    @Inject InvoiceBonusService invoiceBonusService;
    @Inject RevenueService revenueService;
    @Inject PartnerSalesBonusCalculator salesCalculator;
    @Inject PartnerProductionBonusCalculator productionCalculator;

    /**
     * Calculate total prepaid bonuses for a user within a fiscal year.
     * FY runs July 1 (fiscalYear) to June 30 (fiscalYear+1).
     * Supplement value is a monthly rate; total = monthlyRate * overlapMonths.
     */
    public double calculatePrepaidBonuses(String userUuid, int fiscalYear) {
        try {
            LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
            LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);

            List<SalarySupplement> supplements = salarySupplementService.findByUseruuid(userUuid);

            return supplements.stream()
                    .filter(s -> s.getType() == SalarySupplementType.PREPAID)
                    .filter(s -> s.getFromMonth() != null)
                    .filter(s -> {
                        LocalDate suppStart = s.getFromMonth();
                        LocalDate suppEnd = s.getToMonth() != null ? s.getToMonth() : LocalDate.MAX;
                        return !suppEnd.isBefore(fyStart) && !suppStart.isAfter(fyEnd);
                    })
                    .mapToDouble(s -> {
                        LocalDate suppStart = s.getFromMonth();
                        LocalDate suppEnd = s.getToMonth() != null ? s.getToMonth() : LocalDate.MAX;

                        LocalDate overlapStart = suppStart.isBefore(fyStart) ? fyStart : suppStart;
                        LocalDate overlapEnd = suppEnd.isAfter(fyEnd) ? fyEnd : suppEnd;

                        long overlapMonths = ChronoUnit.MONTHS.between(
                                overlapStart.withDayOfMonth(1),
                                overlapEnd.withDayOfMonth(1)
                        ) + 1;

                        double monthlyRate = s.getValue() != null ? s.getValue() : 0.0;
                        return monthlyRate * overlapMonths;
                    })
                    .sum();
        } catch (Exception e) {
            Log.error("Failed to calculate prepaid bonuses for user: " + userUuid, e);
            return 0.0;
        }
    }

    /**
     * Check if a partner bonus payout already exists for a user.
     * Checks FY+1 because payouts for FY X are created in FY X+1.
     */
    public boolean hasExistingPayout(String userUuid, int fiscalYear) {
        try {
            LocalDate paymentFyStart = LocalDate.of(fiscalYear + 1, 7, 1);
            LocalDate paymentFyEnd = LocalDate.of(fiscalYear + 2, 6, 30);

            List<SalaryLumpSum> lumpSums = salaryLumpSumService.findByUseruuid(userUuid);

            return lumpSums.stream()
                    .filter(ls -> ls.getSalaryType() == LumpSumSalaryType.COMMERCIAL_PARTNER_BONUS ||
                                  ls.getSalaryType() == LumpSumSalaryType.PROD_BONUS)
                    .filter(ls -> ls.getMonth() != null)
                    .anyMatch(ls -> !ls.getMonth().isBefore(paymentFyStart) && !ls.getMonth().isAfter(paymentFyEnd));
        } catch (Exception e) {
            Log.error("Failed to check existing payout for user: " + userUuid, e);
            return false;
        }
    }

    /**
     * Pay one partner for a fiscal year. Sales is drawn from the frozen (group, FY) payout event
     * (created on first call, consuming the group's un-consumed APPROVED invoices); production is
     * recomputed from the partner's own registered revenue. Amounts are server-authoritative.
     */
    @Transactional
    public PayoutResultDTO payPartner(String userUuid, LocalDate month, int fiscalYear, String requestedBy) {
        if (userUuid == null || userUuid.isBlank()) throw new BadRequestException("userUuid is required");
        LocalDate fyStart = LocalDate.of(fiscalYear, 7, 1);
        LocalDate fyEnd = LocalDate.of(fiscalYear + 1, 6, 30);
        LocalDate payMonth = (month != null ? month : LocalDate.now()).withDayOfMonth(1);

        // 1. Resolve the partner's group for this fiscal year.
        BonusEligibility elig = BonusEligibility
                .find("useruuid = ?1 and financialYear = ?2", userUuid, fiscalYear).firstResult();
        if (elig == null || elig.getGroup() == null) {
            throw new BadRequestException("User has no bonus eligibility group for FY " + fiscalYear);
        }
        BonusEligibilityGroup group = elig.getGroup();

        // 2. Find-or-create the frozen sales payout event for (group, FY).
        PartnerBonusPayout event = getOrCreatePayoutEvent(group, fiscalYear, fyStart, fyEnd, payMonth, requestedBy);
        double salesAmount = round2(event.getSalesBonusPerPartner());

        // 3. Production bonus — recomputed server-side from the partner's own registered revenue.
        double ownRevenue = sumOwnRevenue(userUuid, fyStart, fyEnd);
        double productionAmount = round2(productionCalculator
                .calculateProductionBonus(BigDecimal.valueOf(ownRevenue), 12).doubleValue());

        // 4. Create the lump sums (idempotent via salary_lump_sum.source_reference unique index).
        String label = fiscalYearLabel(fiscalYear);
        createLumpSumIfAbsent(userUuid, LumpSumSalaryType.COMMERCIAL_PARTNER_BONUS, salesAmount, payMonth,
                "Sales bonus " + label, "partner_sales_" + fiscalYear + "_" + userUuid);
        createLumpSumIfAbsent(userUuid, LumpSumSalaryType.PROD_BONUS, productionAmount, payMonth,
                "Produktionsbonus " + label, "partner_prod_" + fiscalYear + "_" + userUuid);

        return new PayoutResultDTO(userUuid, fiscalYear, salesAmount, productionAmount,
                round2(event.getComputedSalesBasis()), event.getPartnerCount());
    }

    /**
     * Returns the frozen payout event for (group, FY), creating it (and consuming the group's
     * un-consumed APPROVED invoices) on first call. A pessimistic write lock on the group row
     * serialises concurrent first-payouts for the same group; the unique (group, FY) constraint is
     * the hard backstop.
     */
    private PartnerBonusPayout getOrCreatePayoutEvent(BonusEligibilityGroup group, int fiscalYear,
                                                      LocalDate fyStart, LocalDate fyEnd,
                                                      LocalDate payMonth, String requestedBy) {
        // Serialise creation per group.
        Panache.getEntityManager().find(BonusEligibilityGroup.class, group.getUuid(), LockModeType.PESSIMISTIC_WRITE);

        PartnerBonusPayout existing = PartnerBonusPayout.findByGroupAndYear(group.getUuid(), fiscalYear);
        if (existing != null) return existing;

        Set<String> members = groupMemberUuids(group);
        int partnerCount = countPartnersWithRows(members, fyStart, fyEnd);

        List<String> invoiceIds = invoiceBonusService.findApprovedInvoiceIdsForUsers(members, fyStart, fyEnd, true);
        double basis = 0.0;
        for (String invId : invoiceIds) basis += invoiceBonusService.sumApprovedUnconsumed(invId, members);
        basis = round2(basis);

        double perPartner = round2(salesCalculator
                .calculateBonusPerPartner(BigDecimal.valueOf(basis), partnerCount).doubleValue());

        PartnerBonusPayout event = new PartnerBonusPayout();
        event.setPartnerGroupUuid(group.getUuid());
        event.setFiscalYear(fiscalYear);
        event.setPayoutMonth(payMonth);
        event.setComputedSalesBasis(basis);
        event.setSalesBonusPerPartner(perPartner);
        event.setPartnerCount(partnerCount);
        event.setBackfill(false);
        event.setCreatedBy(requestedBy);
        event.persist();

        // Stamp the consumed APPROVED rows so they can never fund another payout.
        int stamped = invoiceBonusService.markApprovedConsumed(invoiceIds, event.getUuid(), members);
        Log.infof("Partner payout event %s created for group %s FY %d: basis=%.2f, perPartner=%.2f, partners=%d, invoices=%d, stampedRows=%d",
                event.getUuid(), group.getUuid(), fiscalYear, basis, perPartner, partnerCount, invoiceIds.size(), stamped);
        return event;
    }

    /**
     * One-time historical backfill. For every (group, FY) that was already paid (a group member has a
     * COMMERCIAL_PARTNER_BONUS lump sum in the payment window) but has no payout event yet, create a
     * backfill event and stamp the APPROVED invoices that funded it. {@code dryRun=true} stamps nothing
     * and only returns the reconciliation report.
     */
    @Transactional
    public PartnerBonusBackfillReport backfillPaidFiscalYears(Integer fyFrom, Integer fyTo, boolean dryRun,
                                                              String requestedBy) {
        // Require both or neither bound, and keep any explicit range sane and bounded — otherwise a
        // partially-specified range silently fell through to "scan every group in every fiscal year".
        if ((fyFrom == null) != (fyTo == null)) {
            throw new BadRequestException("Provide both fiscalYearFrom and fiscalYearTo, or neither");
        }
        if (fyFrom != null && (fyFrom < 2000 || fyTo > 2099 || fyTo < fyFrom || (fyTo - fyFrom) > 20)) {
            throw new BadRequestException("Invalid fiscal year range (2000-2099, fyTo >= fyFrom, span <= 20)");
        }
        List<Integer> fiscalYears = resolveBackfillFiscalYears(fyFrom, fyTo);
        List<PartnerBonusBackfillReport.Entry> entries = new ArrayList<>();
        int considered = 0, applied = 0, invoicesStamped = 0;

        for (int fy : fiscalYears) {
            LocalDate fyStart = LocalDate.of(fy, 7, 1);
            LocalDate fyEnd = LocalDate.of(fy + 1, 6, 30);
            List<BonusEligibilityGroup> groups = BonusEligibilityGroup.list("financialYear", fy);

            for (BonusEligibilityGroup group : groups) {
                considered++;
                if (PartnerBonusPayout.findByGroupAndYear(group.getUuid(), fy) != null) {
                    entries.add(skipEntry(fy, group, "Payout event already exists"));
                    continue;
                }
                Set<String> members = groupMemberUuids(group);
                boolean paid = anyMemberPaidSales(members, fy);
                if (!paid) {
                    entries.add(skipEntry(fy, group, "Not paid (no COMMERCIAL_PARTNER_BONUS lump sum)"));
                    continue;
                }

                List<String> invoiceIds = invoiceBonusService.findApprovedInvoiceIdsForUsers(members, fyStart, fyEnd, true);
                double basis = 0.0;
                for (String invId : invoiceIds) basis += invoiceBonusService.sumApprovedUnconsumed(invId, members);
                basis = round2(basis);
                int partnerCount = countPartnersWithRows(members, fyStart, fyEnd);
                double perPartner = round2(salesCalculator
                        .calculateBonusPerPartner(BigDecimal.valueOf(basis), partnerCount).doubleValue());

                int stamped = 0;
                if (!dryRun) {
                    PartnerBonusPayout event = new PartnerBonusPayout();
                    event.setPartnerGroupUuid(group.getUuid());
                    event.setFiscalYear(fy);
                    event.setPayoutMonth(LocalDate.of(fy + 1, 7, 1));
                    event.setComputedSalesBasis(basis);
                    event.setSalesBonusPerPartner(perPartner);
                    event.setPartnerCount(partnerCount);
                    event.setBackfill(true);
                    event.setCreatedBy(requestedBy != null ? requestedBy : "BACKFILL");
                    event.persist();
                    stamped = invoiceBonusService.markApprovedConsumed(invoiceIds, event.getUuid(), members);
                    applied++;
                    invoicesStamped += invoiceIds.size();
                }

                entries.add(new PartnerBonusBackfillReport.Entry(
                        fy, group.getUuid(), group.getName(), partnerCount, true, false, null,
                        invoiceIds.size(), stamped, basis, perPartner, !dryRun));
            }
        }
        Log.infof("Partner-bonus backfill (dryRun=%s): groupsConsidered=%d, applied=%d, invoicesStamped=%d",
                dryRun, considered, applied, invoicesStamped);
        return new PartnerBonusBackfillReport(dryRun, considered, applied, invoicesStamped, entries);
    }

    // --- helpers ---

    private double sumOwnRevenue(String userUuid, LocalDate fyStart, LocalDate fyEnd) {
        try {
            List<DateValueDTO> series = revenueService.getRegisteredRevenueByPeriodAndSingleConsultant(
                    userUuid, stringIt(fyStart), stringIt(fyEnd));
            return series == null ? 0.0 : series.stream()
                    .mapToDouble(dv -> dv.getValue() != null ? dv.getValue() : 0.0)
                    .sum();
        } catch (Exception e) {
            Log.warnf("Failed to get revenue for %s: %s", userUuid, e.getMessage());
            return 0.0;
        }
    }

    private void createLumpSumIfAbsent(String userUuid, LumpSumSalaryType type, double amount,
                                       LocalDate month, String description, String sourceRef) {
        if (amount <= 0.01) return;
        if (SalaryLumpSum.find("sourceReference", sourceRef).firstResultOptional().isPresent()) {
            Log.infof("Skipping %s payout for %s — already exists (%s)", type, userUuid, sourceRef);
            return;
        }
        SalaryLumpSum ls = new SalaryLumpSum();
        ls.setUuid(UUID.randomUUID().toString());
        ls.setUseruuid(userUuid);
        ls.setSalaryType(type);
        ls.setLumpSum(amount);
        ls.setPension(false);
        ls.setMonth(month);
        ls.setDescription(description);
        ls.setSourceReference(sourceRef);
        salaryLumpSumService.create(ls);
        Log.infof("Created %s for partner %s: %.2f (%s)", type, userUuid, amount, sourceRef);
    }

    private Set<String> groupMemberUuids(BonusEligibilityGroup group) {
        return BonusEligibility.<BonusEligibility>stream("group.uuid = ?1", group.getUuid())
                .map(BonusEligibility::getUseruuid)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
    }

    /**
     * Count group members that have at least one bonus row (any status) on an invoice in the FY
     * window — the same denominator the dashboard preview uses to split the group sales bonus.
     */
    private int countPartnersWithRows(Set<String> members, LocalDate fyStart, LocalDate fyEnd) {
        if (members == null || members.isEmpty()) return 0;
        Long count = Panache.getEntityManager().createQuery("""
                SELECT COUNT(DISTINCT b.useruuid)
                FROM dk.trustworks.intranet.aggregates.invoice.model.Invoice i,
                     dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                WHERE b.invoiceuuid = i.uuid
                  AND b.useruuid IN :members
                  AND i.invoicedate >= :from
                  AND i.invoicedate <= :to
                """, Long.class)
                .setParameter("members", members)
                .setParameter("from", fyStart)
                .setParameter("to", fyEnd)
                .getSingleResult();
        return count == null ? 0 : count.intValue();
    }

    private boolean anyMemberPaidSales(Set<String> members, int fiscalYear) {
        if (members == null || members.isEmpty()) return false;
        LocalDate paymentFyStart = LocalDate.of(fiscalYear + 1, 7, 1);
        LocalDate paymentFyEnd = LocalDate.of(fiscalYear + 2, 6, 30);
        Long count = Panache.getEntityManager().createQuery("""
                SELECT COUNT(s)
                FROM dk.trustworks.intranet.domain.user.entity.SalaryLumpSum s
                WHERE s.useruuid IN :members
                  AND s.salaryType = :type
                  AND s.month >= :from
                  AND s.month <= :to
                """, Long.class)
                .setParameter("members", members)
                .setParameter("type", LumpSumSalaryType.COMMERCIAL_PARTNER_BONUS)
                .setParameter("from", paymentFyStart)
                .setParameter("to", paymentFyEnd)
                .getSingleResult();
        return count != null && count > 0;
    }

    private List<Integer> resolveBackfillFiscalYears(Integer fyFrom, Integer fyTo) {
        if (fyFrom != null && fyTo != null) {
            List<Integer> list = new ArrayList<>();
            for (int y = fyFrom; y <= fyTo; y++) list.add(y);
            return list;
        }
        // Derive from all groups that have a financial year (covers every payable FY).
        return BonusEligibilityGroup.<BonusEligibilityGroup>streamAll()
                .map(BonusEligibilityGroup::getFinancialYear)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private static PartnerBonusBackfillReport.Entry skipEntry(int fy, BonusEligibilityGroup group, String reason) {
        return new PartnerBonusBackfillReport.Entry(fy, group.getUuid(), group.getName(),
                0, false, true, reason, 0, 0, 0.0, 0.0, false);
    }

    private static String fiscalYearLabel(int fiscalYear) {
        return fiscalYear + "/" + String.format("%02d", (fiscalYear + 1) % 100);
    }

    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }
}
