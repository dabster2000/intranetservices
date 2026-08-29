package dk.trustworks.intranet.aggregates.finance.dto.growth;

/**
 * Measured actuals that seed the client-side growth simulation. Every figure is
 * derived from production data over the trailing 12 complete months (TTM) or the
 * latest complete month — nothing here is assumed or hardcoded.
 *
 * <p>Employee-type semantics follow {@code userstatus.type}: CONSULTANT (billable
 * consultants), STUDENT (junior consultants, hourly-paid), STAFF (non-billable
 * back office). EXTERNAL is excluded everywhere.</p>
 *
 * @param asOfMonthKey            latest complete month (YYYYMM) the baseline is measured at
 * @param consultants             current ACTIVE consultants
 * @param students                current ACTIVE students (junior consultants)
 * @param staff                   current ACTIVE staff
 * @param avgSalaryConsultant     average monthly salary per ACTIVE consultant, DKK
 *                                (fact_salary_monthly.effective_salary)
 * @param avgSalaryStudent        average monthly pay per ACTIVE student, DKK. Students are
 *                                HOURLY: fact_salary_monthly stores their rate/pay in øre,
 *                                so this is salary_sum/100 per person
 * @param avgSalaryStaff          average monthly salary per ACTIVE staff, DKK
 * @param payrollOverheadFactor   GL payroll (cost_type=SALARIES) TTM ÷ salary-fact TTM.
 *                                Captures pension, social costs, bonuses and vacation
 *                                accruals on top of contractual salary; null when the
 *                                salary-fact denominator is empty
 * @param nonPayrollOpexMonthly   average monthly non-payroll OPEX, DKK (TTM)
 * @param glDirectPctOfRevenue    external subcontractor (GL DIRECT_COSTS) cost as a share
 *                                of net revenue, TTM, 0..1
 * @param realizedRateConsultant  duration-weighted realized hourly rate for consultants,
 *                                DKK/h (TTM, rate&gt;0 work rows); null if no billable work
 * @param realizedRateStudent     same for students; null if no billable student work
 * @param billableHoursConsultant billable hours per ACTIVE consultant per month (TTM
 *                                billable hours ÷ average ACTIVE consultants ÷ 12)
 * @param billableHoursStudent    same for students
 * @param ttmRevenue              net revenue over the TTM window, DKK
 * @param ttmTotalCost            OPEX (incl. payroll) + GL direct cost over the TTM
 *                                window, DKK
 */
public record GrowthBaselineDTO(
        String asOfMonthKey,
        long consultants,
        long students,
        long staff,
        Double avgSalaryConsultant,
        Double avgSalaryStudent,
        Double avgSalaryStaff,
        Double payrollOverheadFactor,
        double nonPayrollOpexMonthly,
        double glDirectPctOfRevenue,
        Double realizedRateConsultant,
        Double realizedRateStudent,
        Double billableHoursConsultant,
        Double billableHoursStudent,
        double ttmRevenue,
        double ttmTotalCost) {
}
