package dk.trustworks.intranet.recruitmentservice.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * The Interview Room read model (room spec 2026-08-26 §6.2) — everything
 * the room needs in one round trip: the interview, the viewer-scoped
 * candidate, the guidance catalogue for the position's template, prior
 * unlocked rounds, the fact ledger with gaps, the caller's own draft and
 * the evidence-shelf manifest. Assembled by
 * {@code RecruitmentInterviewRoomService}; the boundary between the full
 * and the restricted shelf is decided SERVER-side ({@code restricted}) —
 * the room never guesses at a rule the backend has already ruled on
 * (spec §7.3).
 *
 * @param interview        the interview being run
 * @param candidate        viewer-scoped: full-profile viewers get contact
 *                         columns, restricted interviewers name + LinkedIn
 *                         only (the brief boundary)
 * @param restricted       the viewer holds only the interviewer grant —
 *                         the shelf is brief-scoped and compensation and
 *                         competition facts are absent
 * @param usageNote        the framework's usage note (catalogue)
 * @param subjects         guidance per scorecard-template code, template order
 * @param priorRounds      earlier ROUND interviews with blind-filtered
 *                         scorecards (unlocked content only)
 * @param facts            the fact ledger, viewer-scoped; null for
 *                         restricted viewers (their gaps still arrive)
 * @param gapFields        vocabulary keys still UNKNOWN/ASKED/STALE that
 *                         this viewer's prep lane should surface (§5.1 —
 *                         restricted lanes name timing/practicalities and
 *                         never compensation)
 * @param draft            the caller's own draft, null before first save
 * @param shelf            the evidence-shelf manifest
 * @param scorecardSubmitted the caller already landed this interview
 * @param aiFlags          which room AI capabilities are on (spec §9)
 */
public record InterviewRoomResponse(
        RoomInterview interview,
        RoomCandidate candidate,
        boolean restricted,
        String usageNote,
        List<RoomSubject> subjects,
        List<RoomPriorRound> priorRounds,
        FactsLedgerResponse facts,
        List<String> gapFields,
        RoomDraft draft,
        RoomShelf shelf,
        boolean scorecardSubmitted,
        RoomAiFlags aiFlags
) {

    /** The interview, with names resolved and wall-clock scheduling facts. */
    public record RoomInterview(String uuid, String kind, Integer round,
                                String scheduledAt, int durationMinutes,
                                String location, String joinUrl, String status,
                                List<InterviewResponse.InterviewerInfo> interviewers,
                                String applicationUuid, String positionUuid,
                                String positionTitle) {
    }

    /** Viewer-scoped candidate summary. Restricted: name + LinkedIn only. */
    public record RoomCandidate(String uuid, String fullName, String email,
                                String linkedinUrl, String targetStartDate) {
    }

    /** One scorecard subject with its full guidance (probes + anchors). */
    public record RoomSubject(String code, String label, String shortHint,
                              String whatYouAreScoring, List<String> probes,
                              List<String> anchors) {
    }

    /** One earlier ROUND with its blind-filtered scorecards for this viewer. */
    public record RoomPriorRound(String interviewUuid, Integer round, String scheduledAt,
                                 InterviewScorecardsResponse scorecards) {
    }

    /**
     * The caller's own draft. {@code lines} is the stored INoteLine[]
     * returned verbatim as JSON — the array is the contract (§3.3).
     */
    public record RoomDraft(JsonNode lines, long clientRevision, String updatedAt) {
    }

    /** The evidence-shelf manifest — documents and form answers, viewer-scoped. */
    /**
     * @param employment the workplaces the AI brief read out of the CV
     *                   (brief-v2), newest first — the shelf's answer to
     *                   "where has this person worked?" without opening the
     *                   PDF. Empty when the brief flag is off or no brief
     *                   exists for this interview's application.
     */
    public record RoomShelf(List<CandidateDocument> documents, List<FormAnswer> answers,
                            List<CandidateAiStateResponse.AiEmployment> employment) {
    }

    /** Which AI capabilities the room may offer (all default off, spec §9). */
    public record RoomAiFlags(boolean prep, boolean extraction, boolean tidy,
                              boolean alignment) {
    }
}
