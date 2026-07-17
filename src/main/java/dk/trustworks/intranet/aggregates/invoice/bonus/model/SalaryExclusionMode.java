package dk.trustworks.intranet.aggregates.invoice.bonus.model;

/**
 * Admin override applied to the pool-basis "excluded salaries" set (per leader, per fiscal year).
 *
 * <ul>
 *   <li>{@link #EXCLUDE_SALARY} — force a user INTO the excluded-salary group (their salary is
 *       subtracted from group costs even if not derived automatically).</li>
 *   <li>{@link #INCLUDE_SALARY} — remove a derived user FROM the excluded-salary group (their
 *       salary counts as a normal group cost).</li>
 * </ul>
 */
public enum SalaryExclusionMode {
    EXCLUDE_SALARY,
    INCLUDE_SALARY
}
