package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * Value object representing a single OPEX row in the distribution-aware data pipeline.
 *
 * Matches the grain of fact_opex_mat: company × cost_center × expense_category × month.
 *
 * The {@code dataSource} field distinguishes raw GL rows (from previous fiscal years,
 * queried from fact_opex_mat) from distribution-computed rows (for the current fiscal year,
 * produced by IntercompanyCalcService).
 *
 * Immutable by design — use records for value objects with no identity.
 */
public record OpexRow(
        /** Company UUID (fact_opex_mat.company_id). */
        String companyId,

        /** Cost center identifier (e.g., HR_ADMIN, SALES, FACILITIES). */
        String costCenterId,

        /** Expense category identifier (e.g., PEOPLE_NON_BILLABLE, OFFICE_FACILITIES). */
        String expenseCategoryId,

        /** Month key in YYYYMM format (e.g., "202601"). */
        String monthKey,

        /** OPEX amount in DKK. Signed — net-negative monthly aggregates (refunds, reversals)
         *  propagate as negative cost on the origin company. */
        double opexAmountDkk,

        /** Number of GL entries contributing to this row (from fact_opex_mat or estimated as 1 for distribution). */
        int invoiceCount,

        /** True if this row belongs to a payroll/salary account. */
        boolean isPayrollFlag,

        /**
         * Data provenance indicator.
         * "ERP_GL"       — raw GL from fact_opex_mat (previous fiscal year).
         * "DISTRIBUTION" — distribution algorithm output (current fiscal year).
         */
        String dataSource
) {
    /** Convenience constant for raw GL data source. */
    public static final String SOURCE_ERP_GL = "ERP_GL";

    /** Convenience constant for distribution-computed data source. */
    public static final String SOURCE_DISTRIBUTION = "DISTRIBUTION";

    /** Cost-type value persisted to fact_opex_*.cost_type for payroll rows. */
    public static final String COST_TYPE_SALARIES = "SALARIES";

    /** Cost-type value persisted to fact_opex_*.cost_type for non-payroll OPEX rows. */
    public static final String COST_TYPE_OPEX = "OPEX";

    /**
     * Compact constructor validates non-null invariants. {@code opexAmountDkk}
     * is signed — negative values represent net-negative monthly aggregates
     * (refunds, reversals) on non-shared/non-salary accounts.
     */
    public OpexRow {
        if (companyId == null || companyId.isBlank()) {
            throw new IllegalArgumentException("companyId must not be blank");
        }
        if (costCenterId == null || costCenterId.isBlank()) {
            throw new IllegalArgumentException("costCenterId must not be blank");
        }
        if (expenseCategoryId == null || expenseCategoryId.isBlank()) {
            throw new IllegalArgumentException("expenseCategoryId must not be blank");
        }
        if (monthKey == null || monthKey.length() != 6) {
            throw new IllegalArgumentException("monthKey must be a 6-character YYYYMM string, got: " + monthKey);
        }
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("dataSource must not be blank");
        }
    }
}
