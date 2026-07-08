package dk.trustworks.intranet.aggregates.finance.resources;

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
        Method m = CostAnalyticsResource.class.getDeclaredMethod("buildGroupInvoiceRevenueSql", boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, workPeriod);
    }

    private String entitySql(boolean workPeriod) throws Exception {
        Method m = CostAnalyticsResource.class.getDeclaredMethod("buildEntityInvoiceRevenueSql", boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, workPeriod);
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
