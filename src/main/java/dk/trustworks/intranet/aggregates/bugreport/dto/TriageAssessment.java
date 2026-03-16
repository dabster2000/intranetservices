package dk.trustworks.intranet.aggregates.bugreport.dto;

/**
 * AI triage assessment result indicating whether the reported issue
 * is likely a bug, possibly expected behavior, or needs user guidance.
 */
public enum TriageAssessment {
    LIKELY_BUG,
    POSSIBLY_EXPECTED,
    USER_GUIDANCE_NEEDED
}
