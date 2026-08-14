package dk.trustworks.intranet.competenceservice.dto;

import dk.trustworks.intranet.competenceservice.domain.CompetenceStatus;

import java.time.LocalDateTime;

/**
 * One requirement as it looks to the employee who owes it (spec §6.1,
 * {@code GET /me/requirements}).
 *
 * <p>This is the whole "Mine kompetencekrav" page in one row per krav, and it is deliberately
 * a fat DTO. Everything the card renders — both track statuses, the version labels, the
 * renewal date, whether the test is unlocked, what to click — is derived once on the server
 * from the same rules the matrix uses. The alternative, sending raw statuses and letting the
 * page work out the next action, produces two implementations of §5.3–§5.6 that agree in
 * development and disagree in production, and the one the employee sees would be the one
 * nobody tested.
 *
 * <p>Only requirements visible to the caller appear: active, both tracks published, and the
 * caller in the audience (§5.1). A half-published krav is a content owner's debt, not an
 * employee's obligation, so it is absent here and shows as "Not published yet" in the matrix.
 *
 * <p>There is no useruuid anywhere in the request that produces this — the subject is
 * {@code RequestHeaderHolder.getUserUuid()} (§10.1).
 *
 * <p>Keys are always present (no {@code @JsonInclude(NON_NULL)}): {@code dueAt} and
 * {@code openAttemptUuid} are meaningfully null, and the card branches on exactly that.
 *
 * @param uuid                the requirement — what the course and attempt endpoints address
 * @param compId              slug, used for routing
 * @param kref                the SKI reference, e.g. {@code 7.b.1} — the label an auditor uses
 * @param name                display name
 * @param description         one-paragraph summary shown on the card
 * @param courseStatus        microcourse track (§5.3)
 * @param testStatus          test track (§5.4)
 * @param cellStatus          the two tracks collapsed to one verdict (§5.5), so the employee's
 *                            card and the leader's matrix cell can never disagree about colour
 * @param activeCourseVersion the ACTIVE course version label — shown because "you read v1.0,
 *                            v1.1 is live" is the whole explanation for a RETAKE_REQUIRED card
 * @param activeTestVersion   the ACTIVE test version label
 * @param lastCompletedAt     when this person last completed the microcourse, any version
 * @param lastPassedAt        when this person last passed the test, any version
 * @param cadenceDays         the effective cadence — the per-requirement override when set,
 *                            otherwise {@code competence.cadence-days}
 * @param dueAt               when this krav next falls due, or {@code null} when it cannot
 *                            fall due yet (a track never completed/passed, so the card is
 *                            already telling the person to act). Derived here rather than in
 *                            the browser so "falls due in N days" is a subtraction rather than
 *                            a re-implementation of the cadence rule — see {@link #dueAt}.
 * @param courseGateOpen      whether the test is unlocked right now (§5.6). Re-evaluated
 *                            server-side when an attempt is started, so this is presentation:
 *                            hiding the button is convenience, refusing the POST is control.
 * @param openAttemptUuid     an in-progress attempt to resume, or {@code null}. Present so the
 *                            page can offer "resume" instead of letting the employee press
 *                            start and collect a {@code 409}.
 * @param nextAction          the single thing to do next — see {@link NextAction}
 */
public record MyRequirementDTO(String uuid,
                               String compId,
                               String kref,
                               String name,
                               String description,
                               CompetenceStatus.Course courseStatus,
                               CompetenceStatus.Test testStatus,
                               CompetenceStatus.Cell cellStatus,
                               String activeCourseVersion,
                               String activeTestVersion,
                               LocalDateTime lastCompletedAt,
                               LocalDateTime lastPassedAt,
                               int cadenceDays,
                               LocalDateTime dueAt,
                               boolean courseGateOpen,
                               String openAttemptUuid,
                               NextAction nextAction) {

    /**
     * The one thing this card asks the employee to do.
     *
     * <p>Serialises as the Java constant name, which the frontend contract depends on.
     */
    public enum NextAction {
        /** The microcourse is unread, superseded or out of cadence — read it first. */
        READ_COURSE,
        /** The gate is open and the test is unpassed, superseded or out of cadence. */
        TAKE_TEST,
        /** An attempt is already open; finishing it is the only way forward. */
        RESUME_ATTEMPT,
        /** Passed, undecided. Nothing for the employee to do — it is a leader's turn. */
        AWAITING_APPROVAL,
        /** Green, or nothing meaningful to offer. */
        NOTHING;

        /**
         * Derives the next action from the two track statuses.
         *
         * <p>Order matters and is not the same as urgency order.
         *
         * <ol>
         *   <li><strong>An open attempt wins over everything.</strong> The gate is checked
         *       when an attempt starts, not while it runs, so a microcourse that fell due
         *       mid-test does not strand a candidate half way through a submittable attempt.
         *       Telling them to go and read the course instead would abandon the attempt to
         *       the reaper and lose their answers.</li>
         *   <li><strong>Then the gate.</strong> While the microcourse is not green there is
         *       exactly one move, and offering the test as well would only produce a
         *       {@code 409} the employee cannot act on.</li>
         *   <li><strong>Then the test.</strong> {@code AWAITING_APPROVAL} is deliberately its
         *       own answer rather than {@code NOTHING}: "you are done, someone else is not"
         *       is a different message from "you are done", and the difference is what stops
         *       people re-sitting a test they already passed.</li>
         * </ol>
         *
         * <p>{@code NOT_PUBLISHED} on either track cannot reach a learner — visibility
         * requires both tracks published (§5.1) — but it maps to {@code NOTHING} rather than
         * to a course prompt, so a half-published krav that somehow surfaced never tells an
         * employee to open content that does not exist.
         *
         * @param openAttemptUuid the in-progress attempt, or {@code null}
         * @param courseGateOpen  from {@code CompetenceStatusService.courseGateOpen}, not
         *                        re-derived here — one definition of the gate, not two
         */
        public static NextAction derive(CompetenceStatus.Course courseStatus,
                                        CompetenceStatus.Test testStatus,
                                        boolean courseGateOpen,
                                        String openAttemptUuid) {
            if (openAttemptUuid != null && !openAttemptUuid.isBlank()) {
                return RESUME_ATTEMPT;
            }
            if (courseStatus == CompetenceStatus.Course.NOT_PUBLISHED
                    || testStatus == CompetenceStatus.Test.NOT_PUBLISHED) {
                return NOTHING;
            }
            if (!courseGateOpen) {
                return READ_COURSE;
            }
            return switch (testStatus) {
                case APPROVED -> NOTHING;
                case AWAITING_APPROVAL -> AWAITING_APPROVAL;
                // NOT_PASSED, RETAKE_REQUIRED, OVERDUE — the gate is open, so sit the test.
                default -> TAKE_TEST;
            };
        }
    }

    /**
     * When this requirement next falls due, or {@code null}.
     *
     * <p>Cadence is evaluated at read time against the completion and submission timestamps
     * (§5.8), so a due date is not stored anywhere — it is this addition. Doing it here rather
     * than in the browser means "falls due in 34 days" is one subtraction on a date the server
     * computed, instead of the frontend re-implementing the cadence rule and drifting the
     * first time a per-requirement override lands.
     *
     * <p>The earlier of the two clocks wins: both tracks renew on the same cadence and the
     * krav is out of compliance as soon as <em>either</em> is, so the honest answer is the one
     * that expires first.
     *
     * <p>Null when either track has never been completed or passed. There is nothing to fall
     * due — the card is already red for a reason the employee can act on, and a due date next
     * to "not started" would read as a deadline extension.
     *
     * @param cadenceDays the effective cadence; a non-positive value yields {@code null}
     *                    rather than a date in the past, which would silently mark everybody
     *                    overdue if a setting were ever mis-seeded
     */
    public static LocalDateTime dueAt(LocalDateTime lastCompletedAt,
                                      LocalDateTime lastPassedAt,
                                      int cadenceDays) {
        if (lastCompletedAt == null || lastPassedAt == null || cadenceDays <= 0) {
            return null;
        }
        LocalDateTime anchor = lastCompletedAt.isBefore(lastPassedAt) ? lastCompletedAt : lastPassedAt;
        return anchor.plusDays(cadenceDays);
    }
}
