package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewDecision;

import java.time.LocalDateTime;

/**
 * The server-derived sub-status of one board card (pipeline sub-status
 * feature): where inside its stage the application actually stands. Fully
 * computed from state that already exists — interviews, scorecards, Method B
 * scheduling requests, the offer dossier — so the ladder advances as a side
 * effect of work people already do, never by hand. {@code null} on cards in
 * stages with no ladder (SCREENING, HIRED).
 *
 * <p>Interview-stage ladder: {@code BOOK} → {@code BOOKING} (Method B
 * automation in flight) → {@code AWAITING} → {@code VOTERING} →
 * {@code DECIDE} → {@code INFORM} (a pending decision is recorded, the
 * candidate has not been told yet). OFFER ladder: {@code TEAM_MISSING} →
 * {@code CONTRACT_NOT_SENT} → {@code AWAITING_SIGNATURE} → {@code SIGNED}.
 *
 * @param interviewUuid        the round's driving interview; interview-stage
 *                             codes from {@code AWAITING} on, else null
 * @param interviewScheduledAt wall-clock Europe/Copenhagen, as stored on the
 *                             interview — upcoming for {@code AWAITING},
 *                             held date for {@code VOTERING}/{@code DECIDE}/
 *                             {@code INFORM}
 * @param interviewLocation    PII-free room name or "Teams"
 * @param scorecardsSubmitted  {@code VOTERING}/{@code DECIDE} progress
 *                             counter (same semantics as the debrief's:
 *                             all submitted scorecards, kept ones included)
 * @param scorecardsExpected   currently assigned interviewer count
 * @param decidedOutcome       the pending decision behind {@code INFORM} —
 *                             ADVANCE is served to ordinary decision holders;
 *                             REJECT additionally requires final-outcome
 *                             rights. Otherwise null, so the chip stays
 *                             outcome-neutral without disclosing NO-GO.
 */
public record BoardCardSubStatus(
        Code code,
        String interviewUuid,
        LocalDateTime interviewScheduledAt,
        String interviewLocation,
        Integer scorecardsSubmitted,
        Integer scorecardsExpected,
        RecruitmentInterviewDecision decidedOutcome
) {

    /** The ladder positions — wire values, persisted nowhere. */
    public enum Code {
        BOOK,
        BOOKING,
        AWAITING,
        VOTERING,
        DECIDE,
        INFORM,
        TEAM_MISSING,
        CONTRACT_NOT_SENT,
        AWAITING_SIGNATURE,
        SIGNED
    }

    /** A code-only sub-status (the BOOK/BOOKING and OFFER-ladder shapes). */
    public static BoardCardSubStatus of(Code code) {
        return new BoardCardSubStatus(code, null, null, null, null, null, null);
    }
}
