package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural regression test for the two-query revenue/cost model (executive dashboard).
 *
 * <p>The exact figures (group ≈146.9M reg / ≈142.7M inv, A/S single inv ≈106M) are validated
 * against the prod oracle — the test DB profile carries no such fixture. This test instead
 * locks in the SQL SHAPE that makes those figures correct, invoking the private builders
 * reflectively (no DB / Quarkus context required):
 * <ul>
 *   <li><b>Group</b> path omits INTERNAL and internal credit notes entirely (external only),
 *       so every intercompany document nets to zero at the group grain.</li>
 *   <li><b>Entity</b> path sources the intercompany transfer from the invoice table on BOTH
 *       sides — internal SOLD by the set (issuer∈S, +1) and internal PURCHASED by the set
 *       (debtor∈S, −1) — and admits a row when issuer∈S OR debtor∈S. This is the invariant
 *       that keeps the transfer price out of the GL cost side.</li>
 *   <li>The GL direct-cost query excludes the intercompany transfer-price accounts
 *       (3050/3055/3070/3075/1350) so direct delivery is external subcontractors only.</li>
 * </ul>
 */
class CostAnalyticsResourceInvoiceRevenueSqlTest {

    private String groupSql(boolean workPeriod) throws Exception {
        return groupSql(workPeriod, CostSource.BOOKED);
    }

    private String groupSql(boolean workPeriod, CostSource costSource) throws Exception {
        Method m = CostAnalyticsResource.class.getDeclaredMethod(
                "buildGroupInvoiceRevenueSql", boolean.class, CostSource.class);
        m.setAccessible(true);
        return (String) m.invoke(null, workPeriod, costSource);
    }

    private String entitySql(boolean workPeriod) throws Exception {
        return entitySql(workPeriod, CostSource.BOOKED);
    }

    private String entitySql(boolean workPeriod, CostSource costSource) throws Exception {
        Method m = CostAnalyticsResource.class.getDeclaredMethod(
                "buildEntityInvoiceRevenueSql", boolean.class, CostSource.class);
        m.setAccessible(true);
        return (String) m.invoke(null, workPeriod, costSource);
    }

    @SuppressWarnings("unchecked")
    private String glDirectSql(Set<String> companies) throws Exception {
        Method m = CostAnalyticsResource.class.getDeclaredMethod("buildMonthlyGlDirectCostSql", Set.class);
        m.setAccessible(true);
        return (String) m.invoke(null, companies);
    }

    // ── Group invoice revenue: external only ─────────────────────────────────

    @Test
    void groupInvoiceRevenue_omitsInternalAndInternalCreditNotes() throws Exception {
        String sql = groupSql(false);
        // No INTERNAL leg at all in the group path — internal nets to zero within the group.
        assertFalse(sql.contains("INTERNAL"),
                "group revenue SQL must not reference INTERNAL (external-only consolidation)");
        // External credit notes only (debtor NULL), and no debtor-set params.
        assertTrue(sql.contains("i.debtor_companyuuid IS NULL"),
                "group revenue keeps external credit notes (debtor NULL) at −1");
        assertFalse(sql.contains(":companyIds"),
                "group revenue SQL must bind no companyIds param");
        assertTrue(sql.contains("INVOICE") && sql.contains("PHANTOM"),
                "group revenue keeps external INVOICE/PHANTOM at +1");
    }

    @Test
    void groupInvoiceRevenue_bucketsByWorkPeriodWhenRequested() throws Exception {
        assertTrue(groupSql(true).contains("i.year"),
                "WORK_PERIOD group revenue buckets by i.year/i.month");
        assertTrue(groupSql(false).contains("YEAR(i.invoicedate)"),
                "INVOICED group revenue buckets by invoicedate");
    }

    // ── Entity invoice revenue: symmetric intercompany netting ───────────────

    @Test
    void entityInvoiceRevenue_hasBothSellerAndBuyerInternalLegs() throws Exception {
        String sql = entitySql(false);
        // Seller leg: internal issued by the set → +1.
        assertTrue(sql.contains("i.type = 'INTERNAL' AND i.companyuuid IN (:companyIds) THEN 1"),
                "entity revenue must add +1 for internal SOLD by the set (issuer∈S)");
        // Buyer leg: internal billed to the set → −1 (sourced from the invoice table, not GL).
        assertTrue(sql.contains("i.type = 'INTERNAL' AND i.debtor_companyuuid IN (:companyIds) THEN -1"),
                "entity revenue must subtract −1 for internal PURCHASED by the set (debtor∈S)");
    }

    @Test
    void entityInvoiceRevenue_hasSymmetricInternalCreditNoteLegs() throws Exception {
        String sql = entitySql(false);
        assertTrue(sql.contains("i.type = 'CREDIT_NOTE' AND i.debtor_companyuuid IS NOT NULL AND i.companyuuid IN (:companyIds) THEN -1"),
                "internal credit note issued by the set → −1");
        assertTrue(sql.contains("i.type = 'CREDIT_NOTE' AND i.debtor_companyuuid IS NOT NULL AND i.debtor_companyuuid IN (:companyIds) THEN 1"),
                "internal credit note billed to the set → +1");
    }

    @Test
    void entityInvoiceRevenue_admitsRowsWhereIssuerOrDebtorInSet() throws Exception {
        String sql = entitySql(false);
        // The WHERE clause must let the buyer's row in even when the issuer is outside the set.
        assertTrue(sql.contains("i.companyuuid IN (:companyIds) OR i.debtor_companyuuid IN (:companyIds)"),
                "entity revenue must admit a row when issuer∈S OR debtor∈S");
        // Type/status gate unchanged: INTERNAL is QUEUED/CREATED, external CREATED.
        assertTrue(sql.contains("i.type = 'INTERNAL'            AND i.status IN ('QUEUED', 'CREATED')"),
                "INTERNAL status gate stays QUEUED/CREATED");
    }

    // ── Foreign currency ─────────────────────────────────────────────────────

    @Test
    void bothBuilders_convertForeignCurrencyToDkk() throws Exception {
        // Until V503 neither builder read invoices.currency, so a EUR or SEK invoice
        // was summed at face value as kroner: FY25/26 understated Technology's 6 EUR
        // invoices by 1,100,566 DKK and overstated A/S's 9 SEK invoices by 453,469.
        for (String sql : new String[]{groupSql(true), groupSql(false), entitySql(true), entitySql(false)}) {
            assertTrue(sql.contains("COALESCE(i.exchange_rate, 1)"),
                    "every revenue builder must convert the line total at the invoice's own rate");
        }
    }

    @Test
    void fxFactor_appliesToTheLineTotalNotJustTheSign() throws Exception {
        // The factor must multiply (rate*hours), not sit beside the +1/-1 type factor,
        // or a credit note would convert while its invoice did not.
        assertTrue(groupSql(false).contains("(ii.rate * ii.hours) * COALESCE(i.exchange_rate, 1)"),
                "group builder converts the line total");
        assertTrue(entitySql(false).contains("(ii.rate * ii.hours) * COALESCE(i.exchange_rate, 1)"),
                "entity builder converts the line total");
    }

    @Test
    void fxFactor_isNullSafeSoKroneInvoicesAreUnchanged() throws Exception {
        // exchange_rate is NULL on every DKK invoice; without the COALESCE the whole
        // revenue line would evaluate to NULL and the dashboard would read zero.
        assertFalse(groupSql(false).contains("* i.exchange_rate"),
                "the rate must always be read through COALESCE, never bare");
        assertFalse(entitySql(false).contains("* i.exchange_rate"),
                "the rate must always be read through COALESCE, never bare");
    }

    // ── D10: the cost-source toggle is symmetric ─────────────────────────────

    @Test
    void bookedOnly_excludesTheDraftRevenueMirror() throws Exception {
        for (String sql : new String[]{groupSql(true, CostSource.BOOKED), entitySql(true, CostSource.BOOKED)}) {
            assertTrue(sql.contains("i.economics_posting_status IS NULL OR i.economics_posting_status = 'BOOKED'"),
                    "BOOKED must keep exactly the historical population — no draft-sourced rows");
            assertFalse(sql.contains("'DRAFT'"),
                    "BOOKED must not admit draft-sourced revenue");
        }
    }

    @Test
    void bookedPlusDraft_admitsTheDraftRevenueMirror() throws Exception {
        // Before this, BOOKED_PLUS_DRAFT widened the posting-status filter on COST with
        // no matching widening on REVENUE, so the toggle could only move the result down.
        for (String sql : new String[]{groupSql(true, CostSource.BOOKED_PLUS_DRAFT),
                                       entitySql(true, CostSource.BOOKED_PLUS_DRAFT)}) {
            assertTrue(sql.contains("i.economics_posting_status IN ('BOOKED', 'DRAFT')"),
                    "BOOKED_PLUS_DRAFT must admit draft-sourced revenue, mirroring the cost side");
        }
    }

    @Test
    void everyInvoiceNotFromTheImporter_survivesBothTogglepositions() throws Exception {
        // economics_posting_status is NULL on every manually created invoice. If the gate
        // were not null-safe, BOOKED would drop the entire real invoice population.
        for (CostSource cs : CostSource.values()) {
            assertTrue(groupSql(true, cs).contains("i.economics_posting_status IS NULL"),
                    "the posting-status gate must always admit NULL: " + cs);
            assertTrue(entitySql(true, cs).contains("i.economics_posting_status IS NULL"),
                    "the posting-status gate must always admit NULL: " + cs);
        }
    }

    // ── GL direct cost: external subcontractors only ─────────────────────────

    @Test
    void glDirectCost_excludesIntercompanyTransferPriceAccounts() throws Exception {
        String sql = glDirectSql(null);
        assertTrue(sql.contains("fd.accountnumber NOT IN (3050, 3055, 3070, 3075, 1350)"),
                "direct delivery GL must exclude the intercompany transfer-price accounts");
        assertTrue(sql.contains("aa.cost_type = 'DIRECT_COSTS'"),
                "direct delivery still restricted to DIRECT_COSTS accounts");
    }
}
