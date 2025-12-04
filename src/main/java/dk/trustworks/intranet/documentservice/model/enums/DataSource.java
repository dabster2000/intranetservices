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
    SYSTEM_DATE
}
