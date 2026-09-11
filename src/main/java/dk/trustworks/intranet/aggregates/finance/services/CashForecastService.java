package dk.trustworks.intranet.aggregates.finance.services;

import dk.trustworks.intranet.aggregates.finance.dto.growth.CashForecastDTO;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Inputs;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.InvoiceRow;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.Params;
import dk.trustworks.intranet.aggregates.finance.services.CashForecastEngine.SalaryMonth;
import dk.trustworks.intranet.financeservice.model.BankLedgerEntry;
import dk.trustworks.intranet.financeservice.services.BankLiquidityService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import lombok.extern.jbosslog.JBossLog;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;
import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.toDouble;

/**
 * Loads the {@link CashForecastEngine} inputs from production data as they
 * were known at the as-of date and runs the engine. Group-level only, like the
 * rest of the Growth &amp; Scenarios endpoints.
 *
 * <p>Sources: {@code fact_bank_ledger_entry} (bank + debtor ledgers, imported
 * nightly from e-conomic), {@code invoices}/{@code invoiceitems} (issued
 * external invoices with due dates, VAT and work months), {@code fact_user_day}
 * (registered billable amount per work month), {@code fact_revenue_budget}
 * (contracted budget per month), {@code fact_salary_monthly} and
 * {@code salary_lump_sum} (payroll base and scheduled bonuses),
 * {@code tw_bonus_pool_config} (the coming October bonus pool).</p>
 */
@JBossLog
@ApplicationScoped
public class CashForecastService {

    /** Months of invoice / work history loaded before the as-of date (measurement windows need ≤ 15). */
    static final int HISTORY_MONTHS = 15;

    @Inject
    EntityManager em;

    @Inject
    BankLiquidityService bankLiquidityService;

    @Inject
    GrowthAnalyticsService growthAnalyticsService;

    public CashForecastDTO forecast(LocalDate asOf, int horizonMonths, Double dividendAnnualDkk, double extensionFillPct) {
        Inputs inputs = loadInputs(asOf, horizonMonths);
        return CashForecastEngine.run(inputs, new Params(horizonMonths, dividendAnnualDkk, extensionFillPct));
    }

    Inputs loadInputs(LocalDate asOf, int horizonMonths) {
        YearMonth asOfMonth = YearMonth.from(asOf);
        LocalDate historyFrom = asOfMonth.minusMonths(HISTORY_MONTHS).atDay(1);
        String historyFromKey = CashForecastEngine.monthKey(YearMonth.from(historyFrom));
        String asOfMonthKey = CashForecastEngine.monthKey(asOfMonth);
        String horizonEndKey = CashForecastEngine.monthKey(asOfMonth.plusMonths(Math.max(1, horizonMonths)));

        return new Inputs(
                asOf,
                bankLiquidityService.ledgerLines(BankLedgerEntry.LEDGER_BANK),
                bankLiquidityService.ledgerLines(BankLedgerEntry.LEDGER_DEBTOR),
                queryInvoices(historyFrom, asOf),
                queryInternalInvoiceNumbers(),
                queryRegisteredByMonth(historyFrom, asOf),
                queryBudgetByMonth(historyFromKey, horizonEndKey),
                querySalaryByMonth(historyFromKey, asOfMonthKey),
                queryScheduledLumpSums(asOfMonth.atDay(1)),
                queryBonusPool(lastCompletedFiscalYear(asOf)),
                growthAnalyticsService.queryMonthlyRevenue(
                        CashForecastEngine.monthKey(asOfMonth.minusMonths(38)), asOfMonthKey));
    }

    /** Fiscal year (named by its starting calendar year) that ended before {@code asOf}. */
    static int lastCompletedFiscalYear(LocalDate asOf) {
        return asOf.getMonthValue() >= 7 ? asOf.getYear() - 1 : asOf.getYear() - 2;
    }

    // ------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------

    /**
     * Invoices dated inside {@code [from, asOf]}: external ones (INVOICE, PHANTOM,
     * external CREDIT_NOTE) with the same netting and currency conversion as the
     * growth timeline's revenue query, plus due date, VAT, billing client, work
     * month and the invoice a credit note cancels — and the intercompany ones
     * (INTERNAL, INTERNAL_SERVICE, internal credit notes) flagged {@code internal},
     * which the engine uses only for each company's output VAT.
     */
    List<InvoiceRow> queryInvoices(LocalDate from, LocalDate asOf) {
        String sql = "SELECT i.companyuuid AS companyuuid, i.invoicenumber AS invoicenumber, i.type AS type, " +
                "  i.invoicedate AS invoicedate, i.duedate AS duedate, i.billing_client_uuid AS billing_client_uuid, " +
                "  CONCAT(i.year, LPAD(i.month, 2, '0')) AS work_month_key, " +
                "  cn.invoicenumber AS credit_note_for_number, " +
                "  CASE WHEN i.type IN ('INTERNAL', 'INTERNAL_SERVICE') " +
                "    OR (i.type = 'CREDIT_NOTE' AND i.debtor_companyuuid IS NOT NULL) THEN 1 ELSE 0 END AS internal, " +
                "  COALESCE(SUM(ii.rate * ii.hours " +
                "    * CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END " +
                "    * CASE WHEN i.currency = 'DKK' THEN 1 ELSE COALESCE(cur.conversion, 1) END), 0) AS net_dkk, " +
                "  COALESCE(SUM(ii.rate * ii.hours * COALESCE(i.vat, 0) / 100 " +
                "    * CASE WHEN i.type = 'CREDIT_NOTE' THEN -1 ELSE 1 END " +
                "    * CASE WHEN i.currency = 'DKK' THEN 1 ELSE COALESCE(cur.conversion, 1) END), 0) AS vat_dkk " +
                "FROM invoices i " +
                "LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid AND ii.rate IS NOT NULL AND ii.hours IS NOT NULL " +
                "LEFT JOIN currences cur ON cur.currency = i.currency AND cur.month = DATE_FORMAT(i.invoicedate, '%Y%m') " +
                "LEFT JOIN invoices cn ON cn.uuid = i.creditnote_for_uuid " +
                "WHERE i.status = 'CREATED' " +
                "  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE', 'INTERNAL', 'INTERNAL_SERVICE') " +
                "  AND i.invoicedate BETWEEN :from AND :asOf " +
                "GROUP BY i.uuid, i.companyuuid, i.invoicenumber, i.type, i.invoicedate, i.duedate, " +
                "  i.billing_client_uuid, i.year, i.month, cn.invoicenumber, i.debtor_companyuuid";
        Query query = em.createNativeQuery(sql, Tuple.class);
        query.setParameter("from", from);
        query.setParameter("asOf", asOf);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        List<InvoiceRow> result = new ArrayList<>();
        for (Tuple row : runTupleQuery(query, "queryInvoices")) {
            result.add(new InvoiceRow(
                    row.get("companyuuid", String.class),
                    toInteger(row.get("invoicenumber")),
                    row.get("type", String.class),
                    toLocalDate(row.get("invoicedate")),
                    toLocalDate(row.get("duedate")),
                    toDouble(row.get("net_dkk")),
                    toDouble(row.get("vat_dkk")),
                    row.get("billing_client_uuid", String.class),
                    row.get("work_month_key", String.class),
                    toInteger(row.get("credit_note_for_number")),
                    toInteger(row.get("internal")) != null && toInteger(row.get("internal")) == 1));
        }
        return result;
    }

    /** Intercompany invoice numbers per company — their debtor-ledger items are not customer cash. */
    Map<String, Set<Integer>> queryInternalInvoiceNumbers() {
        Query query = em.createNativeQuery(
                "SELECT companyuuid AS companyuuid, invoicenumber AS invoicenumber FROM invoices " +
                        "WHERE type IN ('INTERNAL', 'INTERNAL_SERVICE') AND invoicenumber IS NOT NULL", Tuple.class);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        Map<String, Set<Integer>> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "queryInternalInvoiceNumbers")) {
            result.computeIfAbsent(row.get("companyuuid", String.class), k -> new HashSet<>())
                    .add(toInteger(row.get("invoicenumber")));
        }
        return result;
    }

    /** Registered billable amount (DKK, external rates) per work month, work dated on or before asOf. */
    Map<String, Double> queryRegisteredByMonth(LocalDate from, LocalDate asOf) {
        Query query = em.createNativeQuery(
                "SELECT CONCAT(year, LPAD(month, 2, '0')) AS month_key, COALESCE(SUM(registered_amount), 0) AS amount " +
                        "FROM fact_user_day WHERE document_date BETWEEN :from AND :asOf " +
                        "GROUP BY CONCAT(year, LPAD(month, 2, '0'))", Tuple.class);
        query.setParameter("from", from);
        query.setParameter("asOf", asOf);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        return toMonthMap(runTupleQuery(query, "queryRegisteredByMonth"), "month_key", "amount");
    }

    /** Contracted budget revenue per month (budgeted hours × rate). */
    Map<String, Double> queryBudgetByMonth(String fromKey, String toKey) {
        Query query = em.createNativeQuery(
                "SELECT month_key AS month_key, COALESCE(SUM(budget_revenue_dkk), 0) AS amount " +
                        "FROM fact_revenue_budget WHERE budget_scenario = 'ORIGINAL' " +
                        "  AND month_key BETWEEN :fromKey AND :toKey GROUP BY month_key", Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        return toMonthMap(runTupleQuery(query, "queryBudgetByMonth"), "month_key", "amount");
    }

    /** Salary facts per month (all companies): contractual salary sum and the lump sums inside it. */
    Map<String, SalaryMonth> querySalaryByMonth(String fromKey, String toKey) {
        Query query = em.createNativeQuery(
                "SELECT month_key AS month_key, COALESCE(SUM(salary_sum), 0) AS salary_sum, " +
                        "  COALESCE(SUM(lump_sums), 0) AS lump_sums " +
                        "FROM fact_salary_monthly WHERE month_key BETWEEN :fromKey AND :toKey GROUP BY month_key", Tuple.class);
        query.setParameter("fromKey", fromKey);
        query.setParameter("toKey", toKey);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        Map<String, SalaryMonth> result = new HashMap<>();
        for (Tuple row : runTupleQuery(query, "querySalaryByMonth")) {
            result.put(row.get("month_key", String.class),
                    new SalaryMonth(toDouble(row.get("salary_sum")), toDouble(row.get("lump_sums"))));
        }
        return result;
    }

    /** Bonus lump sums already scheduled in payroll for months from the as-of month on. */
    Map<String, Double> queryScheduledLumpSums(LocalDate fromMonthStart) {
        Query query = em.createNativeQuery(
                "SELECT DATE_FORMAT(month, '%Y%m') AS month_key, COALESCE(SUM(lump_sum), 0) AS amount " +
                        "FROM salary_lump_sum WHERE month >= :from GROUP BY DATE_FORMAT(month, '%Y%m')", Tuple.class);
        query.setParameter("from", fromMonthStart);
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        return toMonthMap(runTupleQuery(query, "queryScheduledLumpSums"), "month_key", "amount");
    }

    /** The bonus pool for the last completed fiscal year — paid with the October salary run. */
    double queryBonusPool(int fiscalYear) {
        Query query = em.createNativeQuery(
                "SELECT COALESCE(SUM(profit_before_tax * bonus_percent / 100 + COALESCE(extra_pool, 0)), 0) AS pool " +
                        "FROM tw_bonus_pool_config WHERE fiscal_year = :fy", Tuple.class);
        query.setParameter("fy", fiscalYear);
        List<Tuple> rows = runTupleQuery(query, "queryBonusPool");
        return rows.isEmpty() ? 0 : toDouble(rows.get(0).get("pool"));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static Map<String, Double> toMonthMap(List<Tuple> rows, String keyColumn, String valueColumn) {
        Map<String, Double> result = new HashMap<>();
        for (Tuple row : rows) {
            result.put(row.get(keyColumn, String.class), toDouble(row.get(valueColumn)));
        }
        return result;
    }

    private static Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return d;
        if (value instanceof Date d) return d.toLocalDate();
        if (value instanceof java.util.Date d) return new Date(d.getTime()).toLocalDate();
        return LocalDate.parse(value.toString().substring(0, 10));
    }

    @SuppressWarnings("unchecked")
    private List<Tuple> runTupleQuery(Query query, String context) {
        try {
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException pe) {
            log.errorf(pe, "%s failed", context);
            throw pe;
        }
    }
}
