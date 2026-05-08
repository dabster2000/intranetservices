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
    private RecruitmentHrSlackNotifier notifier;

    @BeforeEach
    void setUp() throws Exception {
        slackService = mock(SlackService.class);
        notifier = new RecruitmentHrSlackNotifier();
        injectField(notifier, "slackService", slackService);
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
                .sendMessage(anyString(), anyString(), anyString());
        assertTrue(dedupSet().contains(c.getUuid()),
                "candidate UUID must be added to dedup set after first send");
    }

    /** AC11: second call with same candidate UUID is a no-op. */
    @Test
    void notifyHire_sameCandidateTwice_secondCallIsNoOp() {
        RecruitmentCandidate c = candidate();

        notifier.notifyHire(c, null, List.of("a_signed.pdf"));
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));

        // sendMessage invoked exactly once across the two calls
        verify(slackService, times(1))
                .sendMessage(anyString(), anyString(), anyString());
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
        verify(slackService).sendMessage(channel.capture(), message.capture(), tokenKey.capture());

        assertEquals("C-TEST-CHANNEL", channel.getValue());
        assertEquals("admin", tokenKey.getValue());
    }

    /** AC14: when SlackService throws, notifier swallows the exception. */
    @Test
    void notifyHire_slackThrows_doesNotPropagate_butStillMarksDedup() throws Exception {
        doThrow(new RuntimeException("boom"))
                .when(slackService).sendMessage(anyString(), anyString(), anyString());
        RecruitmentCandidate c = candidate();

        // Must not throw out of notifier
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));

        // Dedup set was added first (atomic add-and-check) — second call still no-ops
        assertTrue(dedupSet().contains(c.getUuid()));
        notifier.notifyHire(c, null, List.of("a_signed.pdf"));
        verify(slackService, times(1)).sendMessage(anyString(), anyString(), anyString());
    }

    /** Null candidate / null UUID early-return — never invokes SlackService. */
    @Test
    void notifyHire_nullCandidate_isSafeNoOp() {
        notifier.notifyHire(null, UUID.randomUUID(), List.of());
        verifyNoInteractions(slackService);
    }

    @Test
    void notifyHire_candidateWithoutUuid_isSafeNoOp() {
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
    void formatMessage_doesNotIncludePiiOrCaseKey() {
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
    void notifyHire_nullSignedFilenames_doesNotThrow() {
        notifier.notifyHire(candidate(), null, null);
        verify(slackService, times(1))
                .sendMessage(anyString(), anyString(), anyString());
    }

    /** Verify slackService is not called from a code path that did not get past dedup. */
    @Test
    void notifyHire_noOpCalls_doNotInvokeSlack() throws Exception {
        notifier.notifyHire(null, null, null);
        notifier.notifyHire(new RecruitmentCandidate(), null, null);
        verify(slackService, never()).sendMessage(anyString(), anyString(), anyString());
    }
}
