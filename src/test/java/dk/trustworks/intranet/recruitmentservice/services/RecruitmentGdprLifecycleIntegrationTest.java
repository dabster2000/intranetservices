package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * P19 DoD: the spec §5.5 lifecycle table, every transition driven by
 * backdated fixtures through the REAL sweep ({@link RecruitmentGdprService})
 * and the REAL consent commands ({@link RecruitmentConsentService}):
 * <ul>
 *   <li>RetentionCountdown → Anonymized (deadline passed, automatic);</li>
 *   <li>RetentionCountdown → PooledWithConsent (renewal email → grant sets
 *       {@code retention_deadline = +12 months});</li>
 *   <li>PooledWithConsent → RetentionCountdown (withdraw resumes the
 *       6-month countdown; expiry flips the consent + CONSENT_EXPIRED);</li>
 *   <li>the two-email renewal choreography (first at −30 d, exactly one;
 *       second only inside the −7 d window and ≥48 h after the first);</li>
 *   <li>hired candidates leave the regime; a live GRANTED consent blocks
 *       auto-erasure even with a stale deadline (belt-and-braces);</li>
 *   <li>flag off ⇒ the sweep is a no-op.</li>
 * </ul>
 * Every DB assertion runs in a fresh transaction (the P11 flush lesson).
 * S3 is mocked — the anonymizer's S3 leg has its own zero-PII test.
 */
@QuarkusTest
class RecruitmentGdprLifecycleIntegrationTest {

    private static final String FLAG = "recruitment.gdpr.enabled";

    @Inject
    RecruitmentGdprService gdprService;

    @Inject
    RecruitmentConsentService consentService;

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private final List<String> candidateUuids = new ArrayList<>();
    private final List<String> fixtureEmails = new ArrayList<>();
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        when(storageService.deleteAllCandidateFiles(any(UUID.class))).thenReturn(0);
        QuarkusTransaction.requiringNew().run(() -> setFlag("true"));
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (!candidateUuids.isEmpty()) {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                        .setParameter("c", candidateUuids).executeUpdate();
            }
            for (String email : fixtureEmails) {
                em.createNativeQuery("DELETE FROM mail WHERE mail = :to")
                        .setParameter("to", email).executeUpdate();
            }
            restoreFlag();
        });
        candidateUuids.clear();
        fixtureEmails.clear();
    }

    // ------------------------------------------------------------------
    // Gate
    // ------------------------------------------------------------------

    @Test
    void sweep_flagOff_isANoOp() {
        String candidate = insertCandidate("ACTIVE", minusDays(1), true);
        QuarkusTransaction.requiringNew().run(() -> setFlag("false"));

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();

        assertEquals(false, summary.enabled());
        assertEquals(CandidateStatus.ACTIVE, reload(candidate).getStatus(),
                "flag off must mean NOTHING is deleted");
    }

    // ------------------------------------------------------------------
    // RetentionCountdown → Anonymized (automatic)
    // ------------------------------------------------------------------

    @Test
    void pastDeadline_noConsent_isAutoAnonymized() {
        String candidate = insertCandidate("ACTIVE", minusDays(1), true);

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();

        assertEquals(1, summary.anonymized());
        RecruitmentCandidate reloaded = reload(candidate);
        assertEquals(CandidateStatus.ANONYMIZED, reloaded.getStatus());
        assertNotNull(reloaded.getAnonymizedAt());
        assertNull(reloaded.getEmail());
        assertNull(reloaded.getRetentionDeadline(), "the consumed clock is cleared");

        RecruitmentEvent event = lastEventOfType(candidate, RecruitmentEventType.CANDIDATE_ANONYMIZED);
        assertNotNull(event, "the erasure itself is on the timeline");
        assertTrue(event.getPayload().contains("\"mode\":\"AUTO\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);

        // Idempotency: the next sweep must not touch the candidate again.
        RecruitmentGdprService.SweepSummary second = gdprService.sweep();
        assertEquals(0, second.anonymized());
    }

    @Test
    void hiredCandidate_leavesTheRetentionRegime() {
        String candidate = insertCandidate("HIRED", minusDays(30), true);

        gdprService.sweep();

        assertEquals(CandidateStatus.HIRED, reload(candidate).getStatus(),
                "hired candidates are access-restricted, never auto-erased (spec §5.5)");
    }

    @Test
    void liveGrantedConsent_blocksAutoErasure_evenWithAStaleDeadline() {
        String candidate = insertCandidate("POOLED", minusDays(1), true);
        insertConsent(candidate, "GRANTED", now().plusMonths(6), null, null);

        gdprService.sweep();

        assertEquals(CandidateStatus.POOLED, reload(candidate).getStatus(),
                "a live GRANTED consent must always win over a stale deadline");
    }

    // ------------------------------------------------------------------
    // Renewal choreography
    // ------------------------------------------------------------------

    @Test
    void insideFirstWindow_sendsExactlyOneRenewal_andMintsAToken() {
        String candidate = insertCandidate("POOLED", plusDays(20), true);

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();
        assertEquals(1, summary.renewalsSent());

        // Mail row + EMAIL_SENT event + minted token — all DB-asserted.
        assertEquals(1, mailCount(candidate));
        RecruitmentEvent event = lastEventOfType(candidate, RecruitmentEventType.EMAIL_SENT);
        assertTrue(event.getPayload().contains("\"template_key\":\"CONSENT_RENEWAL\""));
        assertTrue(event.getPayload().contains("\"renewal_number\":1"));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentConsent consent = RecruitmentConsent
                    .<RecruitmentConsent>find("candidateUuid", candidate).firstResult();
            assertNotNull(consent, "the sweep creates the REQUESTED consent row");
            assertEquals(RecruitmentConsentStatus.REQUESTED, consent.getStatus());
            assertNotNull(consent.getTokenHash(), "a token must be minted with the email");
            assertNotNull(consent.getTokenExpiresAt());
        });

        // A re-run must NOT send a second first-email.
        RecruitmentGdprService.SweepSummary second = gdprService.sweep();
        assertEquals(0, second.renewalsSent());
        assertEquals(1, mailCount(candidate));
    }

    @Test
    void insideSecondWindow_sendsTheSecondRenewal_onceAndSpaced() {
        String candidate = insertCandidate("POOLED", plusDays(5), true);
        // The first renewal went out three days ago (backdated bookkeeping).
        String deadlineKey = reload(candidate).getRetentionDeadline().toString();
        insertRenewalSentEvent(candidate, deadlineKey, 1, minusDays(3));

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();
        assertEquals(1, summary.renewalsSent());
        RecruitmentEvent event = lastEventOfType(candidate, RecruitmentEventType.EMAIL_SENT);
        assertTrue(event.getPayload().contains("\"renewal_number\":2"));

        // Two is the hard cap for one deadline.
        assertEquals(0, gdprService.sweep().renewalsSent());
    }

    @Test
    void secondRenewal_neverOnTheNightAfterTheFirst() {
        String candidate = insertCandidate("POOLED", plusDays(5), true);
        String deadlineKey = reload(candidate).getRetentionDeadline().toString();
        insertRenewalSentEvent(candidate, deadlineKey, 1, now().minusHours(12));

        assertEquals(0, gdprService.sweep().renewalsSent(),
                "late flag-on: both renewal mails on consecutive nights is spam, not compliance");
    }

    @Test
    void nonPooledCandidate_getsNoRenewal_justTheDeadline() {
        String candidate = insertCandidate("ACTIVE", plusDays(20), true);

        assertEquals(0, gdprService.sweep().renewalsSent(),
                "pool retention consent is only offered to pooled candidates (spec §5.5)");
        assertEquals(0, mailCount(candidate));
    }

    @Test
    void candidateWithoutEmail_isAVisibleSkip_notAFailure() {
        String candidate = insertCandidate("POOLED", plusDays(20), false);

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();
        assertEquals(0, summary.renewalsSent());
        assertEquals(0, summary.failures());
        assertEquals(CandidateStatus.POOLED, reload(candidate).getStatus());
    }

    // ------------------------------------------------------------------
    // Grant / withdraw / expiry (the consent side of the lifecycle)
    // ------------------------------------------------------------------

    @Test
    void grantViaToken_pushesTheDeadlineTwelveMonths() {
        String candidate = insertCandidate("POOLED", plusDays(20), true);
        String token = mint(candidate);

        RecruitmentConsentService.ConsentView view = consentService.grant(token);

        assertNotNull(view);
        assertEquals(RecruitmentConsentStatus.GRANTED, view.status());
        RecruitmentCandidate reloaded = reload(candidate);
        long months = ChronoUnit.DAYS.between(now(), reloaded.getRetentionDeadline());
        assertTrue(months > 360 && months < 370,
                "grant must set retention_deadline = +12 months (was +" + months + " days)");
        RecruitmentEvent event = lastEventOfType(candidate, RecruitmentEventType.CONSENT_GRANTED);
        assertTrue(event.getPayload().contains("\"origin\":\"consent_page\""));
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
    }

    @Test
    void withdrawViaToken_resumesTheSixMonthCountdown() {
        String candidate = insertCandidate("POOLED", plusDays(20), true);
        LocalDateTime processEnded = minusDays(30);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("UPDATE recruitment_candidates SET process_ended_at = :ended "
                                + "WHERE uuid = :c")
                        .setParameter("ended", processEnded)
                        .setParameter("c", candidate).executeUpdate());
        String token = mint(candidate);
        consentService.grant(token);

        RecruitmentConsentService.ConsentView view = consentService.withdraw(token);

        assertNotNull(view);
        assertEquals(RecruitmentConsentStatus.WITHDRAWN, view.status());
        RecruitmentCandidate reloaded = reload(candidate);
        long driftSeconds = Math.abs(ChronoUnit.SECONDS.between(
                processEnded.plusMonths(6), reloaded.getRetentionDeadline()));
        assertTrue(driftSeconds <= 1,
                "withdraw resumes the process countdown (process_ended_at + 6 months), "
                        + "drift was " + driftSeconds + "s");
        assertNotNull(lastEventOfType(candidate, RecruitmentEventType.CONSENT_WITHDRAWN));
    }

    @Test
    void withdraw_withoutAnEndedProcess_leavesNoClock() {
        String candidate = insertCandidate("POOLED", plusDays(20), true);
        String token = mint(candidate);
        consentService.grant(token);

        consentService.withdraw(token);

        assertNull(reload(candidate).getRetentionDeadline(),
                "no ended process = no clock (the P4 NULL convention)");
    }

    @Test
    void expiredGrantedConsent_flipsToExpired_withItsOwnEvent() {
        String candidate = insertCandidate("POOLED", minusDays(1), true);
        insertConsent(candidate, "GRANTED", minusDays(1), null, null);

        RecruitmentGdprService.SweepSummary summary = gdprService.sweep();

        assertEquals(1, summary.consentsExpired());
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentConsent consent = RecruitmentConsent
                    .<RecruitmentConsent>find("candidateUuid", candidate).firstResult();
            assertEquals(RecruitmentConsentStatus.EXPIRED, consent.getStatus());
        });
        RecruitmentEvent event = lastEventOfType(candidate, RecruitmentEventType.CONSENT_EXPIRED);
        assertNotNull(event);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        // With the consent expired and the deadline passed, the same sweep
        // already anonymized (expiry runs before the deadline sub-sweep).
        assertEquals(CandidateStatus.ANONYMIZED, reload(candidate).getStatus(),
                "expiry resumes the countdown; a passed deadline erases");
    }

    // ------------------------------------------------------------------
    // Fixtures & helpers
    // ------------------------------------------------------------------

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static LocalDateTime minusDays(int days) {
        return now().minusDays(days);
    }

    private static LocalDateTime plusDays(int days) {
        return now().plusDays(days);
    }

    private String insertCandidate(String status, LocalDateTime retentionDeadline,
                                   boolean withEmail) {
        String uuid = UUID.randomUUID().toString();
        candidateUuids.add(uuid);
        String email = withEmail ? "p19.lifecycle+" + uuid.substring(0, 8) + "@example.invalid" : null;
        if (email != null) {
            fixtureEmails.add(email);
        }
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_candidates
                                    (uuid, first_name, last_name, email, status, pool_status, source,
                                     retention_deadline, created_by_useruuid, created_at, updated_at)
                                VALUES (:uuid, 'Gdpr', 'Lifecycle', :email, :status, :pool, 'OTHER',
                                        :deadline, :actor, NOW(), NOW())
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("email", email)
                        .setParameter("status", status)
                        .setParameter("pool", "POOLED".equals(status) ? "PROSPECT" : null)
                        .setParameter("deadline", retentionDeadline)
                        .setParameter("actor", UUID.randomUUID().toString())
                        .executeUpdate());
        return uuid;
    }

    private void insertConsent(String candidateUuid, String status, LocalDateTime expiresAt,
                               String tokenHash, LocalDateTime tokenExpiresAt) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_consents
                                    (uuid, candidate_uuid, kind, status, granted_at, expires_at,
                                     token_hash, token_expires_at, created_at, updated_at, created_by)
                                VALUES (:uuid, :candidate, 'TALENT_POOL_RETENTION', :status,
                                        :granted, :expires, :hash, :hashExpires, NOW(), NOW(), 'test')
                                """)
                        .setParameter("uuid", UUID.randomUUID().toString())
                        .setParameter("candidate", candidateUuid)
                        .setParameter("status", status)
                        .setParameter("granted", "GRANTED".equals(status) ? now().minusMonths(12) : null)
                        .setParameter("expires", expiresAt)
                        .setParameter("hash", tokenHash)
                        .setParameter("hashExpires", tokenExpiresAt)
                        .executeUpdate());
    }

    /** Backdated EMAIL_SENT bookkeeping for the renewal spacing rules. */
    private void insertRenewalSentEvent(String candidateUuid, String deadlineKey,
                                        int renewalNumber, LocalDateTime occurredAt) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_events
                                    (event_id, event_type, candidate_uuid, actor_type, occurred_at,
                                     visibility, payload, pii_state)
                                VALUES (:id, 'EMAIL_SENT', :candidate, 'SCHEDULER', :occurred,
                                        'NORMAL', :payload, 'NONE')
                                """)
                        .setParameter("id", UUID.randomUUID().toString())
                        .setParameter("candidate", candidateUuid)
                        .setParameter("occurred", occurredAt)
                        .setParameter("payload", """
                                {"trigger":"GDPR_SWEEP","template_key":"CONSENT_RENEWAL",\
                                "renewal_number":%d,"retention_deadline":"%s"}"""
                                .formatted(renewalNumber, deadlineKey))
                        .executeUpdate());
    }

    private String mint(String candidateUuid) {
        RecruitmentCandidate candidate = reload(candidateUuid);
        return consentService.mintToken(candidateUuid, candidate.getRetentionDeadline()).token();
    }

    private RecruitmentCandidate reload(String candidateUuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                RecruitmentCandidate.findById(candidateUuid));
    }

    private RecruitmentEvent lastEventOfType(String candidateUuid, RecruitmentEventType type) {
        return QuarkusTransaction.requiringNew().call(() -> RecruitmentEvent
                .<RecruitmentEvent>find("candidateUuid = ?1 and eventType = ?2 ORDER BY seq DESC",
                        candidateUuid, type)
                .firstResult());
    }

    private long mailCount(String candidateUuid) {
        RecruitmentCandidate candidate = reload(candidateUuid);
        if (candidate.getEmail() == null) {
            return 0;
        }
        return QuarkusTransaction.requiringNew().call(() ->
                ((Number) em.createNativeQuery("SELECT COUNT(*) FROM mail WHERE mail = :to")
                        .setParameter("to", candidate.getEmail())
                        .getSingleResult()).longValue());
    }

    private void setFlag(String value) {
        List<?> current = em.createNativeQuery(
                        "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                .setParameter("key", FLAG).getResultList();
        if (previousFlagValue == null && !current.isEmpty()) {
            previousFlagValue = (String) current.get(0);
        }
        if (current.isEmpty()) {
            em.createNativeQuery("""
                            INSERT INTO app_settings (setting_key, setting_value, category)
                            VALUES (:key, :value, 'recruitment')
                            """)
                    .setParameter("key", FLAG)
                    .setParameter("value", value).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("key", FLAG)
                    .setParameter("value", value).executeUpdate();
        }
    }

    private void restoreFlag() {
        if (previousFlagValue == null) {
            em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("key", FLAG)
                    .setParameter("value", previousFlagValue).executeUpdate();
        }
        previousFlagValue = null;
    }
}
