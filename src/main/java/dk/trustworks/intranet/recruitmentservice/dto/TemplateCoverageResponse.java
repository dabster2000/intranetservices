package dk.trustworks.intranet.recruitmentservice.dto;

import java.util.List;

/**
 * Envelope of {@code GET /recruitment/email-templates/coverage}: every
 * curated pipeline moment, whether a letter actually answers it, and how
 * loud that moment has been lately. Computed by
 * {@code RecruitmentCommsCoverageService} over the very chain the candidate
 * mailer resolves, so the screen cannot report a moment as covered that the
 * runtime would leave silent.
 *
 * <p><b>Keys and state only.</b> Every human word — the moment's name, the
 * reason phrasing, the pool bucket wording — lives in the frontend's
 * {@code templateOptions.ts}, which already owns that whole vocabulary. A
 * second copy in Java is how the two start disagreeing.</p>
 */
public record TemplateCoverageResponse(List<MomentCoverage> moments) {

    /**
     * One pipeline moment and the letter (if any) that answers it.
     *
     * @param triggerKey    the moment's own key — the most specific rung of
     *                      its chain, e.g. {@code STAGE_OFFER} or
     *                      {@code REJECTION_EXPERIENCE_LEVEL_SCREENING}
     * @param group         APPLICATION | STAGE | REJECTION | POOL | GDPR
     * @param outcome       COVERED (a letter of its own, switched on) |
     *                      FALLS_BACK (a less specific rung answers it) |
     *                      INACTIVE (the letter that would answer is
     *                      switched off, so nothing is sent) | NONE
     * @param templateUuid  the answering letter; null when nothing does
     * @param templateKey   that letter's identity key
     * @param templateName  that letter's display name
     * @param autoSend      whether it sends straight out or queues for
     *                      review; null when nothing answers
     * @param fallsBackTo   the rung that actually answers (or, when nothing
     *                      is sendable, would answer if it were switched on)
     *                      when it is not this moment's own key; null
     *                      otherwise
     * @param occurredCount how often the moment fired over the rollup
     *                      window; 0 until the nightly job has run
     * @param emailedCount  how often a letter went out for it; same
     * @param queuedCount   review-queue rows for the answering letter, all
     *                      statuses — counted live, not from the rollup
     * @param dismissedCount those of them a recruiter threw away
     * @param approvedCount  those of them a recruiter sent
     * @param specifics     the reason-coded / bucketed children of this
     *                      moment; empty for a moment that has none
     */
    public record MomentCoverage(
            String triggerKey,
            String group,
            String outcome,
            String templateUuid,
            String templateKey,
            String templateName,
            Boolean autoSend,
            String fallsBackTo,
            int occurredCount,
            int emailedCount,
            int queuedCount,
            int dismissedCount,
            int approvedCount,
            List<MomentCoverage> specifics
    ) {
    }
}
