package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.PublicApplySubmission;
import dk.trustworks.intranet.recruitmentservice.dto.PublicApplySubmission.UploadedDocument;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentConsentStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

/**
 * P5 DoD at the service layer:
 * <ul>
 *   <li>position submission builds the full aggregate atomically:
 *       candidate + application (first stage) + answers + documents +
 *       consent + events, all {@code actor_type=CANDIDATE};</li>
 *   <li>dedupe reuses an existing non-terminal candidate by exact email
 *       WITHOUT overwriting any stored field, flagging
 *       {@code dedupe_review} on the application event;</li>
 *   <li>a duplicate open application stores documents only (with
 *       {@code reason=DUPLICATE_PUBLIC_SUBMISSION}) — no second
 *       application, no answer changes;</li>
 *   <li>a pooled candidate reused via dedupe is auto-unpooled with the
 *       retention clock reset;</li>
 *   <li>pool consent is GRANTED only when ticked, expiring
 *       {@code granted_at} + 12 months;</li>
 *   <li>{@code assertNoPiiInPayload} green on every appended event
 *       ({@code PII_SENTINEL} in all personal fixture fields).</li>
 * </ul>
 * Storage is mocked — no S3 round trips; file-metadata behavior is
 * asserted through the mock + the {@code DOCUMENT_UPLOADED} events.
 */
@QuarkusTest
class PublicApplyServiceIntegrationTest {

    private static final byte[] PDF_BYTES = {
            0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a, 0x25, (byte) 0xc4};

    @Inject
    PublicApplyService publicApplyService;

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String practiceUuid;
    private String inactivePracticeUuid;
    private String positionUuid;
    private String slug;

    private final List<String> trackedEmails = new ArrayList<>();
    private final List<String> fixtureCandidateUuids = new ArrayList<>();

    @BeforeEach
    void seedFixtures() {
        practiceUuid = UUID.randomUUID().toString();
        inactivePracticeUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        slug = "p5-svc-" + UUID.randomUUID().toString().substring(0, 8);

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceUuid, true);
            insertPractice(inactivePracticeUuid, false);
            insertPosition(positionUuid, slug, "OPEN");
        });

        when(storageService.storeApplicationDocument(any(byte[].class), anyString(), any(UUID.class)))
                .thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            List<String> candidates = allCandidateUuids();
            if (!candidates.isEmpty()) {
                em.createNativeQuery("""
                                DELETE FROM recruitment_application_answers
                                WHERE candidate_uuid IN :c
                                   OR application_uuid IN (SELECT uuid FROM recruitment_applications
                                                           WHERE candidate_uuid IN :c)
                                """)
                        .setParameter("c", candidates).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                        .setParameter("c", candidates).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid IN :c")
                        .setParameter("c", candidates).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid IN :c")
                        .setParameter("c", candidates).executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                        .setParameter("c", candidates).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid = :p")
                    .setParameter("p", positionUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid IN :pr")
                    .setParameter("pr", List.of(practiceUuid, inactivePracticeUuid)).executeUpdate();
        });
        trackedEmails.clear();
        fixtureCandidateUuids.clear();
    }

    // ---- Position form: new candidate ------------------------------------------

    @Test
    void positionSubmission_newCandidate_buildsTheFullAggregate() {
        String email = uniqueEmail();
        submitForPosition(submission(email, true, null));

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertEquals(PublicApplyService.PUBLIC_FORM_CREATOR, candidate.getCreatedByUseruuid());
        assertEquals(CandidateStatus.ACTIVE, candidate.getStatus());
        assertEquals(CandidateSource.WEBSITE, candidate.getSource());
        assertEquals(CandidateLawfulBasis.LEGITIMATE_INTEREST, candidate.getLawfulBasis());
        assertNull(candidate.getArt14Required(), "P5 entry channels are Art. 13 — no Art. 14 bookkeeping");
        assertEquals(CandidateEducationLevel.MASTER, candidate.getEducationLevel());
        assertEquals(CandidateExperienceLevel.SENIOR, candidate.getExperienceLevel());
        assertEquals("NETWORK", candidate.getSourceDetail().get("selfReportedSource"));
        assertTrue(String.valueOf(candidate.getSourceDetail().get("referenceName")).contains(PII_SENTINEL));

        List<RecruitmentApplication> applications = applicationsOf(candidate.getUuid());
        assertEquals(1, applications.size());
        assertEquals(RecruitmentStage.SCREENING, applications.get(0).getStage());
        assertNull(applications.get(0).getTerminal());

        List<RecruitmentApplicationAnswer> answers = answersForApplication(applications.get(0).getUuid());
        assertEquals(List.of("WHY_TRUSTWORKS", "STRENGTHS"),
                answers.stream().map(RecruitmentApplicationAnswer::getQuestionKey).toList());
        answers.forEach(a -> assertNull(a.getCandidateUuid(),
                "position-form answers are application-scoped"));

        List<RecruitmentConsent> consents = consentsOf(candidate.getUuid());
        assertEquals(1, consents.size());
        RecruitmentConsent consent = consents.get(0);
        assertEquals(RecruitmentConsentStatus.GRANTED, consent.getStatus());
        assertNull(consent.getTokenHash(), "tokens are minted by P19");
        assertNotNull(consent.getGrantedAt());
        assertEquals(0, Duration.between(
                        consent.getGrantedAt().plusMonths(PublicApplyService.POOL_CONSENT_MONTHS),
                        consent.getExpiresAt()).toMinutes(),
                "expires_at = granted_at + 12 months");

        verify(storageService, times(1))
                .storeApplicationDocument(any(byte[].class), anyString(), any(UUID.class));

        List<RecruitmentEvent> events = eventsFor(candidate.getUuid());
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED,
                        RecruitmentEventType.APPLICATION_CREATED,
                        RecruitmentEventType.DOCUMENT_UPLOADED,
                        RecruitmentEventType.CONSENT_GRANTED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        for (RecruitmentEvent event : events) {
            assertEquals(RecruitmentActorType.CANDIDATE, event.getActorType(),
                    "public intake events carry actor_type=CANDIDATE");
            assertNull(event.getActorUuid());
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        }
        RecruitmentEvent created = events.get(1);
        assertTrue(created.getPayload().contains("\"origin\":\"public_form\""));
        assertFalse(created.getPayload().contains("dedupe_review"),
                "a fresh candidate is not flagged for dedupe review");
        RecruitmentEvent document = events.get(2);
        assertTrue(document.getPayload().contains("\"kind\":\"CV\""));
        assertTrue(document.getPii().contains(PII_SENTINEL), "the filename lives in pii");
    }

    // ---- Dedupe reuse ----------------------------------------------------------

    @Test
    void dedupe_existingEmail_reusesCandidateWithoutOverwriting_flagsReview() {
        String email = uniqueEmail();
        String existing = insertCandidate(email, "ACTIVE", null);

        submitForPosition(submission(email, false, null));

        assertEquals(1, candidateCountByEmail(email), "no second candidate row");
        RecruitmentCandidate candidate = reloadCandidate(existing);
        assertEquals(PII_SENTINEL + "-Original", candidate.getFirstName(),
                "public input must never overwrite stored candidate data");
        assertEquals(PII_SENTINEL + " 999", candidate.getPhone());
        assertEquals(CandidateSource.OTHER, candidate.getSource(), "source untouched on reuse");

        List<RecruitmentApplication> applications = applicationsOf(existing);
        assertEquals(1, applications.size(), "the application attaches to the existing candidate");

        List<RecruitmentEvent> events = eventsFor(existing);
        assertEquals(List.of(RecruitmentEventType.APPLICATION_CREATED,
                        RecruitmentEventType.DOCUMENT_UPLOADED),
                events.stream().map(RecruitmentEvent::getEventType).toList(),
                "no CANDIDATE_CREATED on reuse");
        assertTrue(events.get(0).getPayload().contains("\"dedupe_review\":true"));
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void dedupe_terminalCandidate_getsAFreshCandidateRow() {
        String email = uniqueEmail();
        insertCandidate(email, "DECLINED", null);

        submitForPosition(submission(email, false, null));

        assertEquals(2, candidateCountByEmail(email),
                "a terminal candidate cannot re-enter the pipeline — a fresh row is created");
    }

    // ---- Duplicate open application ---------------------------------------------

    @Test
    void duplicateOpenApplication_storesDocumentsOnly_sameSilence() {
        String email = uniqueEmail();
        submitForPosition(submission(email, false, null));
        RecruitmentCandidate candidate = candidateByEmail(email);
        String applicationUuid = applicationsOf(candidate.getUuid()).get(0).getUuid();
        List<String> answersBefore = answerTexts(applicationUuid);

        // Second submission, same email + position, different answers — must
        // complete without error (the caller sees the same generic 201).
        PublicApplySubmission second = submission(email, false, null);
        second.answers().put("WHY_TRUSTWORKS", PII_SENTINEL + " changed my mind entirely");
        second.answers().put("DNA_MATCH", PII_SENTINEL + " a new answer");
        submitForPosition(second);

        assertEquals(1, applicationsOf(candidate.getUuid()).size(), "no second application");
        assertEquals(answersBefore, answerTexts(applicationUuid), "existing answers untouched");

        List<RecruitmentEvent> events = eventsFor(candidate.getUuid());
        long applicationCreated = events.stream()
                .filter(e -> e.getEventType() == RecruitmentEventType.APPLICATION_CREATED).count();
        assertEquals(1, applicationCreated);
        RecruitmentEvent lastDocument = events.get(events.size() - 1);
        assertEquals(RecruitmentEventType.DOCUMENT_UPLOADED, lastDocument.getEventType());
        assertTrue(lastDocument.getPayload().contains("\"reason\":\"DUPLICATE_PUBLIC_SUBMISSION\""));
        verify(storageService, times(2))
                .storeApplicationDocument(any(byte[].class), anyString(), any(UUID.class));
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Pooled candidate reuse --------------------------------------------------

    @Test
    void pooledCandidateReused_unpoolsAndResetsRetentionClock() {
        String email = uniqueEmail();
        String existing = insertCandidate(email, "POOLED", "PROSPECT");
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                UPDATE recruitment_candidates
                                SET process_ended_at = NOW(3), retention_deadline = NOW(3)
                                WHERE uuid = :c
                                """)
                        .setParameter("c", existing).executeUpdate());

        submitForPosition(submission(email, false, null));

        RecruitmentCandidate candidate = reloadCandidate(existing);
        assertEquals(CandidateStatus.ACTIVE, candidate.getStatus(), "pool → pipeline reactivates");
        assertNull(candidate.getPoolStatus());
        assertNull(candidate.getProcessEndedAt(), "a resumed process stops the retention clock");
        assertNull(candidate.getRetentionDeadline());

        List<RecruitmentEvent> events = eventsFor(existing);
        assertEquals(RecruitmentEventType.CANDIDATE_UNPOOLED, events.get(0).getEventType());
        assertEquals(RecruitmentActorType.CANDIDATE, events.get(0).getActorType(),
                "the unpool was caused by the candidate's own submission");
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Consent ----------------------------------------------------------------

    @Test
    void poolConsentUnticked_createsNoConsentAndNoEvent() {
        String email = uniqueEmail();
        submitForPosition(submission(email, false, null));

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertEquals(0, consentsOf(candidate.getUuid()).size());
        assertTrue(eventsFor(candidate.getUuid()).stream()
                        .noneMatch(e -> e.getEventType() == RecruitmentEventType.CONSENT_GRANTED),
                "no CONSENT_GRANTED without the ticked checkbox");
    }

    // ---- Unsolicited -------------------------------------------------------------

    @Test
    void unsolicited_createsCandidateOnly_withCandidateScopedAnswers() {
        String email = uniqueEmail();
        submitUnsolicited(submission(email, false, inactivePracticeUuid));

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertEquals(0, applicationsOf(candidate.getUuid()).size(),
                "unsolicited creates the candidate ONLY — triage attaches the application later");

        List<RecruitmentApplicationAnswer> answers = answersForCandidate(candidate.getUuid());
        assertEquals(List.of("WHY_TRUSTWORKS", "STRENGTHS"),
                answers.stream().map(RecruitmentApplicationAnswer::getQuestionKey).toList());
        answers.forEach(a -> assertNull(a.getApplicationUuid(),
                "unsolicited answers are candidate-scoped"));

        // A since-deactivated practice still lands (mid-flight deactivation).
        assertEquals(inactivePracticeUuid, candidate.getSourceDetail().get("desiredPracticeUuid"));
        assertEquals("P5 Inactive Practice", candidate.getSourceDetail().get("desiredPracticeName"));

        eventsFor(candidate.getUuid()).forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    @Test
    void unsolicited_garbagePracticeUuid_isDroppedButSubmissionLands() {
        String email = uniqueEmail();
        submitUnsolicited(submission(email, false, UUID.randomUUID().toString()));

        RecruitmentCandidate candidate = candidateByEmail(email);
        Map<String, Object> sourceDetail = candidate.getSourceDetail();
        assertFalse(sourceDetail != null && sourceDetail.containsKey("desiredPracticeUuid"),
                "attacker garbage never lands in source_detail");
    }

    @Test
    void unsolicited_reusedCandidate_existingAnswerKeysUntouched() {
        String email = uniqueEmail();
        String existing = insertCandidate(email, "ACTIVE", null);
        String originalText = PII_SENTINEL + " original answer";
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_application_answers
                                    (uuid, candidate_uuid, question_key, answer, created_at)
                                VALUES (:uuid, :candidate, 'WHY_TRUSTWORKS', :answer, NOW(3))
                                """)
                        .setParameter("uuid", UUID.randomUUID().toString())
                        .setParameter("candidate", existing)
                        .setParameter("answer", originalText)
                        .executeUpdate());

        submitUnsolicited(submission(email, false, null));

        List<RecruitmentApplicationAnswer> answers = answersForCandidate(existing);
        assertEquals(2, answers.size(), "WHY_TRUSTWORKS kept + STRENGTHS added");
        assertEquals(originalText, answers.stream()
                        .filter(a -> a.getQuestionKey().equals("WHY_TRUSTWORKS"))
                        .findFirst().orElseThrow().getAnswer(),
                "public input never modifies existing answers");
        assertEquals(1, candidateCountByEmail(email), "no second candidate row");
    }

    // ---- Fixtures & helpers ------------------------------------------------------

    /** A valid submission with {@code PII_SENTINEL} in every personal field. */
    private PublicApplySubmission submission(String email, boolean poolConsent,
                                             String desiredPracticeUuid) {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("WHY_TRUSTWORKS", PII_SENTINEL + " fordi opgaverne er spændende");
        answers.put("STRENGTHS", PII_SENTINEL + " struktur og kundetække");
        return new PublicApplySubmission(
                PII_SENTINEL + "-Anna",
                PII_SENTINEL + "-Hansen",
                email,
                PII_SENTINEL + " 999",
                "https://www.linkedin.com/in/PII_SENTINEL-anna-" + UUID.randomUUID().toString().substring(0, 8),
                CandidateEducationLevel.MASTER,
                null,
                CandidateExperienceLevel.SENIOR,
                CandidateSource.WEBSITE,
                "NETWORK",
                PII_SENTINEL + " Jane Reference",
                answers,
                poolConsent,
                new UploadedDocument(PDF_BYTES, PII_SENTINEL + "-cv.pdf", "cv.pdf", "application/pdf"),
                null,
                desiredPracticeUuid);
    }

    private void submitForPosition(PublicApplySubmission submission) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            publicApplyService.submitForPosition(slug, submission);
        });
    }

    private void submitUnsolicited(PublicApplySubmission submission) {
        QuarkusTransaction.requiringNew().run(() -> {
            em.clear();
            publicApplyService.submitUnsolicited(submission);
        });
    }

    private String uniqueEmail() {
        String email = "PII_SENTINEL.p5svc+" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        trackedEmails.add(email);
        return email;
    }

    private String insertCandidate(String email, String status, String poolStatus) {
        String uuid = UUID.randomUUID().toString();
        fixtureCandidateUuids.add(uuid);
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_candidates
                                    (uuid, first_name, last_name, email, phone, status, pool_status,
                                     source, created_by_useruuid, created_at, updated_at)
                                VALUES (:uuid, :first, 'Fixture', :email, :phone, :status, :pool,
                                        'OTHER', :actor, NOW(), NOW())
                                """)
                        .setParameter("uuid", uuid)
                        .setParameter("first", PII_SENTINEL + "-Original")
                        .setParameter("email", email)
                        .setParameter("phone", PII_SENTINEL + " 999")
                        .setParameter("status", status)
                        .setParameter("pool", poolStatus)
                        .setParameter("actor", UUID.randomUUID().toString())
                        .executeUpdate());
        return uuid;
    }

    private void insertPractice(String uuid, boolean active) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, :name, :active, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "P" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .setParameter("name", active ? "P5 Svc Practice" : "P5 Inactive Practice")
                .setParameter("active", active)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String publicSlug, String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, public_slug, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, 'P5 Consultant', 'PRACTICE_TEAM', :practice, :slug,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', :status, NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("practice", practiceUuid)
                .setParameter("slug", publicSlug)
                .setParameter("status", status)
                .executeUpdate();
    }

    private List<String> allCandidateUuids() {
        List<String> uuids = new ArrayList<>(fixtureCandidateUuids);
        if (!trackedEmails.isEmpty()) {
            @SuppressWarnings("unchecked")
            List<String> created = em.createNativeQuery(
                            "SELECT uuid FROM recruitment_candidates WHERE email IN :emails")
                    .setParameter("emails", trackedEmails).getResultList();
            for (String uuid : created) {
                if (!uuids.contains(uuid)) {
                    uuids.add(uuid);
                }
            }
        }
        return uuids;
    }

    private RecruitmentCandidate candidateByEmail(String email) {
        em.clear();
        RecruitmentCandidate candidate = RecruitmentCandidate
                .<RecruitmentCandidate>find("email", email).firstResult();
        assertNotNull(candidate, "expected a candidate for " + email);
        return candidate;
    }

    private long candidateCountByEmail(String email) {
        em.clear();
        return RecruitmentCandidate.count("email", email);
    }

    private RecruitmentCandidate reloadCandidate(String uuid) {
        em.clear();
        return RecruitmentCandidate.findById(uuid);
    }

    private List<RecruitmentApplication> applicationsOf(String candidateUuid) {
        em.clear();
        return RecruitmentApplication.list("candidateUuid", candidateUuid);
    }

    private List<RecruitmentApplicationAnswer> answersForApplication(String applicationUuid) {
        em.clear();
        return RecruitmentApplicationAnswer.list("applicationUuid = ?1 order by createdAt, questionKey desc",
                applicationUuid);
    }

    private List<RecruitmentApplicationAnswer> answersForCandidate(String candidateUuid) {
        em.clear();
        return RecruitmentApplicationAnswer.list("candidateUuid = ?1 order by createdAt, questionKey desc",
                candidateUuid);
    }

    private List<String> answerTexts(String applicationUuid) {
        return answersForApplication(applicationUuid).stream()
                .map(a -> a.getQuestionKey() + "=" + a.getAnswer())
                .toList();
    }

    private List<RecruitmentConsent> consentsOf(String candidateUuid) {
        em.clear();
        return RecruitmentConsent.list("candidateUuid", candidateUuid);
    }

    private List<RecruitmentEvent> eventsFor(String candidateUuid) {
        em.clear();
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }
}
