package dk.trustworks.intranet.aggregates.finance.dto;

/**
 * REST-exposed shape of a salary GL anomaly cell — used by the executive
 * dashboard's pending-data banner to show which (company × month) cells
 * are awaiting bookkeeping in e-conomic GL.
 *
 * <p>Built from {@code SalaryGLAnomalyCheck.Anomaly} in the resource layer.
 * {@code companyName} is resolved server-side so the frontend can render
 * "Trustworks Tech" instead of the bare UUID.
 *
 * @param companyUuid      the company whose GL is under-posted for that month
 * @param companyName      display name of the company, resolved via Company.uuid lookup
 * @param year             calendar year of the anomaly cell
 * @param month            calendar month of the anomaly cell (1-12)
 * @param glSalary         actual BOOKED GL salary total summed from finance_details
 *                         on accounts where cost_type='SALARIES'. Booked only — an
 *                         unposted draft journal does not count towards it.
 * @param draftSalary      GL salary sitting in an UNPOSTED draft journal for the cell.
 *                         Non-zero means the entries exist and only need posting.
 * @param gapKind          {@code DRAFTED_NOT_POSTED} when the draft would have closed
 *                         the gap, otherwise {@code ABSENT}. The two need different
 *                         human actions, so the banner should not merge them.
 * @param intendedSalary   intended salary total summed from fact_salary_monthly
 *                         (what the payroll system says should have been posted)
 * @param gapDkk           intendedSalary − glSalary (always ≥ 0 for anomalies)
 * @param coveragePct      glSalary / intendedSalary, in the closed interval [0, 1]
 *                         (cells below the configured threshold fire as anomalies)
 */
public record SalaryGLAnomalyDTO(
        String companyUuid,
        String companyName,
        int year,
        int month,
        double glSalary,
        double draftSalary,
        String gapKind,
        double intendedSalary,
        double gapDkk,
        double coveragePct
) {}
