package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentActorType;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST contract tests for the P5 public surface {@code /apply/*}.
 * Deliberately NO {@code @TestSecurity} — every request is anonymous,
 * proving {@code @PermitAll} end-to-end.
 * <ul>
 *   <li>uniform, byte-identical 404 for unknown slug / CLOSED position /
 *       disabled flag;</li>
 *   <li>code-only 400 bodies that never echo submitted values;</li>
 *   <li>magic-byte vs Content-Type pairing on uploads;</li>
 *   <li>the same generic 201 for first and duplicate submissions;</li>
 *   <li>unsolicited → candidate only, no application.</li>
 * </ul>
 * Storage is mocked ({@code @InjectMock RecruitmentS3StorageService}) —
 * no S3 round trips. Each POST carries a unique {@code X-Forwarded-For}
 * so the per-IP rate limiter never throttles the suite (the window logic
 * itself is unit-tested in {@code PublicApplyRateLimitFilterTest}).
 */
@QuarkusTest
class PublicApplyResourceApiTest {

    private static final String FLAG = "recruitment.pipeline.enabled";
    private static final String NOT_FOUND_BODY = "{\"error\":\"NOT_FOUND\"}";
    private static final AtomicInteger IP_COUNTER = new AtomicInteger(1);

    private static final byte[] PDF_BYTES = {
            0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x37, 0x0a, 0x25, (byte) 0xc4};
    private static final byte[] JPEG_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0x00, 0x10, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d};

    @Inject
    EntityManager em;

    @InjectMock
    RecruitmentS3StorageService storageService;

    private String practiceUuid;
    private String inactivePracticeUuid;
    private String positionUuid;
    private String closedPositionUuid;
    private String slug;
    private String closedSlug;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        inactivePracticeUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        closedPositionUuid = UUID.randomUUID().toString();
        slug = "p5-api-" + UUID.randomUUID().toString().substring(0, 8);
        closedSlug = "p5-api-closed-" + UUID.randomUUID().toString().substring(0, 8);

        QuarkusTransaction.requiringNew().run(() -> {
            insertPractice(practiceUuid, "P5 Api Practice", true);
            insertPractice(inactivePracticeUuid, "P5 Api Inactive", false);
            insertPosition(positionUuid, slug, "OPEN");
            insertPosition(closedPositionUuid, closedSlug, "CLOSED");

            List<?> current = em.createNativeQuery(
                            "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", FLAG).getResultList();
            previousFlagValue = current.isEmpty() ? null : (String) current.get(0);
            if (previousFlagValue == null) {
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, 'true', 'recruitment')
                                """)
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = 'true' WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            }
        });

        when(storageService.storeApplicationDocument(any(byte[].class), anyString(), any(UUID.class)))
                .thenAnswer(inv -> UUID.randomUUID().toString());
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            @SuppressWarnings("unchecked")
            List<String> candidates = em.createNativeQuery(
                            "SELECT uuid FROM recruitment_candidates WHERE email LIKE 'PII_SENTINEL.p5api+%'")
                    .getResultList();
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
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", List.of(positionUuid, closedPositionUuid)).executeUpdate();
            em.createNativeQuery("DELETE FROM practice WHERE uuid IN :pr")
                    .setParameter("pr", List.of(practiceUuid, inactivePracticeUuid)).executeUpdate();
            if (previousFlagValue == null) {
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", previousFlagValue)
                        .setParameter("key", FLAG).executeUpdate();
            }
        });
    }

    // ---- GET /apply/{slug} -----------------------------------------------------

    @Test
    void getPositionForm_returnsTitlePracticeNameAndQuestions() {
        given()
                .when().get("/apply/" + slug)
                .then().statusCode(200)
                .body("title", equalTo("P5 Api Consultant"))
                .body("practiceName", equalTo("P5 Api Practice"))
                .body("questions.size()", equalTo(4))
                .body("questions[0].key", equalTo("WHY_TRUSTWORKS"))
                .body("questions[0].label", equalTo("Hvorfor Trustworks?"))
                .body("questions[0].required", equalTo(false))
                .body("questions.key", hasItem("STRENGTHS"));
    }

    @Test
    void unknownSlug_closedPosition_flagOff_areByteIdentical404s() {
        String unknownBody = given()
                .when().get("/apply/no-such-" + UUID.randomUUID().toString().substring(0, 8))
                .then().statusCode(404).extract().asString();
        String closedBody = given()
                .when().get("/apply/" + closedSlug)
                .then().statusCode(404).extract().asString();

        setFlag("false");
        String flagOffBody = given()
                .when().get("/apply/" + slug)
                .then().statusCode(404).extract().asString();
        setFlag("true");

        assertEquals(NOT_FOUND_BODY, unknownBody);
        assertEquals(unknownBody, closedBody, "closed position must be indistinguishable from unknown");
        assertEquals(unknownBody, flagOffBody, "flag off must be indistinguishable from unknown");
    }

    // ---- GET /apply/unsolicited --------------------------------------------------

    @Test
    void getUnsolicitedForm_returnsQuestionsAndActivePracticesOnly() {
        given()
                .when().get("/apply/unsolicited")
                .then().statusCode(200)
                .body("questions.size()", equalTo(4))
                .body("practices.uuid", hasItem(practiceUuid))
                .body("practices.uuid", not(hasItem(inactivePracticeUuid)))
                .body("practices.find { it.uuid == '" + practiceUuid + "' }.name",
                        equalTo("P5 Api Practice"));
    }

    // ---- POST /apply/{slug} — happy path -----------------------------------------

    @Test
    void postPosition_happyPath_creates201AndTheFullAggregate() throws IOException {
        String email = uniqueEmail();
        baseForm(email)
                .multiPart("phone", PII_SENTINEL + " 12345678")
                .multiPart("educationLevel", "MASTER")
                .multiPart("experienceLevel", "SENIOR")
                .multiPart("channel", "LINKEDIN_AD")
                .multiPart("selfReportedSource", "CONFERENCE")
                .multiPart("sourceFollowUp", PII_SENTINEL + " IT-dagen 2026")
                .multiPart("answer_STRENGTHS", PII_SENTINEL + " struktur")
                .multiPart("poolConsent", "true")
                .multiPart("coverLetter", tempFile(PNG_BYTES, "letter.png"), "image/png")
                .when().post("/apply/" + slug)
                .then().statusCode(201)
                .body("status", equalTo("RECEIVED"));

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertEquals("LINKEDIN_AD", candidate.getSource().name());
        assertEquals("public-form", candidate.getCreatedByUseruuid());

        em.clear();
        List<RecruitmentApplication> applications =
                RecruitmentApplication.list("candidateUuid", candidate.getUuid());
        assertEquals(1, applications.size());

        List<RecruitmentApplicationAnswer> answers = RecruitmentApplicationAnswer
                .list("applicationUuid", applications.get(0).getUuid());
        assertEquals(2, answers.size(), "WHY_TRUSTWORKS + STRENGTHS");

        List<RecruitmentConsent> consents =
                RecruitmentConsent.list("candidateUuid", candidate.getUuid());
        assertEquals(1, consents.size());
        assertNotNull(consents.get(0).getGrantedAt());

        verify(storageService, times(2))
                .storeApplicationDocument(any(byte[].class), anyString(), any(UUID.class));

        List<RecruitmentEvent> events = RecruitmentEvent
                .list("candidateUuid = ?1 order by seq", candidate.getUuid());
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED,
                        RecruitmentEventType.APPLICATION_CREATED,
                        RecruitmentEventType.DOCUMENT_UPLOADED,
                        RecruitmentEventType.DOCUMENT_UPLOADED,
                        RecruitmentEventType.CONSENT_GRANTED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        for (RecruitmentEvent event : events) {
            assertEquals(RecruitmentActorType.CANDIDATE, event.getActorType());
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        }
    }

    @Test
    void postPosition_duplicateSubmission_answersWithTheSameGeneric201() throws IOException {
        String email = uniqueEmail();
        String first = baseForm(email)
                .when().post("/apply/" + slug)
                .then().statusCode(201).extract().asString();
        String second = baseForm(email)
                .when().post("/apply/" + slug)
                .then().statusCode(201).extract().asString();
        assertEquals(first, second,
                "an attacker must not learn that the email already applied");

        RecruitmentCandidate candidate = candidateByEmail(email);
        em.clear();
        assertEquals(1, RecruitmentApplication.count("candidateUuid", candidate.getUuid()),
                "no second application");
    }

    // ---- POST /apply/{slug} — validation -----------------------------------------

    @Test
    void postPosition_missingFirstName_is400MissingRequiredField() throws IOException {
        given()
                .header("X-Forwarded-For", nextIp())
                .multiPart("lastName", PII_SENTINEL + "-Hansen")
                .multiPart("email", uniqueEmail())
                .multiPart("cv", tempFile(PDF_BYTES, "cv.pdf"), "application/pdf")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"MISSING_REQUIRED_FIELD\"}"));
    }

    @Test
    void postPosition_invalidEmail_is400InvalidEmail() throws IOException {
        given()
                .header("X-Forwarded-For", nextIp())
                .multiPart("firstName", PII_SENTINEL + "-Anna")
                .multiPart("lastName", PII_SENTINEL + "-Hansen")
                .multiPart("email", "not-an-email")
                .multiPart("cv", tempFile(PDF_BYTES, "cv.pdf"), "application/pdf")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"INVALID_EMAIL\"}"));
    }

    @Test
    void postPosition_missingCv_is400FileRequired() {
        given()
                .header("X-Forwarded-For", nextIp())
                .multiPart("firstName", PII_SENTINEL + "-Anna")
                .multiPart("lastName", PII_SENTINEL + "-Hansen")
                .multiPart("email", uniqueEmail())
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"FILE_REQUIRED\"}"));
    }

    @Test
    void postPosition_oversizeCv_is400FileTooLarge() throws IOException {
        byte[] huge = new byte[10 * 1024 * 1024 + 1];
        huge[0] = 0x25; huge[1] = 0x50; huge[2] = 0x44; huge[3] = 0x46;
        given()
                .header("X-Forwarded-For", nextIp())
                .multiPart("firstName", PII_SENTINEL + "-Anna")
                .multiPart("lastName", PII_SENTINEL + "-Hansen")
                .multiPart("email", uniqueEmail())
                .multiPart("cv", tempFile(huge, "huge.pdf"), "application/pdf")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"FILE_TOO_LARGE\"}"));
    }

    @Test
    void postPosition_pdfBytesWithImageMime_is400UnsupportedMediaType() throws IOException {
        baseFormWithoutCv(uniqueEmail())
                .multiPart("cv", tempFile(PDF_BYTES, "spoof.jpg"), "image/jpeg")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"UNSUPPORTED_MEDIA_TYPE\"}"));
    }

    @Test
    void postPosition_imageBytesWithPdfMime_is400UnsupportedMediaType() throws IOException {
        baseFormWithoutCv(uniqueEmail())
                .multiPart("cv", tempFile(JPEG_BYTES, "spoof.pdf"), "application/pdf")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"UNSUPPORTED_MEDIA_TYPE\"}"));
    }

    @Test
    void postPosition_answerTooLong_is400AnswerTooLong() throws IOException {
        baseForm(uniqueEmail())
                .multiPart("answer_BEST_TASKS", "a".repeat(10_001))
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"ANSWER_TOO_LONG\"}"));
    }

    @Test
    void postPosition_garbageEducationLevel_is400InvalidField() throws IOException {
        baseForm(uniqueEmail())
                .multiPart("educationLevel", "DROPOUT'; DROP TABLE--")
                .when().post("/apply/" + slug)
                .then().statusCode(400)
                .body(equalTo("{\"error\":\"INVALID_FIELD\"}"));
    }

    // ---- Rate-limiter authenticated-branch guard -----------------------------------

    /**
     * The identity-aware rate limiter trusts {@code X-Client-IP} only from
     * authenticated callers. This pins the assumption that trust rests
     * on: a garbage bearer token fails proactive authentication
     * with 401 BEFORE the request reaches the filter/resource — an
     * attacker cannot forge their way into the authenticated branch by
     * pairing a fake token with a spoofed {@code X-Client-IP}.
     */
    @Test
    void postPosition_garbageBearerToken_failsAuthenticationWith401() throws IOException {
        baseForm(uniqueEmail())
                .header("Authorization", "Bearer not-a-real-token")
                .header("X-Client-IP", "203.0.113.77")
                .when().post("/apply/" + slug)
                .then().statusCode(401);
    }

    // ---- POST /apply/{slug} — silence & flag --------------------------------------

    @Test
    void postPosition_unknownSlug_is404EvenWithAValidBody() throws IOException {
        baseForm(uniqueEmail())
                .when().post("/apply/no-such-" + UUID.randomUUID().toString().substring(0, 8))
                .then().statusCode(404)
                .body(equalTo(NOT_FOUND_BODY));
    }

    @Test
    void postPosition_flagOff_is404() throws IOException {
        setFlag("false");
        try {
            baseForm(uniqueEmail())
                    .when().post("/apply/" + slug)
                    .then().statusCode(404)
                    .body(equalTo(NOT_FOUND_BODY));
        } finally {
            setFlag("true");
        }
    }

    // ---- POST /apply/unsolicited ---------------------------------------------------

    @Test
    void postUnsolicited_happyPath_createsCandidateOnly() throws IOException {
        String email = uniqueEmail();
        baseForm(email)
                .multiPart("desiredPracticeUuid", practiceUuid)
                .when().post("/apply/unsolicited")
                .then().statusCode(201)
                .body("status", equalTo("RECEIVED"));

        RecruitmentCandidate candidate = candidateByEmail(email);
        em.clear();
        assertEquals(0, RecruitmentApplication.count("candidateUuid", candidate.getUuid()),
                "unsolicited creates the candidate ONLY — no application");
        List<RecruitmentApplicationAnswer> answers = RecruitmentApplicationAnswer
                .list("candidateUuid", candidate.getUuid());
        assertEquals(1, answers.size(), "the WHY_TRUSTWORKS answer, candidate-scoped");
        assertNull(answers.get(0).getApplicationUuid());
        assertEquals(practiceUuid, candidate.getSourceDetail().get("desiredPracticeUuid"));
        assertEquals("P5 Api Practice", candidate.getSourceDetail().get("desiredPracticeName"));
    }

    @Test
    void postUnsolicited_deactivatedPractice_stillLands() throws IOException {
        String email = uniqueEmail();
        baseForm(email)
                .multiPart("desiredPracticeUuid", inactivePracticeUuid)
                .when().post("/apply/unsolicited")
                .then().statusCode(201);

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertEquals(inactivePracticeUuid, candidate.getSourceDetail().get("desiredPracticeUuid"),
                "a mid-flight deactivation must not lose the preference");
    }

    @Test
    void postUnsolicited_garbagePracticeUuid_isDroppedButAccepted() throws IOException {
        String email = uniqueEmail();
        baseForm(email)
                .multiPart("desiredPracticeUuid", "'; DROP TABLE practice; --")
                .when().post("/apply/unsolicited")
                .then().statusCode(201);

        RecruitmentCandidate candidate = candidateByEmail(email);
        assertTrue(candidate.getSourceDetail() == null
                        || !candidate.getSourceDetail().containsKey("desiredPracticeUuid"),
                "attacker garbage never lands in source_detail");
    }

    @Test
    void unsolicitedRoutes_flagOff_are404() {
        setFlag("false");
        try {
            given().when().get("/apply/unsolicited")
                    .then().statusCode(404)
                    .body(equalTo(NOT_FOUND_BODY));
        } finally {
            setFlag("true");
        }
    }

    // ---- Fixtures & helpers --------------------------------------------------------

    /** Minimal VALID form: names, email, one answer, a PDF CV — plus a unique source IP. */
    private RequestSpecification baseForm(String email) throws IOException {
        return baseFormWithoutCv(email)
                .multiPart("cv", tempFile(PDF_BYTES, "cv.pdf"), "application/pdf");
    }

    private RequestSpecification baseFormWithoutCv(String email) {
        return given()
                .header("X-Forwarded-For", nextIp())
                .multiPart("firstName", PII_SENTINEL + "-Anna")
                .multiPart("lastName", PII_SENTINEL + "-Hansen")
                .multiPart("email", email)
                .multiPart("answer_WHY_TRUSTWORKS", PII_SENTINEL + " fordi arbejdet er spændende");
    }

    /**
     * Unique per-request source IP so the per-IP rate limiter never
     * throttles the suite (the filter reads the rightmost X-Forwarded-For
     * entry — unique here for the first 62 500 requests).
     */
    private static String nextIp() {
        int n = IP_COUNTER.getAndIncrement();
        return "203.0." + (n / 250 % 250) + "." + (n % 250 + 1);
    }

    private static String uniqueEmail() {
        return "PII_SENTINEL.p5api+" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    private RecruitmentCandidate candidateByEmail(String email) {
        em.clear();
        RecruitmentCandidate candidate = RecruitmentCandidate
                .<RecruitmentCandidate>find("email", email).firstResult();
        assertNotNull(candidate, "expected a candidate for " + email);
        return candidate;
    }

    private void setFlag(String value) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", value)
                        .setParameter("key", FLAG).executeUpdate());
    }

    private void insertPractice(String uuid, String name, boolean active) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, :name, :active, 999, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "A" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .setParameter("name", name)
                .setParameter("active", active)
                .executeUpdate();
    }

    private void insertPosition(String uuid, String publicSlug, String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, public_slug, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, 'P5 Api Consultant', 'PRACTICE_TEAM', :practice, :slug,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', :status, NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("practice", practiceUuid)
                .setParameter("slug", publicSlug)
                .setParameter("status", status)
                .executeUpdate();
    }

    private static File tempFile(byte[] bytes, String filename) throws IOException {
        File file = File.createTempFile("p5-apply-", "-" + filename);
        file.deleteOnExit();
        Files.write(file.toPath(), bytes);
        return file;
    }
}
