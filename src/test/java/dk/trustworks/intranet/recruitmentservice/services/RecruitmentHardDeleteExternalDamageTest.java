package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.notifications.CandidateDiscussionSlackNotifier;
import dk.trustworks.intranet.recruitmentservice.notifications.SlackCardReactor;
import dk.trustworks.intranet.recruitmentservice.reporting.RecruitmentReportingProjector;
import dk.trustworks.intranet.graph.GraphResponseExceptionMapper.GraphApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the hard delete does <em>outside</em> the database, and what it admits
 * to. Two review findings live here.
 *
 * <h3>M4 — irreversible external work in front of a transaction that can roll back</h3>
 * Microsoft Graph cancellations and Slack rewrites cannot be undone, and they
 * have to run before the cascade: they read rows it deletes, and remote I/O
 * may not happen inside a recruitment transaction (the 2026-08-11 reactor
 * deadlock rule). When the cascade then threw, the candidate survived with
 * their Outlook invitations cancelled and their Slack cards redacted, and
 * <b>nothing recorded it</b> — the ledger INSERT was inside the cascade and
 * rolled back with everything else. The fix keeps the ordering (see the
 * service javadoc for why post-commit redaction is worse) and makes the record
 * durable: the ledger row is committed before the first external call, and
 * what those calls did is committed before the cascade opens.
 *
 * <h3>M3 — a Graph leg that reported clean while invitations survived</h3>
 * {@code RecruitmentCalendarService.cancelEvent} returns void and swallows
 * everything, so the old residue was empty whether the cancellation worked or
 * not — on the leg most likely to fail. It also returns early when
 * {@code graph_event_id} is null, so an interview carrying only a
 * candidate-facing invitation was never touched at all.
 *
 * <p>Every test here runs without a database: {@code runDelete} takes the
 * preflight as a value, and the Graph call is a seam.</p>
 */
class RecruitmentHardDeleteExternalDamageTest {

    private static final String CANDIDATE = "11111111-1111-1111-1111-111111111111";
    private static final String ACTOR = "22222222-2222-2222-2222-222222222222";
    private static final String APPLICATION = "33333333-3333-3333-3333-333333333333";
    private static final String INTERVIEW = "44444444-4444-4444-4444-444444444444";
    private static final String LEDGER = "55555555-5555-5555-5555-555555555555";

    private RecruitmentCandidateHardDeleteService service;
    private RecordingCascade cascade;
    private RecordingSlackCardReactor slackCards;
    private RecordingDiscussionNotifier discussionRoots;
    private final List<String> graphCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new RecruitmentCandidateHardDeleteService();
        cascade = new RecordingCascade();
        slackCards = new RecordingSlackCardReactor();
        discussionRoots = new RecordingDiscussionNotifier();

        service.cascade = cascade;
        service.slackCardReactor = slackCards;
        service.discussionSlackNotifier = discussionRoots;
        service.reportingProjector = new StubProjector();
        service.graphEventCanceller = (mailbox, eventId) -> graphCalls.add(mailbox + "/" + eventId);
    }

    // ---- M4: the durable record ------------------------------------------------------

    @Test
    void aCascadeFailureLeavesADurableRecordOfEverythingAlreadyRedacted() {
        cascade.failCascadeWith = new IllegalStateException("FK violation two thirds of the way in");

        RuntimeException thrown = assertThrows(IllegalStateException.class,
                () -> service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight()));
        assertEquals("FK violation two thirds of the way in", thrown.getMessage(),
                "the real cause must reach the caller, not a bookkeeping failure");

        // The record was written, and it was written BEFORE the doomed cascade.
        assertNotNull(cascade.recordedExternal,
                "the external redaction must be recorded even though the delete failed — this "
                        + "is the whole finding: cancelled invitations and rewritten Slack "
                        + "cards for a candidate who still exists, with nothing to show for it");
        assertEquals(List.of("openLedger", "recordExternalRedaction", "deleteCandidate",
                        "markRolledBack"),
                cascade.callLog,
                "and the record must be committed before the transaction that rolled back");

        assertEquals(List.of(INTERVIEW + "#internal", INTERVIEW + "#candidate"),
                cascade.recordedExternal.get("graphEventsCancelled"),
                "both Outlook events were really cancelled, so both are named");
        assertEquals(List.of(APPLICATION), cascade.recordedExternal.get("slackRootCardsRedacted"),
                "the Slack root card was really rewritten, so it is named too");
        assertEquals(LEDGER, cascade.rolledBackLedgerUuid,
                "the surviving row is stamped so an operator finds it by outcome, not by luck");
    }

    @Test
    void theLedgerCannotBeOpened_soNothingIrreversibleIsAttempted() {
        cascade.failOpenWith = new IllegalStateException("ledger table unavailable");

        assertThrows(IllegalStateException.class,
                () -> service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight()));

        assertTrue(graphCalls.isEmpty(),
                "no record means no irreversible work: the Outlook invitations must be untouched");
        assertEquals(0, slackCards.invocations,
                "and the Slack cards must be untouched");
    }

    @Test
    void aCleanDeleteStillRecordsWhatWasDoneOutside_andSaysTheResidueIsEmpty() {
        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight());

        assertEquals(List.of("openLedger", "recordExternalRedaction", "deleteCandidate",
                        "recordResidue"),
                cascade.callLog,
                "recordResidue closes the row off after the post-commit legs — this candidate "
                        + "has Slack presence, so the documented replies-and-DMs residual is set");
        assertEquals(List.of(INTERVIEW + "#internal", INTERVIEW + "#candidate"),
                summary.externallyRedacted().get("graphEventsCancelled"));
        assertFalse(summary.residue().containsKey("graphEventsNotCancelled"),
                "nothing failed, so nothing is claimed as residue: " + summary.residue());
        assertTrue(summary.reportingRebuilt());
    }

    // ---- M3: per-event honesty --------------------------------------------------------

    @Test
    void anOutlookInvitationThatSurvivesIsResidue_notASilentSuccess() {
        service.graphEventCanceller = (mailbox, eventId) -> {
            if (eventId.equals("candidate-event")) {
                throw new GraphApiException("Graph said 503", 503);
            }
            graphCalls.add(mailbox + "/" + eventId);
        };

        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight());

        assertEquals(List.of(INTERVIEW + "#candidate"),
                summary.residue().get("graphEventsNotCancelled"),
                "the candidate's invitation is still live and the response says so");
        assertNotNull(summary.residue().get("graphEventsNotCancelledReason"));
        assertEquals(List.of(INTERVIEW + "#internal"),
                summary.externallyRedacted().get("graphEventsCancelled"),
                "and the one that did work is not tarred with it");
        assertFalse(summary.residue().isEmpty(),
                "a non-empty residue must reach the caller — it is the only signal that a "
                        + "hand cleanup is needed");
    }

    @Test
    void aGraph404CountsAsCancelled_becauseTheEventIsOffTheCalendar() {
        service.graphEventCanceller = (mailbox, eventId) -> {
            throw new GraphApiException("ErrorItemNotFound", 404);
        };

        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight());

        assertFalse(summary.residue().containsKey("graphEventsNotCancelled"),
                "404 is idempotent success: the invitation is not on anyone's calendar");
        assertEquals(List.of(INTERVIEW + "#internal", INTERVIEW + "#candidate"),
                summary.externallyRedacted().get("graphEventsCancelled"));
    }

    @Test
    void calendarSyncOff_namesEveryEventItCouldNotEvenAttempt() {
        RecruitmentCandidateHardDeleteService.Preflight pre = preflight(false);

        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", pre);

        assertEquals(List.of(INTERVIEW + "#internal", INTERVIEW + "#candidate"),
                summary.residue().get("graphEventsNotCancelled"));
        assertTrue(graphCalls.isEmpty());
    }

    @Test
    void anUnresolvableOrganizerMailboxIsAFailure_notASkip() {
        RecruitmentCandidateHardDeleteService.GraphCancellation outcome =
                RecruitmentCandidateHardDeleteService.cancelEach(
                        List.of(new RecruitmentCandidateHardDeleteService.GraphEventHandle(
                                        INTERVIEW, "internal", null, "internal-event"),
                                new RecruitmentCandidateHardDeleteService.GraphEventHandle(
                                        INTERVIEW, "candidate", "  ", "candidate-event")),
                        (mailbox, eventId) -> {
                            throw new AssertionError("must not be called without a mailbox");
                        });

        assertEquals(List.of(), outcome.cancelled());
        assertEquals(List.of(INTERVIEW + "#internal", INTERVIEW + "#candidate"), outcome.failed(),
                "nobody cancelled these — an unreachable mailbox is exactly the case the old "
                        + "code reported as clean");
    }

    // ---- The widened selector (LOW finding) --------------------------------------------

    @Test
    void anInterviewCarryingOnlyACandidateInvitationIsStillCancelled() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid(INTERVIEW);
        interview.setGraphCandidateEventId("candidate-event");

        List<RecruitmentCandidateHardDeleteService.GraphEventHandle> handles =
                RecruitmentCandidateHardDeleteService.handlesFor(
                        interview, "organizer@trustworks.dk", "career@trustworks.dk");

        assertEquals(1, handles.size(),
                "keying the cleanup on graph_event_id alone missed exactly this row — the "
                        + "invitation still sitting in the deleted person's inbox");
        assertEquals(INTERVIEW + "#candidate", handles.get(0).ref());
        assertEquals("career@trustworks.dk", handles.get(0).mailbox(),
                "the candidate event lives in the shared organizer mailbox, not the "
                        + "interviewer's — cancelling it against the wrong one is a 404 that "
                        + "would then be read as success");
    }

    @Test
    void bothEventsAreCancelledAgainstTheirOwnMailboxes() {
        List<RecruitmentCandidateHardDeleteService.GraphEventHandle> handles =
                RecruitmentCandidateHardDeleteService.handlesFor(
                        interview(), "organizer@trustworks.dk", "career@trustworks.dk");

        assertEquals(2, handles.size());
        assertEquals("organizer@trustworks.dk", handles.get(0).mailbox());
        assertEquals("career@trustworks.dk", handles.get(1).mailbox());
    }

    @Test
    void anInterviewWithNoOutlookEventAtAllProducesNothingToCancel() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid(INTERVIEW);

        assertTrue(RecruitmentCandidateHardDeleteService.handlesFor(interview, "a@b.dk", "c@d.dk")
                .isEmpty());
    }

    // ---- Slack legs are recorded the same way ------------------------------------------

    @Test
    void aSlackCardThatCouldNotBeRedactedIsResidue_andIsNotClaimedAsRedacted() {
        slackCards.failed = List.of(APPLICATION);

        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight());

        assertEquals(List.of(APPLICATION), summary.residue().get("slackRootCardsNotRedacted"));
        assertFalse(summary.externallyRedacted().containsKey("slackRootCardsRedacted"),
                "the card still carries the candidate's name — claiming it as redacted would "
                        + "be the same lie the Graph leg used to tell");
    }

    @Test
    void aWholesaleSlackLookupFailureClaimsNothingAsRedacted() {
        slackCards.failed = List.of("<application lookup failed>");

        RecruitmentCandidateHardDeleteService.HardDeleteSummary summary =
                service.runDelete(CANDIDATE, ACTOR, "Created by mistake", preflight());

        assertFalse(summary.externallyRedacted().containsKey("slackRootCardsRedacted"),
                "the reactor could not even list the cards, so their state is unknown and "
                        + "nothing may be claimed");
        assertEquals(List.of("<application lookup failed>"),
                summary.residue().get("slackRootCardsNotRedacted"));
    }

    // ---- Fixtures ------------------------------------------------------------------------

    private static RecruitmentInterview interview() {
        RecruitmentInterview interview = new RecruitmentInterview();
        interview.setUuid(INTERVIEW);
        interview.setGraphEventId("internal-event");
        interview.setGraphCandidateEventId("candidate-event");
        return interview;
    }

    private RecruitmentCandidateHardDeleteService.Preflight preflight() {
        return preflight(true);
    }

    private RecruitmentCandidateHardDeleteService.Preflight preflight(boolean calendarEnabled) {
        List<RecruitmentCandidateHardDeleteService.GraphEventHandle> handles =
                RecruitmentCandidateHardDeleteService.handlesFor(
                        interview(), "organizer@trustworks.dk", "career@trustworks.dk");
        return new RecruitmentCandidateHardDeleteService.Preflight(
                List.of(APPLICATION), List.of(), List.of(),
                handles, calendarEnabled, List.of(APPLICATION), List.of(), false);
    }

    // ---- Stubs ---------------------------------------------------------------------------

    /** Records the ledger lifecycle in call order; that order IS the fix. */
    private static class RecordingCascade extends RecruitmentCandidateDeleteCascade {
        final List<String> callLog = new ArrayList<>();
        Map<String, Object> recordedExternal;
        Map<String, Object> recordedResidue;
        String rolledBackLedgerUuid;
        RuntimeException failOpenWith;
        RuntimeException failCascadeWith;

        @Override
        public String openLedger(String candidateUuid, String actorUuid, String reason) {
            callLog.add("openLedger");
            if (failOpenWith != null) {
                throw failOpenWith;
            }
            return LEDGER;
        }

        @Override
        public void recordExternalRedaction(String ledgerUuid, Map<String, Object> external,
                                            Map<String, Object> residue) {
            callLog.add("recordExternalRedaction");
            recordedExternal = new LinkedHashMap<>(external);
            recordedResidue = new LinkedHashMap<>(residue);
        }

        @Override
        public CascadeResult deleteCandidate(String candidateUuid, String ledgerUuid) {
            callLog.add("deleteCandidate");
            if (failCascadeWith != null) {
                throw failCascadeWith;
            }
            return new CascadeResult(ledgerUuid, Map.of("recruitment_candidates", 1), List.of());
        }

        @Override
        public void markRolledBack(String ledgerUuid, String failureClassName) {
            callLog.add("markRolledBack");
            rolledBackLedgerUuid = ledgerUuid;
        }

        @Override
        public void recordResidue(String ledgerUuid, Map<String, Object> residue) {
            callLog.add("recordResidue");
        }
    }

    private static class RecordingSlackCardReactor extends SlackCardReactor {
        List<String> failed = List.of();
        int invocations;

        @Override
        public List<String> redactRootCardsForHardDelete(String candidateUuid) {
            invocations++;
            return failed;
        }
    }

    private static class RecordingDiscussionNotifier extends CandidateDiscussionSlackNotifier {
        @Override
        public List<String> redactDiscussionRootsForHardDelete(String candidateUuid) {
            return List.of();
        }
    }

    private static class StubProjector extends RecruitmentReportingProjector {
        @Override
        public RebuildSummary rebuild() {
            return new RebuildSummary(1, 0, false);
        }
    }
}
