package dk.trustworks.intranet.agreementservice.model.enums;

/**
 * Lifecycle of one AI-backfill corpus walk (template-clauses spec §4.8).
 * Runs are single-flight; a RUNNING row surviving a process restart is
 * reconciled to FAILED the next time the console asks for status.
 */
public enum BackfillRunStatus {
    RUNNING,
    COMPLETED,
    FAILED
}
