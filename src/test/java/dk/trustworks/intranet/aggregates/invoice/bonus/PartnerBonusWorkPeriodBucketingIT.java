package dk.trustworks.intranet.aggregates.invoice.bonus;

import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibility;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.BonusEligibilityGroup;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.resources.BonusEligibilityGroupResource;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.MyBonusFySum;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.MyBonusRow;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed integration test for the partner-bonus work-period FY bucketing + cancellation-exclusion
 * spec (§8 acceptance criteria / §9.2 test list).
 *
 * <p><b>Environment note:</b> this test needs a MariaDB datasource and CANNOT boot in a DB-less
 * sandbox. It is written to run in CI / a DB-backed environment. It follows the
 * {@code PhantomAttributionServiceTest} pattern: a {@link TestProfile} that disables S3 dev-services
 * and supplies CV-tool placeholders, fixtures seeded via entity {@code .persist()} inside
 * {@code @Transactional} helpers, strong assertions against deterministic uuids, and cleanup in a
 * {@code finally} block.</p>
 *
 * <p>All seeded rows carry the {@link #TAG} prefix in their UUID so cleanup is exact and can never
 * touch real data.</p>
 */
@QuarkusTest
@TestProfile(PartnerBonusWorkPeriodBucketingIT.NoDevServicesProfile.class)
class PartnerBonusWorkPeriodBucketingIT {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    /** Unique marker so every fixture is isolated and cleanup is exact. */
    private static final String TAG = "wpbkt-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    // FY2025/26 window: July 1 2025 -> June 30 2026 (inclusive for the payout-basis surfaces).
    private static final LocalDate FY2025_START = LocalDate.of(2025, 7, 1);
    private static final LocalDate FY2025_END   = LocalDate.of(2026, 6, 30);
    // Half-open upper bound used by the my-bonus surfaces (WP < :to).
    private static final LocalDate FY2025_TO_EXCL = LocalDate.of(2026, 7, 1);
    // FY2026/27 window.
    private static final LocalDate FY2026_START = LocalDate.of(2026, 7, 1);
    private static final LocalDate FY2026_END   = LocalDate.of(2027, 6, 30);

    private static final double INVOICE_TOTAL = 100_000.0; // 100 hours * 1000 rate
    private static final double BONUS_AMOUNT  = 5_000.0;

    @Inject InvoiceBonusService bonusService;
    @Inject InvoiceService invoiceService;
    @Inject BonusEligibilityGroupResource groupResource;
    @Inject EntityManager em;

    // ---------------------------------------------------------------------------------------------
    // 1. Work-period bucketing on the payout basis (findApprovedInvoiceIdsForUsers)
    // ---------------------------------------------------------------------------------------------

    @Test
    void findApprovedInvoiceIdsForUsers_bucketsJuneWorkJulyIssueIntoWorkPeriodFy_andExcludesConsumed() {
        String user = TAG + "userA";
        // June-2026 work, July-2026 issue, APPROVED, unconsumed.
        String openInv = seedInvoice(TAG + "open", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "bonusOpen", openInv, user, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        // Same shape but CONSUMED (payout_uuid set).
        String consumedInv = seedInvoice(TAG + "consumed", 2026, 6, LocalDate.of(2026, 7, 6), INVOICE_TOTAL);
        seedBonus(TAG + "bonusConsumed", consumedInv, user, SalesApprovalStatus.APPROVED,
                TAG + "payout", BONUS_AMOUNT);

        try {
            List<String> fy2025 = bonusService.findApprovedInvoiceIdsForUsers(
                    Set.of(user), FY2025_START, FY2025_END, true);
            assertTrue(fy2025.contains(openInv),
                    "June-work/July-issue invoice must count toward FY2025/26 by work period");
            assertFalse(fy2025.contains(consumedInv),
                    "consumed (payout_uuid set) invoice is excluded when onlyUnconsumed=true");

            List<String> fy2026 = bonusService.findApprovedInvoiceIdsForUsers(
                    Set.of(user), FY2026_START, FY2026_END, true);
            assertFalse(fy2026.contains(openInv),
                    "the invoice must NOT count toward FY2026/27 (its work period is June 2026)");
        } finally {
            cleanup();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 2. D8 cancellation exclusion across every switched surface
    // ---------------------------------------------------------------------------------------------

    @Test
    void cancellationExclusion_fullyCreditedInvoice_isExcludedEverywhere_thenSelfReverses() {
        String user = TAG + "userD8";
        // Fully-credited original: June-2026 work, July-2026 issue, APPROVED unconsumed bonus.
        String orig = seedInvoice(TAG + "d8orig", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "d8bonus", orig, user, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        // Live credit note (status CREATED) fully reversing the original (#28080-shaped).
        String cn = seedCreditNote(TAG + "d8cn", orig, 2026, 6, InvoiceStatus.CREATED, INVOICE_TOTAL);

        try {
            // (a) Fully credited by a LIVE credit note -> excluded from every basis/view surface.
            assertFalse(basisFy2025(user).contains(orig),
                    "payout basis excludes a fully live-credited invoice");
            assertEquals(0, countMyBonusFy2025(user),
                    "countMyBonus excludes a fully live-credited invoice");
            assertTrue(myBonusPageFy2025(user).stream().noneMatch(r -> r.invoiceuuid().equals(orig)),
                    "findMyBonusPage excludes a fully live-credited invoice");
            assertEquals(0.0, fy2025SummaryApproved(user), 0.001,
                    "myBonusFySummary excludes a fully live-credited invoice");
            assertEquals(0.0, approvedTotalFy2025(user), 0.001,
                    "approved-total preview excludes a fully live-credited invoice");

            // (b) Flip the credit note to DRAFT (not live) -> the invoice re-enters everywhere.
            setInvoiceStatus(cn, InvoiceStatus.DRAFT);
            assertTrue(basisFy2025(user).contains(orig),
                    "DRAFT credit note is not live -> invoice re-enters the basis (self-reversing)");
            assertEquals(1, countMyBonusFy2025(user),
                    "DRAFT credit note -> invoice counted again in countMyBonus");
            assertTrue(myBonusPageFy2025(user).stream().anyMatch(r -> r.invoiceuuid().equals(orig)),
                    "DRAFT credit note -> invoice back in findMyBonusPage");
            assertEquals(BONUS_AMOUNT, approvedTotalFy2025(user), 0.001,
                    "DRAFT credit note -> approved-total includes the invoice's approved bonus");
        } finally {
            cleanup();
        }
    }

    @Test
    void cancellationExclusion_partialCredit_isNotExcluded() {
        String user = TAG + "userPartial";
        String orig = seedInvoice(TAG + "partOrig", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "partBonus", orig, user, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        // Live credit note covering only half the invoice total -> partial credit, NOT excluded.
        seedCreditNote(TAG + "partCn", orig, 2026, 6, InvoiceStatus.CREATED, INVOICE_TOTAL / 2.0);

        try {
            assertTrue(basisFy2025(user).contains(orig),
                    "a partially credited invoice keeps its bonus (net revenue is real)");
            assertEquals(1, countMyBonusFy2025(user),
                    "partial credit -> still counted");
        } finally {
            cleanup();
        }
    }

    @Test
    void cancellationExclusion_splitLiveCreditNotesSummingToFull_isExcluded() {
        String user = TAG + "userSplit";
        String orig = seedInvoice(TAG + "splitOrig", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "splitBonus", orig, user, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        // Two live credit notes each covering half; together they fully reverse the original.
        seedCreditNote(TAG + "splitCn1", orig, 2026, 6, InvoiceStatus.CREATED, INVOICE_TOTAL / 2.0);
        seedCreditNote(TAG + "splitCn2", orig, 2026, 6, InvoiceStatus.QUEUED, INVOICE_TOTAL / 2.0);

        try {
            assertFalse(basisFy2025(user).contains(orig),
                    "two live credit notes summing to the full amount -> excluded");
        } finally {
            cleanup();
        }
    }

    @Test
    void cancellationExclusion_plainCreditNoteCarryingBonus_isNotExcluded() {
        String user = TAG + "userCnBonus";
        // A CREDIT_NOTE invoice that itself carries a bonus and is NOT cancelled by anything.
        String cnInv = seedInvoiceOfType(TAG + "plainCn", InvoiceType.CREDIT_NOTE, InvoiceStatus.CREATED,
                2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL, null);
        seedBonus(TAG + "plainCnBonus", cnInv, user, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);

        try {
            assertTrue(basisFy2025(user).contains(cnInv),
                    "a plain CREDIT_NOTE carrying a bonus is not 'cancelled' by anything -> not excluded");
        } finally {
            cleanup();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 3. Denominator (countPartnersWithRows twin): distinct basis users, cancelled partner excluded
    // ---------------------------------------------------------------------------------------------

    @Test
    void denominator_countsDistinctBasisUsers_andExcludesCancelledOnlyPartner() {
        String userA = TAG + "denA";
        String userB = TAG + "denB";
        String userC = TAG + "denC"; // only rows on a cancelled invoice
        Set<String> members = Set.of(userA, userB, userC);

        String invA = seedInvoice(TAG + "denInvA", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "denBonusA", invA, userA, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        String invB = seedInvoice(TAG + "denInvB", 2026, 5, LocalDate.of(2026, 6, 2), INVOICE_TOTAL);
        seedBonus(TAG + "denBonusB", invB, userB, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        // userC only appears on a fully-cancelled invoice.
        String invC = seedInvoice(TAG + "denInvC", 2026, 6, LocalDate.of(2026, 7, 5), INVOICE_TOTAL);
        seedBonus(TAG + "denBonusC", invC, userC, SalesApprovalStatus.APPROVED, null, BONUS_AMOUNT);
        seedCreditNote(TAG + "denCnC", invC, 2026, 6, InvoiceStatus.CREATED, INVOICE_TOTAL);

        try {
            // countPartnersWithRows is private on PartnerBonusPayoutService; this is its exact runnable
            // twin, built from the same public shared SQL constants it uses (WP_DATE_SQL + NOT_FULLY_CREDITED).
            int denominator = countPartnersWithRowsTwin(members, FY2025_START, FY2025_END);
            assertEquals(2, denominator,
                    "denominator counts distinct basis users; a partner with only cancelled rows is not counted");

            // Cross-check: the basis invoice set's distinct users match {userA, userB}.
            Set<String> basisUsers = distinctBasisUsers(members, FY2025_START, FY2025_END);
            assertEquals(Set.of(userA, userB), basisUsers,
                    "basis distinct users exclude the cancelled-only partner");
            assertEquals(basisUsers.size(), denominator,
                    "denominator equals the basis set's distinct-user count");
        } finally {
            cleanup();
        }
    }

    // ============================ query helpers (thin wrappers) ============================

    private List<String> basisFy2025(String user) {
        return bonusService.findApprovedInvoiceIdsForUsers(Set.of(user), FY2025_START, FY2025_END, true);
    }

    private long countMyBonusFy2025(String user) {
        return invoiceService.countMyBonus(user, null, FY2025_START, FY2025_TO_EXCL);
    }

    private List<MyBonusRow> myBonusPageFy2025(String user) {
        return invoiceService.findMyBonusPage(user, null, FY2025_START, FY2025_TO_EXCL, 0, 50);
    }

    private double fy2025SummaryApproved(String user) {
        return invoiceService.myBonusFySummary(user).stream()
                .filter(s -> s.fyStart().equals(FY2025_START))
                .mapToDouble(MyBonusFySum::approved)
                .sum();
    }

    private double approvedTotalFy2025(String user) {
        BonusEligibilityGroup group = seedGroupWithMember(2025, user);
        BonusEligibilityGroupResource.ApprovedTotalDTO dto =
                groupResource.approvedTotalEndpoint(group.getUuid(), 2025);
        return dto.approvedTotal();
    }

    /** Exact twin of the private {@code PartnerBonusPayoutService.countPartnersWithRows}. */
    private int countPartnersWithRowsTwin(Set<String> members, LocalDate from, LocalDate to) {
        String sql = "SELECT COUNT(DISTINCT b.useruuid)"
                + " FROM invoices i JOIN invoice_bonuses b ON b.invoiceuuid = i.uuid"
                + " WHERE b.useruuid IN (:members)"
                + "   AND " + InvoiceBonusService.WP_DATE_SQL + " >= :from"
                + "   AND " + InvoiceBonusService.WP_DATE_SQL + " <= :to"
                + InvoiceBonusService.NOT_FULLY_CREDITED_SQL;
        Object r = em.createNativeQuery(sql)
                .setParameter("members", members)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return r == null ? 0 : ((Number) r).intValue();
    }

    private Set<String> distinctBasisUsers(Set<String> members, LocalDate from, LocalDate to) {
        String sql = "SELECT DISTINCT b.useruuid"
                + " FROM invoices i JOIN invoice_bonuses b ON b.invoiceuuid = i.uuid"
                + " WHERE b.status = :approved"
                + "   AND b.useruuid IN (:members)"
                + "   AND " + InvoiceBonusService.WP_DATE_SQL + " >= :from"
                + "   AND " + InvoiceBonusService.WP_DATE_SQL + " <= :to"
                + InvoiceBonusService.NOT_FULLY_CREDITED_SQL;
        List<?> raw = em.createNativeQuery(sql)
                .setParameter("approved", SalesApprovalStatus.APPROVED.name())
                .setParameter("members", members)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
        return raw.stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
    }

    // ============================ fixture seeding (all @Transactional) ============================

    private String seedInvoice(String prefix, int year, int month, LocalDate invoicedate, double total) {
        return seedInvoiceOfType(prefix, InvoiceType.INVOICE, InvoiceStatus.CREATED,
                year, month, invoicedate, total, null);
    }

    private String seedCreditNote(String prefix, String creditnoteForUuid, int year, int month,
                                  InvoiceStatus status, double total) {
        return seedInvoiceOfType(prefix, InvoiceType.CREDIT_NOTE, status,
                year, month, LocalDate.now(), total, creditnoteForUuid);
    }

    @Transactional
    String seedInvoiceOfType(String prefix, InvoiceType type, InvoiceStatus status,
                             int year, int month, LocalDate invoicedate, double total,
                             String creditnoteForUuid) {
        Invoice inv = new Invoice();
        inv.uuid = prefix + "-inv-" + UUID.randomUUID().toString().substring(0, 8);
        inv.type = type;
        inv.status = status;
        inv.year = year;
        inv.month = month;
        inv.invoicedate = invoicedate;
        inv.invoicenumber = 0;
        inv.currency = "DKK";
        inv.clientname = "WP Bucketing IT";
        inv.creditnoteForUuid = creditnoteForUuid;
        inv.invoiceitems = new java.util.LinkedList<>();
        inv.persist();

        // One BASE item worth `total` (100 hours * (total/100) rate).
        InvoiceItem item = new InvoiceItem();
        item.uuid = prefix + "-item-" + UUID.randomUUID().toString().substring(0, 8);
        item.invoiceuuid = inv.uuid;
        item.itemname = "work";
        item.description = "work";
        item.hours = 100.0;
        item.rate = total / 100.0;
        item.position = 0;
        item.persist();

        return inv.uuid;
    }

    @Transactional
    void seedBonus(String prefix, String invoiceuuid, String useruuid, SalesApprovalStatus status,
                   String payoutUuid, double computedAmount) {
        InvoiceBonus b = new InvoiceBonus();
        b.uuid = prefix + "-bonus-" + UUID.randomUUID().toString().substring(0, 8);
        b.invoiceuuid = invoiceuuid;
        b.useruuid = useruuid;
        b.addedBy = useruuid;
        b.shareType = InvoiceBonus.ShareType.AMOUNT;
        b.shareValue = computedAmount;
        b.computedAmount = computedAmount;
        b.status = status;
        b.payoutUuid = payoutUuid;
        b.persist();
    }

    @Transactional
    BonusEligibilityGroup seedGroupWithMember(int financialYear, String useruuid) {
        BonusEligibilityGroup g = new BonusEligibilityGroup();
        g.uuid = TAG + "grp-" + UUID.randomUUID().toString().substring(0, 8);
        g.name = "WP IT group";
        g.financialYear = financialYear;
        g.persist();

        BonusEligibility be = new BonusEligibility();
        be.uuid = TAG + "elig-" + UUID.randomUUID().toString().substring(0, 8);
        be.group = g;
        be.useruuid = useruuid;
        be.financialYear = financialYear;
        be.canSelfAssign = true;
        be.persist();
        return g;
    }

    @Transactional
    void setInvoiceStatus(String invoiceuuid, InvoiceStatus status) {
        em.createNativeQuery("UPDATE invoices SET status = :s WHERE uuid = :u")
                .setParameter("s", status.name())
                .setParameter("u", invoiceuuid)
                .executeUpdate();
    }

    @Transactional
    void cleanup() {
        String like = TAG + "%";
        em.createNativeQuery("DELETE FROM invoice_bonuses WHERE uuid LIKE :p").setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoice_bonus_eligibility WHERE uuid LIKE :p").setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoice_bonus_eligibility_group WHERE uuid LIKE :p").setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoiceitems WHERE uuid LIKE :p").setParameter("p", like).executeUpdate();
        em.createNativeQuery("DELETE FROM invoices WHERE uuid LIKE :p").setParameter("p", like).executeUpdate();
    }
}
