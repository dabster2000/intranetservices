package dk.trustworks.intranet.aggregates.bugreport.dto;

/**
 * AI triage assessment result indicating whether the reported issue
 * is likely a bug, possibly expected behavior, or uncertain.
 */
public enum TriageAssessment {
    LIKELY_BUG,
    POSSIBLY_EXPECTED,
    UNCERTAIN
}
