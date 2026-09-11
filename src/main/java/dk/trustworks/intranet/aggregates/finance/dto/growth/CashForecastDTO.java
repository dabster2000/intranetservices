package dk.trustworks.intranet.aggregates.finance.dto.growth;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Direct-method cash forecast for the Growth &amp; Scenarios liquidity panel:
 * the combined bank balance of all three companies rolled forward day by day
 * from {@code asOf} with scheduled inflows and outflows, then aggregated to
 * calendar months. Every driver is measured from production data (see
 * {@link CashForecastMeasuredDTO}); the only assumptions are the dividend
 * amount (defaults to the last twelve months' measured payouts) and how much
 * of the gap between contracted work and the revenue run rate is filled by
 * contract extensions.
 *
 * <p>{@code asOf} may lie in the past — the forecast is then built only from
 * data dated on or before it, and {@code actuals} carries the real month-end
 * and intra-month-low balances that followed, so the model can be backtested.</p>
 *
 * @param asOf                    the date the forecast starts from (balance at close of this day)
 * @param openingBalance          combined bank balance at {@code asOf}, booked entries + Smart Bank drafts, DKK
 * @param months                  forecast months from the month of {@code asOf} (partial) onward
 * @param measured                the measured parameters the forecast is built from
 * @param dividendAnnualDefault   dividend outflow assumed when no amount is given — the trailing
 *                                12 months' measured payouts, DKK
 * @param dividendMonthShares     12 shares (index 0 = January, sum 1) of how last fiscal year's
 *                                dividends were spread over calendar months
 * @param actuals                 real balances for months after {@code asOf}, when the ledger
 *                                already has them (backtest); empty for a forecast from today
 */
public record CashForecastDTO(
        LocalDate asOf,
        double openingBalance,
        List<CashForecastMonthDTO> months,
        CashForecastMeasuredDTO measured,
        double dividendAnnualDefault,
        List<Double> dividendMonthShares,
        List<CashForecastActualMonthDTO> actuals) {

    /**
     * One forecast month. Inflow components are positive, outflow components
     * are positive amounts leaving the bank; {@code netFlow} = inflows − outflows.
     *
     * @param monthKey            YYYYMM
     * @param eomBalance          forecast combined balance at month end, DKK
     * @param minBalance          lowest forecast balance on any day of the month, DKK
     * @param minDate             the day carrying {@code minBalance}
     * @param maxBalance          highest forecast balance on any day of the month, DKK
     * @param receivablesIn       collections of invoices already issued and open at {@code asOf}
     * @param wipIn               collections of work already performed but not yet invoiced
     * @param contractedIn        collections of contracted (budgeted) future work
     * @param extensionIn         collections of the assumed contract extensions filling the gap to run rate
     * @param otherIn             public refunds (sickness, parental leave) and other measured inflows
     * @param payrollOut          net salaries + A-skat/AM-bidrag + pension + holiday pay + ATP
     * @param bonusOut            bonus lump sums (scheduled, or the pool for October)
     * @param vatOut              VAT settlements (monthly for A/S, quarterly for the subsidiaries)
     * @param corporateTaxOut     corporate tax instalments replicated from last year
     * @param supplierOut         supplier payments incl. rent, expense-card top-ups, other outflows
     * @param dividendOut         dividend tranches
     * @param expectedBillingNet  net revenue expected to be invoiced for work in this month, DKK
     */
    public record CashForecastMonthDTO(
            String monthKey,
            double eomBalance,
            double minBalance,
            LocalDate minDate,
            double maxBalance,
            double receivablesIn,
            double wipIn,
            double contractedIn,
            double extensionIn,
            double otherIn,
            double payrollOut,
            double bonusOut,
            double vatOut,
            double corporateTaxOut,
            double supplierOut,
            double dividendOut,
            double netFlow,
            double expectedBillingNet) {
    }

    /**
     * Measured drivers, exposed so the UI can show what the forecast rests on.
     *
     * @param collectionDaysMedian      days from invoice date to full payment (debtor ledger, 12 months)
     * @param lateDaysMedian            days from due date to full payment (negative = early)
     * @param paymentTermsDaysMedian    days from invoice date to due date on issued invoices
     * @param invoicingLagDaysMedian    days from the end of the work month to the invoice date
     * @param billRatio                 invoiced net ÷ registered billable amount over complete months
     * @param budgetRealization         registered billable amount ÷ budgeted amount over complete months
     * @param runRateMonthlyNet         trailing-12-month external net revenue ÷ 12
     * @param vatRateEffective          output VAT ÷ net on issued invoices (≈0.25 minus reverse-charge clients)
     * @param payrollRatios             cash outflow per DKK of salary fact, per kind (SALARY, PAYROLL_TAX, PENSION, VACATION_PAY)
     * @param vatCadenceByCompany       MONTHLY or QUARTERLY per company uuid
     * @param vatNetRatioByCompany      VAT actually paid ÷ output VAT, per company (input VAT, refunds)
     * @param vatOffsetDaysByCompany    measured days from the end of a VAT period to its settlement, per company
     * @param monthlyAverages           trailing average per month per kind for the averaged outflow/inflow kinds
     * @param openReceivablesDkk        open receivables at {@code asOf} (external invoices, incl. VAT)
     * @param doubtfulReceivablesDkk    receivables more than 180 days past due — excluded from the forecast
     * @param unbilledWipDkk            registered but not yet invoiced work (net), at {@code asOf}
     * @param sampleInvoicesPaid        number of paid invoices behind the collection statistics
     */
    public record CashForecastMeasuredDTO(
            Double collectionDaysMedian,
            Double lateDaysMedian,
            Double paymentTermsDaysMedian,
            Double invoicingLagDaysMedian,
            Double billRatio,
            Double budgetRealization,
            double runRateMonthlyNet,
            double vatRateEffective,
            Map<String, Double> payrollRatios,
            Map<String, String> vatCadenceByCompany,
            Map<String, Double> vatNetRatioByCompany,
            Map<String, Integer> vatOffsetDaysByCompany,
            Map<String, Double> monthlyAverages,
            double openReceivablesDkk,
            double doubtfulReceivablesDkk,
            double unbilledWipDkk,
            int sampleInvoicesPaid) {
    }

    /**
     * Real balances of a month after {@code asOf}, from the imported ledger.
     *
     * @param monthKey    YYYYMM
     * @param eomBalance  balance at the last imported day of the month (month end when complete)
     * @param minBalance  lowest balance on any imported day of the month
     * @param throughDate last imported day of the month
     * @param complete    whether the month has ledger lines through its last day
     */
    public record CashForecastActualMonthDTO(
            String monthKey,
            double eomBalance,
            double minBalance,
            LocalDate throughDate,
            boolean complete) {
    }
}
