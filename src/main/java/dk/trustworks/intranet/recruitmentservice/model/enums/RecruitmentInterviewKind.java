package dk.trustworks.intranet.recruitmentservice.model.enums;

/**
 * Interview kind (ATS spec §4.1): a {@code ROUND} counts toward the stage
 * machine (round 1–3 ↔ stage {@code INTERVIEW_n}); {@code INFORMAL} is
 * Airtable's <em>uformel snak</em> — schedulable at any point before or
 * between rounds without advancing the stage, and no scorecard is allowed
 * (a plain note suffices, spec §5.3). {@code OFFER} is the meeting held in
 * the offer phase (the contract/offer conversation, or a last talk with a
 * partner): like {@code INFORMAL} it is schedulable at any point while the
 * application is in play, never advances the stage and takes no scorecard —
 * it exists so the offer phase gets a real, invitable calendar entry
 * instead of being mislabelled as an extra round.
 * <p>
 * Values are persisted as strings ({@code @Enumerated(EnumType.STRING)})
 * and CHECK-pinned in SQL — append new kinds, never rename or reorder.
 */
public enum RecruitmentInterviewKind {
    INFORMAL,
    ROUND,
    OFFER;

    /**
     * Whether assigned interviewers submit a blind scorecard for this kind
     * — the single source of truth behind {@code scorecardRequired} on the
     * wire, the debrief query, the SLA nudges and the Slack scorecard
     * buttons. Only {@code ROUND} is evaluated; an informal chat and an
     * offer meeting take a plain note on the candidate instead (spec §5.3).
     */
    public boolean takesScorecard() {
        return this == ROUND;
    }

    /**
     * Whether this kind carries a round number. Only {@code ROUND} does;
     * every other kind stores {@code round IS NULL} (CHECK-pinned).
     */
    public boolean hasRound() {
        return this == ROUND;
    }
}
