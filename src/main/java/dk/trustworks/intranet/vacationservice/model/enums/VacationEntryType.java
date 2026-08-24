package dk.trustworks.intranet.vacationservice.model.enums;

/**
 * Ledger entry kinds. Consumption is never a ledger entry — it is derived
 * live from {@code work} rows on the VACATION task, so the three buckets
 * (afholdt / afventer lønkørsel / planlagt) always agree with the timesheet.
 *
 * <p>IMPORT_BASELINE_* pairs snapshot a Danløn feriepengeforpligtelse upload
 * as of the batch's as-of date and supersede every other entry (and every
 * payroll-stamped registration) at or before that date for the (user,
 * ferieår) pairs the file carried.</p>
 */
public enum VacationEntryType {
    ACCRUAL,
    IMPORT_BASELINE_EARNED,
    IMPORT_BASELINE_USED,
    TRANSFER_IN,
    TRANSFER_OUT,
    PAYOUT,
    ADJUSTMENT
}
