package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageJob.CountedEvent;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageJob.RollupContext;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.Counts;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageJob.WINDOW_DAYS;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageJob.count;
import static dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageJob.windowStart;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The nightly rollup's counting core (fast tier, no DB): which moments one
 * pipeline event makes true, which moment a sent letter belonged to, and
 * where the window ends.
 * <p>
 * The rule the Journey's numbers rest on is that a moment is counted from
 * the event that would fire it — the same event, and the same chain, the
 * candidate mailer resolves. So this file names no key suffix of its own:
 * every expected key comes from
 * {@link RecruitmentEmailService#rejectionKeyChain} /
 * {@link RecruitmentEmailService#pooledKeyChain}, and a change to either
 * chain lands here without an edit.
 */
class RecruitmentCommsCoverageJobTest {

    /** The window's upper edge in every case below. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.SEPTEMBER, 2, 3, 50);

    /** Comfortably inside the window. */
    private static final LocalDateTime RECENTLY = NOW.minusDays(3);

    /** "Too junior, and we found out at screening" — a reason-coded rejection. */
    private static final List<String> EXPERIENCE_AT_SCREENING =
            RecruitmentEmailService.rejectionKeyChain(
                    RecruitmentRejectionReason.EXPERIENCE_LEVEL.name(),
                    RecruitmentStage.SCREENING.name());

    private static final List<String> SILVER_MEDALIST =
            RecruitmentEmailService.pooledKeyChain(CandidatePoolStatus.SILVER_MEDALIST.name());

    private static final String STAGE_INTERVIEW_1 =
            RecruitmentEmailService.STAGE_KEY_PREFIX + RecruitmentStage.INTERVIEW_1.name();

    // ---- Occurrences ------------------------------------------------------

    @Test
    void anApplicationFromThePublicForm_countsTheAcknowledgement() {
        Map<String, Counts> counts = countOf(applicationCreated("public_form"));

        assertEquals(1, counts.get(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT).occurred());
    }

    @Test
    void anApplicationARecruiterAttachedByHand_countsNothing() {
        // Not an application receipt: nobody applied, so there is nothing to
        // acknowledge and the moment did not happen.
        Map<String, Counts> counts = countOf(applicationCreated("airtable"));

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    @Test
    void aForwardStageMove_countsThatStagesMoment() {
        Map<String, Counts> counts = countOf(stageChanged("FORWARD", RecruitmentStage.INTERVIEW_1));

        assertEquals(1, counts.get(STAGE_INTERVIEW_1).occurred());
    }

    @Test
    void aBackMove_countsNothing() {
        // A back-move never mails the candidate, so it is not a moment.
        Map<String, Counts> counts = countOf(stageChanged("BACK", RecruitmentStage.SCREENING));

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    @Test
    void oneRejection_countsBothTheGenericAndTheReasonCodedRung() {
        // The two-key case, and it is deliberate: the Journey shows
        // "Rejected" and, nested under it, "Rejected — too junior, at
        // screening". The same rejection is what both rows are about.
        Map<String, Counts> counts = countOf(rejected(
                RecruitmentRejectionReason.EXPERIENCE_LEVEL.name(),
                RecruitmentStage.SCREENING.name()));

        String specific = EXPERIENCE_AT_SCREENING.getFirst();
        String generic = EXPERIENCE_AT_SCREENING.getLast();
        assertEquals(1, counts.get(specific).occurred());
        assertEquals(1, counts.get(generic).occurred());
        // The chain's middle rung (reason alone, either bucket) routes but
        // has no row on the Journey, so nothing counts it.
        assertEquals(2, counts.size(), () -> "expected exactly two rungs, got " + counts.keySet());
    }

    @Test
    void aRejectionCarryingNoReason_countsTheGenericRungAlone() {
        Map<String, Counts> counts = countOf(rejected(null, RecruitmentStage.INTERVIEW_2.name()));

        assertEquals(1, counts.get(RecruitmentEmailService.KEY_REJECTION_POST_INTERVIEW).occurred());
        assertEquals(1, counts.size(), () -> "expected one rung, got " + counts.keySet());
    }

    @Test
    void enteringTheTalentPool_countsBothThePoolAndItsBucket() {
        Map<String, Counts> counts =
                countOf(pooled(true, CandidatePoolStatus.SILVER_MEDALIST.name()));

        assertEquals(1, counts.get(SILVER_MEDALIST.getFirst()).occurred());
        assertEquals(1, counts.get(RecruitmentEmailService.KEY_POOLED).occurred());
    }

    @Test
    void reBucketingSomeoneAlreadyPooled_countsNothing() {
        Map<String, Counts> counts =
                countOf(pooled(false, CandidatePoolStatus.CONTACTED.name()));

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    @Test
    void theUnsolicitedAndDuplicateReceipts_countTheirOwnMoments() {
        Map<String, Counts> counts = countOf(
                new CountedEvent(RecruitmentEventType.UNSOLICITED_APPLICATION_RECEIVED,
                        RECENTLY, Map.of()),
                new CountedEvent(RecruitmentEventType.DUPLICATE_APPLICATION_RECEIVED,
                        RECENTLY, Map.of()));

        assertEquals(1,
                counts.get(RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT).occurred());
        assertEquals(1,
                counts.get(RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE).occurred());
    }

    @Test
    void anEventTypeNothingMails_countsNothing() {
        Map<String, Counts> counts = countOf(new CountedEvent(
                RecruitmentEventType.INTERVIEW_SCHEDULED, RECENTLY, Map.of()));

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    // ---- The window -------------------------------------------------------

    @Test
    void theWindowStartsExactlyNinetyDaysBack() {
        assertEquals(NOW.minusDays(WINDOW_DAYS), windowStart(NOW));
    }

    @Test
    void anEventOnTheWindowsEdge_stillCounts() {
        // The edge is inclusive on both sides of the boundary — the loader's
        // SQL narrows with the same >=, so a row the query returns is a row
        // the core must count.
        Map<String, Counts> counts =
                countOf(applicationCreatedAt("public_form", windowStart(NOW)));

        assertEquals(1, counts.get(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT).occurred());
    }

    @Test
    void anEventOneMillisecondOlderThanTheWindow_doesNot() {
        // A millisecond, not a day: occurred_at is DATETIME(3), so that is
        // the smallest step the boundary can actually be tested at.
        Map<String, Counts> counts = countOf(applicationCreatedAt(
                "public_form", windowStart(NOW).minusNanos(1_000_000L)));

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    // ---- Letters sent -----------------------------------------------------

    @Test
    void aSentLetter_countsUnderTheMomentItAnswers_notItsOwnKey() {
        // The trigger/identity split, seen from the rollup: TA pointed the
        // letter whose key still says POOLED_NOT_NOW at the offer moment, so
        // the send counts against the offer.
        String offer = RecruitmentEmailService.STAGE_KEY_PREFIX + RecruitmentStage.OFFER.name();
        Map<String, Counts> counts = count(
                new RollupContext(List.of(emailSent("POOLED_NOT_NOW")),
                        Map.of("POOLED_NOT_NOW", offer)::get),
                NOW);

        assertEquals(1, counts.get(offer).emailed());
        assertNull(counts.get("POOLED_NOT_NOW"));
    }

    @Test
    void aLetterAnsweringNoMoment_isCountedNowhere() {
        // A manual-send letter — no trigger assignment, and a key that is not
        // itself a reserved trigger. It reached a candidate, but not as an
        // answer to any moment the Journey has a row for.
        Map<String, Counts> counts = count(
                new RollupContext(List.of(emailSent("FOLLOW_UP_NUDGE")),
                        Map.<String, String>of()::get),
                NOW);

        assertTrue(counts.isEmpty(), () -> "expected no moments, got " + counts.keySet());
    }

    @Test
    void occurrencesAndSendsMeetOnTheSameKey() {
        // The pair the Journey renders side by side: the moment fired twice,
        // and one letter went out.
        Map<String, Counts> counts = count(
                new RollupContext(List.of(
                        applicationCreated("public_form"),
                        applicationCreated("public_form"),
                        emailSent(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)),
                        Map.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT,
                                RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)::get),
                NOW);

        Counts acknowledgement = counts.get(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT);
        assertEquals(2, acknowledgement.occurred());
        assertEquals(1, acknowledgement.emailed());
    }

    // ---- Re-running -------------------------------------------------------

    @Test
    void runningTheRollupAgainOverTheSameWindow_producesTheSameNumbers() {
        // The job recounts the whole window every night, so the second run
        // must land on the first run's numbers rather than on twice them.
        RollupContext ctx = new RollupContext(List.of(
                applicationCreated("public_form"),
                rejected(RecruitmentRejectionReason.EXPERIENCE_LEVEL.name(),
                        RecruitmentStage.SCREENING.name()),
                emailSent(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)),
                Map.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT,
                        RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)::get);

        assertEquals(count(ctx, NOW), count(ctx, NOW));
    }

    @Test
    void theUpsertReplacesTheCounts_ratherThanAddingToThem() {
        // The other half of "same numbers on a re-run", and the half a pure
        // recount cannot show: with the sibling projection's additive idiom
        // (cnt = cnt + VALUES(cnt), correct there because it sees each event
        // once) every night would add another copy of the same 90 days.
        String upsert = RecruitmentCommsCoverageJob.UPSERT;

        assertTrue(upsert.contains("occurred_count = VALUES(occurred_count)"), upsert);
        assertTrue(upsert.contains("emailed_count  = VALUES(emailed_count)"), upsert);
        assertFalse(upsert.contains("occurred_count + "), upsert);
        assertFalse(upsert.contains("emailed_count + "), upsert);
    }

    // ---- Fixtures ---------------------------------------------------------

    /** Counts the given events with no letters resolvable — occurrences only. */
    private static Map<String, Counts> countOf(CountedEvent... events) {
        return count(new RollupContext(List.of(events), Map.<String, String>of()::get), NOW);
    }

    private static CountedEvent applicationCreated(String origin) {
        return applicationCreatedAt(origin, RECENTLY);
    }

    private static CountedEvent applicationCreatedAt(String origin, LocalDateTime occurredAt) {
        return new CountedEvent(RecruitmentEventType.APPLICATION_CREATED, occurredAt,
                Map.of("origin", origin));
    }

    private static CountedEvent stageChanged(String direction, RecruitmentStage to) {
        return new CountedEvent(RecruitmentEventType.APPLICATION_STAGE_CHANGED, RECENTLY,
                Map.of("direction", direction, "to", to.name()));
    }

    private static CountedEvent rejected(String reasonCode, String fromStage) {
        return new CountedEvent(RecruitmentEventType.APPLICATION_REJECTED, RECENTLY,
                reasonCode == null
                        ? Map.of("from_stage", fromStage)
                        : Map.of("reason_code", reasonCode, "from_stage", fromStage));
    }

    private static CountedEvent pooled(boolean enteredPool, String poolStatus) {
        return new CountedEvent(RecruitmentEventType.CANDIDATE_POOLED, RECENTLY,
                Map.of("entered_pool", enteredPool, "pool_status", poolStatus));
    }

    private static CountedEvent emailSent(String templateKey) {
        return new CountedEvent(RecruitmentEventType.EMAIL_SENT, RECENTLY,
                Map.of("template_key", templateKey));
    }
}
