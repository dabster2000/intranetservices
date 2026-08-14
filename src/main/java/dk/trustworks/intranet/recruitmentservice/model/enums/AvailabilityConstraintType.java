package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * What one normalized availability interval MEANS to the engine
 * (spec §12.2/§12.3): BUSY subtracts, AVAILABLE_ONLY restricts its
 * covered days, PREFERRED/AVOID only rank. Positive claims never
 * override an O365 busy interval — busy always wins.
 */
public enum AvailabilityConstraintType {
    /** A hard external busy interval — subtracted like O365 busy. */
    BUSY,
    /** "I am ONLY available …" — on the days the evidence covers, slots
     * must fall inside these intervals. */
    AVAILABLE_ONLY,
    /** Ranking bonus — never a hard exclusion (spec §12.3). */
    PREFERRED,
    /** Ranking penalty — never a hard exclusion. */
    AVOID
}
