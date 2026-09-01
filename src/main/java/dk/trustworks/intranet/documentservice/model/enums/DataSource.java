package dk.trustworks.intranet.documentservice.model.enums;

/**
 * Data source for template placeholders.
 * Indicates where the placeholder value should come from.
 */
public enum DataSource {
    /** No automatic data source - manual input required */
    MANUAL,

    /** No automatic data source - manual input required (alias) */
    NONE,

    /** User/consultant information */
    USER,

    /** Client/customer information */
    CLIENT,

    /** Project details */
    PROJECT,

    /** Contract information */
    CONTRACT,

    /** Company information */
    COMPANY,

    /** Current date/time */
    SYSTEM_DATE,

    /**
     * Interview-fact suggestion (dossier flow only): the field offers a
     * click-to-apply value from the candidate's hiring-fact ledger
     * ({@code recruitment_candidate_fact_state}), never an auto-insert.
     * {@code source_field} names the fact key (e.g. SALARY_EXPECTATION).
     */
    INTERVIEW_FACT
}
