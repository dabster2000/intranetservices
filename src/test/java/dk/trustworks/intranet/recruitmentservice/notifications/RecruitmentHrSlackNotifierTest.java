package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecruitmentHrSlackNotifier}.
 *
 * <p>Covers the AC10–AC15 acceptance criteria that do not require Panache
 * Company/User resolution: dedup (AC11/AC15), channel id + token key wiring
 * (AC12), non-fatal-on-failure (AC14), and the PII allowlist contract
 * (security-focus item §1: forbidden substrings must not appear in the
 * formatted message body).
 */
class RecruitmentHrSlackNotifierTest {

    private SlackService slackService;
    private RecruitmentSlackChannelRouter router;
    private RecruitmentHrSlackNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        slackService = mock(SlackService.class);
        // Offer split off by default: both settings-driven channels blank, so
        // resolution falls through to the config channelId — the pre-split
        // behavior every existing assertion was written against.
        router = mock(RecruitmentSlackChannelRouter.class);
        when(router.offerChannel()).thenReturn(java.util.Optional.empty());
        when(router.defaultChannel()).thenReturn(java.util.Optional.empty());
        notifier = new RecruitmentHrSlackNotifier();
        injectField(notifier, "slackService", slackService);
        injectField(notifier, "router", router);
        injectField(notifier, "channelId", "C0B1XUB3AEB");
        injectField(notifier, "botTokenKey", "mother");
        injectField(notifier, "dossierBaseUrl", "https://intra.trustworks.dk/recruitment/candidates");
        clearDedupSet();
    }

    private static void injectField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @SuppressWarnings("unchecked")
    private static void clearDedupSet() throws Exception {
        Field f = RecruitmentHrSlackNotifier.class.getDeclaredField("NOTIFIED_CANDIDATE_UUIDS");
        f.setAccessible(true);
        Set<String> set = (Set<String>) f.get(null);
        set.clear();
    }

    @SuppressWarnings("unchecked")
    private static Set<String> dedupSet() throws Exception {
        Field f = RecruitmentHrSlackNotifier.class.getDeclaredField("NOTIFIED_CANDIDATE_UUIDS");
        f.setAccessible(true);
        return (Set<String>) f.get(null);
    }

    private static RecruitmentCandidate candidate() {
        RecruitmentCandidate c = new RecruitmentCandidate();
        c.setUuid(UUID.randomUUID().toString());
        c.setFirstName("Jane");
        c.setLastName("Doe");
        c.setEmail("jane.doe@example.com");
        return c;
    }

    /** AC15: dedup set is the static field, type is concurrent. */
    @Test
    void dedupSet_isStaticConcurrentHashMapBacked() throws Exception {
        Field f = RecruitmentHrSlackNotifier.class.getDeclaredField("NOTIFIED_CANDIDATE_UUIDS");
        assertTrue(java.lang.reflect.Modifier.isStatic(f.getModifiers()), "must be static");
        assertTrue(java.lang.reflect.Modifier.isFinal(f.getModifiers()), "must be final");
        assertTrue(java.lang.reflect.Modifier.isPrivate(f.getModifiers()), "must be private");
        f.setAccessible(true);
        Object set = f.get(null);
        // ConcurrentHashMap.newKeySet() returns ConcurrentHashMap$KeySetView
        assertTrue(set.getClass().getName().contains("ConcurrentHashMap"),
                "must be backed by ConcurrentHashMap.newKeySet()");
        assertEquals(ConcurrentHashMap.newKeySet().getClass(), set.getClass());
    }

    /** AC11 + AC12: first call posts, dedup set tracks the UUID. */
    @Test
    void notifyHire_firstCall_postsAndAddsToDedupSet() throws Exception {
        RecruitmentCandidate c = candidate();

        notifier.notifyHire(c, UUID.randomUUID(), List.of("Contract_signed.pdf"));

        verify(slackService, times(1))
                .sendMessageStrict(anyString(), anyString(), anyString());
        assertTrue(dedupSet().contains(c.getUuid()),
                "candidate UUID must be added to dedup set after first send");
    }

    /** AC11: second call with same candidate UUID is a no-op. */
    @Test
    void notifyHire_sameCandidateTwice_secondCallIsNoOp() throws Exception {
        RecruitmentCandidate c = candidate();

        notifier.notifyHire(c, null, List.of("a_signed.pdf"));
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));

        // sendMessageStrict invoked exactly once across the two calls
        verify(slackService, times(1))
                .sendMessageStrict(anyString(), anyString(), anyString());
    }

    /** AC12: channel id and token key flow through to SlackService unchanged. */
    @Test
    void notifyHire_passesChannelIdAndTokenKeyFromConfig() throws Exception {
        injectField(notifier, "channelId", "C-TEST-CHANNEL");
        injectField(notifier, "botTokenKey", "admin");
        RecruitmentCandidate c = candidate();

        notifier.notifyHire(c, null, List.of("a_signed.pdf"));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> tokenKey = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageStrict(channel.capture(), message.capture(), tokenKey.capture());

        assertEquals("C-TEST-CHANNEL", channel.getValue());
        assertEquals("admin", tokenKey.getValue());
    }

    /** AC14: when SlackService throws, notifier swallows the exception. */
    @Test
    void notifyHire_slackThrows_doesNotPropagate_butStillMarksDedup() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(slackService).sendMessageStrict(anyString(), anyString(), anyString());
        RecruitmentCandidate c = candidate();

        // Must not throw out of notifier
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));

        // Dedup set was added first (atomic add-and-check) — second call still no-ops
        assertTrue(dedupSet().contains(c.getUuid()));
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));
        verify(slackService, times(1)).sendMessageStrict(anyString(), anyString(), anyString());
    }

    /** Null candidate / null UUID early-return — never invokes SlackService. */
    @Test
    void notifyHire_nullCandidate_isSafeNoOp() throws Exception {
        notifier.notifyHire(null, UUID.randomUUID(), List.of());
        verifyNoInteractions(slackService);
    }

    @Test
    void notifyHire_candidateWithoutUuid_isSafeNoOp() throws Exception {
        RecruitmentCandidate c = new RecruitmentCandidate();
        c.setUuid(null);
        notifier.notifyHire(c, null, List.of());
        verifyNoInteractions(slackService);
    }

    /**
     * Security-focus §1 (PII allowlist): the formatted message body must NOT
     * contain candidate email, candidate CPR, or signer email substrings.
     * Allowed: candidate first/last name, signed-PDF filenames, dossier link.
     */
    @Test
    void formatMessage_doesNotIncludePiiOrCaseKey() throws Exception {
        RecruitmentCandidate c = candidate();
        c.setEmail("PII-EMAIL@example.com");

        String body = notifier.formatMessage(c, null, List.of("Contract_signed.pdf"));

        assertFalse(body.contains("PII-EMAIL@example.com"),
                "candidate email must not appear in Slack message");
        // Sanity: candidate name + filename ARE present
        assertTrue(body.contains("Jane"), "first name should be present");
        assertTrue(body.contains("Doe"), "last name should be present");
        assertTrue(body.contains("Contract_signed.pdf"), "signed filename should be present");
        // Dossier link uses candidate UUID (security-focus §2)
        assertTrue(body.contains(c.getUuid()), "dossier link must include candidate UUID");
    }

    /** signedFilenames null is tolerated — formatter treats it as empty. */
    @Test
    void notifyHire_nullSignedFilenames_doesNotThrow() throws Exception {
        notifier.notifyHire(candidate(), null, null);
        verify(slackService, times(1))
                .sendMessageStrict(anyString(), anyString(), anyString());
    }

    /** Verify slackService is not called from a code path that did not get past dedup. */
    @Test
    void notifyHire_noOpCalls_doNotInvokeSlack() throws Exception {
        notifier.notifyHire(null, null, null);
        notifier.notifyHire(new RecruitmentCandidate(), null, null);
        verify(slackService, never()).sendMessageStrict(anyString(), anyString(), anyString());
    }

    // ── Hired, but nothing signed to file ──────────────────────────────────

    /**
     * A hire whose paperwork never came back signed promotes zero documents.
     * Silence there is indistinguishable from a clean promotion, so HR gets
     * told to file the contract by hand.
     */
    @Test
    void notifyHireWithoutSignedContract_asksAHumanToFileThePaperwork() throws Exception {
        RecruitmentCandidate c = candidate();

        notifier.notifyHireWithoutSignedContract(c, null);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendMessageStrict(anyString(), body.capture(), anyString());
        assertTrue(body.getValue().contains("no signed contract"),
                "the message must say what is missing: " + body.getValue());
        assertTrue(body.getValue().contains(c.getUuid()), "dossier link must include candidate UUID");
    }

    @Test
    void notifyHireWithoutSignedContract_sharesTheHireDedupSet() throws Exception {
        // A candidate is announced once, whichever outcome got there first —
        // otherwise a re-drive after a deploy posts a second message.
        RecruitmentCandidate c = candidate();

        notifier.notifyHireWithoutSignedContract(c, null);
        notifier.notifyHireWithoutSignedContract(c, null);
        notifier.notifyHire(c, null, List.of());

        verify(slackService, times(1)).sendMessageStrict(anyString(), anyString(), anyString());
    }

    @Test
    void notifyHireWithoutSignedContract_doesNotLeakCandidateEmail() throws Exception {
        RecruitmentCandidate c = candidate();
        c.setEmail("PII-EMAIL@example.com");

        String body = notifier.formatNoBindingDocumentsMessage(c, null);

        assertFalse(body.contains("PII-EMAIL@example.com"),
                "candidate email must not appear in Slack message");
    }

    @Test
    void notifyHireWithoutSignedContract_noOpCalls_doNotInvokeSlack() throws Exception {
        notifier.notifyHireWithoutSignedContract(null, null);
        notifier.notifyHireWithoutSignedContract(new RecruitmentCandidate(), null);
        verify(slackService, never()).sendMessageStrict(anyString(), anyString(), anyString());
    }

    // ── Offer-channel routing (offer split, 2026-08-25) ────────────────────

    /** Offer channel configured ⇒ hire messages move there. */
    @Test
    void notifyHire_prefersTheOfferChannel() throws Exception {
        when(router.offerChannel()).thenReturn(java.util.Optional.of("C-OFFER"));

        notifier.notifyHire(candidate(), null, List.of("a_signed.pdf"));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageStrict(channel.capture(), anyString(), anyString());
        assertEquals("C-OFFER", channel.getValue());
    }

    /** Offer blank, default set ⇒ the default channel carries the message. */
    @Test
    void notifyHire_fallsBackToTheDefaultChannel() throws Exception {
        when(router.defaultChannel()).thenReturn(java.util.Optional.of("C-DEFAULT"));

        notifier.notifyHire(candidate(), null, List.of("a_signed.pdf"));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageStrict(channel.capture(), anyString(), anyString());
        assertEquals("C-DEFAULT", channel.getValue());
    }

    /** Both settings blank ⇒ the config channelId — byte-identical pre-split behavior. */
    @Test
    void notifyHire_bothChannelsBlank_usesTheConfigFallback() throws Exception {
        notifier.notifyHire(candidate(), null, List.of("a_signed.pdf"));

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService).sendMessageStrict(channel.capture(), anyString(), anyString());
        assertEquals("C0B1XUB3AEB", channel.getValue());
    }

    // ── Contract sent (a moment that exists only on the offer channel) ─────

    @Test
    void notifyContractSent_postsOnlyWhenTheOfferChannelIsConfigured() throws Exception {
        // Blank offer channel: this NEW ping must not leak into the default
        // channel — blank means "everything behaves as before the split".
        notifier.notifyContractSent(candidate(), 2, 1);
        verifyNoInteractions(slackService);

        when(router.offerChannel()).thenReturn(java.util.Optional.of("C-OFFER"));
        notifier.notifyContractSent(candidate(), 2, 1);

        ArgumentCaptor<String> channel = ArgumentCaptor.forClass(String.class);
        verify(slackService, times(1)).sendMessageStrict(channel.capture(), anyString(), anyString());
        assertEquals("C-OFFER", channel.getValue());
    }

    @Test
    void notifyContractSent_isNotDeduped_aResendIsNews() throws Exception {
        when(router.offerChannel()).thenReturn(java.util.Optional.of("C-OFFER"));
        RecruitmentCandidate c = candidate();

        notifier.notifyContractSent(c, 2, 1);
        notifier.notifyContractSent(c, 2, 1);

        verify(slackService, times(2)).sendMessageStrict(anyString(), anyString(), anyString());
    }

    @Test
    void formatContractSentMessage_carriesCountsAndLink_neverPii() throws Exception {
        RecruitmentCandidate c = candidate();
        c.setEmail("PII-EMAIL@example.com");

        String body = notifier.formatContractSentMessage(c, 3, 2);

        assertFalse(body.contains("PII-EMAIL@example.com"),
                "candidate email must not appear in Slack message");
        assertTrue(body.contains("3 documents"), "document count should be present");
        assertTrue(body.contains("2 signers"), "signer count should be present");
        assertTrue(body.contains(c.getUuid()), "dossier link must include candidate UUID");
    }

    // ── Candidate invite failed (calendar-retry dead-letter, V533) ─────────

    @Test
    void formatCandidateInviteFailedMessage_namesTheFactsNeverPii() throws Exception {
        RecruitmentCandidate c = candidate();
        c.setEmail("PII-EMAIL@example.com");
        dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview interview =
                new dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview();
        interview.setUuid("int-1");
        interview.setScheduledAt(java.time.LocalDateTime.of(2026, 8, 25, 15, 0));

        String body = notifier.formatCandidateInviteFailedMessage(c, interview,
                "The candidate's Outlook invitation could NOT be sent. Invite them by hand.",
                "Graph API error 504 Gateway Timeout",
                "078c03a7-cc41-449a-bf8e-205e7c0db0c1");

        assertFalse(body.contains("PII-EMAIL@example.com"),
                "candidate email must not appear in Slack message");
        assertTrue(body.contains("2026-08-25T15:00"), "the interview time is the actionable fact");
        assertTrue(body.contains("Invite them by hand"), "the message tells HR what to do");
        assertTrue(body.contains("078c03a7-cc41-449a-bf8e-205e7c0db0c1"),
                "the Graph request-id is the support-ticket handle");
        assertTrue(body.contains(c.getUuid()), "profile link must include candidate UUID");
    }

    @Test
    void notifyCandidateInviteFailed_slackFailureNeverPropagates() throws Exception {
        // The real-world shape (2026-08-27): a private channel without the bot
        // answers channel_not_found, which the STRICT send raises as a checked
        // IOException — the notifier must swallow that exactly like a crash.
        doThrow(new java.io.IOException("Slack post failed for channel C-OFFER: channel_not_found"))
                .when(slackService)
                .sendMessageStrict(anyString(), anyString(), anyString());
        dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview interview =
                new dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview();
        interview.setUuid("int-1");

        notifier.notifyCandidateInviteFailed(candidate(), interview, "problem", "reason", null);
        // no exception = pass: a Slack outage must not fail scheduling or the sweep
    }

    @Test
    void notifyCandidateInviteFailed_isNotDeduped_eachTerminalFactIsNews() throws Exception {
        dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview interview =
                new dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview();
        interview.setUuid("int-1");

        notifier.notifyCandidateInviteFailed(candidate(), interview, "problem", "reason", null);
        notifier.notifyCandidateInviteFailed(candidate(), interview, "problem", "reason", null);

        verify(slackService, times(2)).sendMessageStrict(anyString(), anyString(), anyString());
    }
}
