package dk.trustworks.intranet.aggregates.jkdashboard.dto;

/**
 * Classification of how a JK's registered hours were billed for a given
 * JK + client + project + month combination.
 */
public enum BillingScenario {
    /** No invoice items under JK name, no help colleague, no merge detected */
    NOT_BILLED,
    /** JK invoice items exist and invoiced_hours >= 0.9 x registered_hours */
    FULLY_BILLED,
    /** JK invoice items exist but invoiced_hours < 0.9 x registered_hours */
    PARTIALLY_BILLED,
    /** No JK invoice items, but regular consultant surplus detected on same project */
    POSSIBLY_MERGED,
    /** Help colleague entry, regular consultant surplus detected */
    HELP_MERGED,
    /** Help colleague entry, no merge detected */
    HELP_NOT_BILLED
}
