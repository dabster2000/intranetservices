package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8 DoD (timeline): a full-journey candidate fixture — public form →
 * dedupe-flagged application → stage move → notes — rendered in {@code seq}
 * DESC order with actor attribution (CANDIDATE actors carry no name, USER
 * actors resolve to "First Last"); CIRCLE events filtered per viewer;
 * private notes visible only to author / recruiter tier / admin; salary
 * notes carry {@code pii} only for the comp tier (others get the event
 * with {@code piiRedacted=true}); exact {@code beforeSeq}+{@code hasMore}
 * pagination; parsed-JSON payloads on the wire.
 */
@QuarkusTest
class RecruitmentCandidateTimelineApiTest {

    private static final String EVENTS = "/recruitment/candidates/{uuid}/events";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamA;

    private String recruiter;      // HR — recruiter tier + comp tier
    private String teamlead;       // LEADER of teamA — involved, comp tier, private-note author
    private String hiringOwner;    // named TEAMLEAD owner — dossier reader, NOT note tier
    private String assistant;      // practice-scoped assistant — never a dossier reader
    private String practiceLead;   // current practice lead — involved, NOT comp tier
    private String circleHr;       // HR + circle member on the partner position
    private String adminUser;

    private String journeyPosition;
    private String partnerPosition;
    private String candidate;
    private String applicationUuid;

    private long seqCreated;
    private long seqDocument;
    private long seqApplication;
    private long seqStageMove;
    private long seqPublicNote;
    private long seqPrivateNote;
    private long seqSalaryNote;
    private long seqCircleEvent;

    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamA = UUID.randomUUID().toString();
        recruiter = UUID.randomUUID().toString();
        teamlead = UUID.randomUUID().toString();
        hiringOwner = UUID.randomUUID().toString();
        assistant = UUID.randomUUID().toString();
        practiceLead = UUID.randomUUID().toString();
        circleHr = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        journeyPosition = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        candidate = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiter, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, teamlead, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, hiringOwner, "Owen", "Owner");
            P8ProfileFixtures.insertUser(em, assistant, "Rikke", "Assistant");
            P8ProfileFixtures.insertUser(em, practiceLead, "Pia", "Lead");
            P8ProfileFixtures.insertUser(em, circleHr, "Cirkel", "Recruiter");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertRole(em, recruiter, "HR");
            P8ProfileFixtures.insertRole(em, hiringOwner, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistant, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, circleHr, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            em.createNativeQuery("UPDATE user SET practice_uuid = :practice WHERE uuid = :user")
                    .setParameter("practice", practiceUuid)
                    .setParameter("user", assistant)
                    .executeUpdate();
            P8ProfileFixtures.insertPracticeLead(em, practiceLead, practiceUuid);
            P8ProfileFixtures.insertTeamLeader(em, teamlead, teamA);

            P8ProfileFixtures.insertPosition(em, journeyPosition, "Journey Consultant",
                    "PRACTICE_TEAM", practiceUuid, teamA, hiringOwner);
            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);
            P8ProfileFixtures.insertCircleMember(em, partnerPosition, circleHr);

            P8ProfileFixtures.insertCandidate(em, candidate,
                    "PII_SENTINEL Jonna", "PII_SENTINEL Journey", "ACTIVE", null, null, recruiter);
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid, candidate,
                    journeyPosition, "INTERVIEW_1");

            // The journey, oldest first (seq ascends with insertion order).
            seqCreated = P8ProfileFixtures.insertEvent(em, "CANDIDATE_CREATED", candidate,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"source\":\"WEBSITE\",\"origin\":\"public_form\"}",
                    "{\"first_name\":\"PII_SENTINEL Jonna\",\"last_name\":\"PII_SENTINEL Journey\"}");
            seqDocument = P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidate,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + UUID.randomUUID() + "\",\"kind\":\"CV\","
                            + "\"origin\":\"public_form\"}",
                    "{\"filename\":\"PII_SENTINEL cv original.pdf\"}");
            seqApplication = P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidate,
                    applicationUuid, journeyPosition, "CANDIDATE", null, "NORMAL",
                    "{\"origin\":\"public_form\",\"stage\":\"SCREENING\",\"dedupe_review\":true}",
                    null);
            seqStageMove = P8ProfileFixtures.insertEvent(em, "APPLICATION_STAGE_CHANGED", candidate,
                    applicationUuid, journeyPosition, "USER", recruiter, "NORMAL",
                    "{\"from\":\"SCREENING\",\"to\":\"INTERVIEW_1\",\"direction\":\"FORWARD\"}",
                    null);
            seqPublicNote = P8ProfileFixtures.insertEvent(em, "NOTE_ADDED", candidate,
                    null, null, "USER", recruiter, "NORMAL",
                    "{\"private\":false}",
                    "{\"text\":\"PII_SENTINEL great first call\"}");
            seqPrivateNote = P8ProfileFixtures.insertEvent(em, "NOTE_ADDED", candidate,
                    null, null, "USER", teamlead, "NORMAL",
                    "{\"private\":true}",
                    "{\"text\":\"PII_SENTINEL keep between us\"}");
            seqSalaryNote = P8ProfileFixtures.insertEvent(em, "NOTE_ADDED", candidate,
                    null, null, "USER", recruiter, "NORMAL",
                    "{\"private\":false,\"field\":\"SALARY_EXPECTATION\"}",
                    "{\"text\":\"PII_SENTINEL 55.000 om måneden\"}");
            seqCircleEvent = P8ProfileFixtures.insertEvent(em, "APPLICATION_CREATED", candidate,
                    UUID.randomUUID().toString(), partnerPosition, "USER", circleHr, "CIRCLE",
                    "{\"origin\":\"manual\",\"stage\":\"SCREENING\"}",
                    null);

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            DELETE FROM candidate_dossier_revisions WHERE dossier_uuid IN
                            (SELECT uuid FROM candidate_dossiers WHERE candidate_uuid = :candidate)
                            """)
                    .setParameter("candidate", candidate)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid = :candidate")
                    .setParameter("candidate", candidate)
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidate),
                    List.of(journeyPosition, partnerPosition),
                    List.of(recruiter, teamlead, hiringOwner, assistant,
                            practiceLead, circleHr, adminUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Full journey: order + actor attribution ---------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void fullJourney_rendersSeqDescending_withActorAndPositionAttribution() {
        given().header("X-Requested-By", recruiter)
                .when().get(EVENTS, candidate)
                .then()
                .statusCode(200)
                .body("hasMore", Matchers.equalTo(false))
                // The CIRCLE event is invisible to a non-circle recruiter →
                // 7 of 8, newest first.
                .body("events", Matchers.hasSize(7))
                .body("events.eventType", Matchers.contains(
                        "NOTE_ADDED", "NOTE_ADDED", "NOTE_ADDED",
                        "APPLICATION_STAGE_CHANGED", "APPLICATION_CREATED",
                        "DOCUMENT_UPLOADED", "CANDIDATE_CREATED"))
                // Public-form steps: CANDIDATE actor, no name to resolve.
                .body("events[6].actorType", Matchers.equalTo("CANDIDATE"))
                .body("events[6].actorName", Matchers.nullValue())
                // The recruiter's stage move resolves to a display name.
                .body("events[3].actorType", Matchers.equalTo("USER"))
                .body("events[3].actorName", Matchers.equalTo("Rina Recruiter"))
                .body("events[3].positionUuid", Matchers.equalTo(journeyPosition))
                .body("events[3].positionName", Matchers.equalTo("Journey Consultant"))
                .body("events[3].applicationUuid", Matchers.equalTo(applicationUuid))
                // Payloads are parsed JSON objects, not strings.
                .body("events[4].payload.dedupe_review", Matchers.equalTo(true))
                .body("events[3].payload.from", Matchers.equalTo("SCREENING"))
                .body("events[3].payload.to", Matchers.equalTo("INTERVIEW_1"))
                // Ordinary events include pii for anyone with profile access.
                .body("events[6].pii.first_name", Matchers.equalTo("PII_SENTINEL Jonna"))
                .body("events[6].piiRedacted", Matchers.equalTo(false));
    }

    // ---- CIRCLE filtering ---------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void circleEvents_visibleOnlyToCircleMembersAndAdmin() {
        given().header("X-Requested-By", circleHr)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(8))
                .body("events[0].seq", Matchers.equalTo((int) seqCircleEvent));
        given().header("X-Requested-By", adminUser)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(8));
        given().header("X-Requested-By", recruiter)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events.seq", Matchers.not(Matchers.hasItem((int) seqCircleEvent)));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void unreadablePositionRouteFailsClosed_evenWhenProducerStampedNormal() {
        long staleNormalPartnerEvent = QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "APPLICATION_STAGE_CHANGED", candidate,
                        UUID.randomUUID().toString(), partnerPosition, "USER", circleHr,
                        "NORMAL", "{\"from\":\"SCREENING\",\"to\":\"INTERVIEW_1\"}",
                        null));

        for (String outsideCircle : List.of(recruiter, assistant)) {
            given().header("X-Requested-By", outsideCircle)
                    .when().get(EVENTS, candidate)
                    .then().statusCode(200)
                    .body("events.seq", Matchers.not(
                            Matchers.hasItem((int) staleNormalPartnerEvent)));
        }
        for (String allowed : List.of(circleHr, adminUser)) {
            given().header("X-Requested-By", allowed)
                    .when().get(EVENTS, candidate)
                    .then().statusCode(200)
                    .body("events.seq", Matchers.hasItem((int) staleNormalPartnerEvent));
        }
    }

    // ---- Private notes -------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void privateNote_onlyAuthorRecruiterTierAndAdmin_othersNeverSeeTheEvent() {
        // The hiring owner is involved (profile access) but neither author
        // nor recruiter tier — the private note is omitted entirely.
        given().header("X-Requested-By", hiringOwner)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events.seq", Matchers.not(Matchers.hasItem((int) seqPrivateNote)));
        // The author (teamlead) sees their own private note.
        given().header("X-Requested-By", teamlead)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events.seq", Matchers.hasItem((int) seqPrivateNote));
        // Recruiter tier (HR) sees it too.
        given().header("X-Requested-By", recruiter)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events.seq", Matchers.hasItem((int) seqPrivateNote));
    }

    // ---- Salary-expectation redaction ----------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void salaryNote_visibleWithPiiForCompTier_practiceLeadHasNoProfileAccess() {
        // Decision 11 removed the practice-lead route entirely; the resource
        // fails closed before event-level salary redaction is relevant.
        given().header("X-Requested-By", practiceLead)
                .when().get(EVENTS, candidate)
                .then().statusCode(404);

        // Assistant in the candidate position's practice is explicitly in the
        // pre-offer compensation tier and sees/registers salary expectations.
        Response assistantResponse = given().header("X-Requested-By", assistant)
                .when().get(EVENTS, candidate);
        assistantResponse.then().statusCode(200)
                .body("compTier", Matchers.equalTo(true));
        int assistantIndex = assistantResponse.jsonPath().getList("events.seq", Long.class)
                .indexOf(seqSalaryNote);
        assertTrue(assistantIndex >= 0, "salary note must stay on the assistant timeline");
        assertEquals("PII_SENTINEL 55.000 om måneden",
                assistantResponse.jsonPath().getString(
                        "events[" + assistantIndex + "].pii.text"));

        // Recruiter (HR): comp tier — pii present.
        given().header("X-Requested-By", recruiter)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events[0].seq", Matchers.equalTo((int) seqSalaryNote))
                .body("events[0].pii.text", Matchers.containsString("55.000"))
                .body("events[0].piiRedacted", Matchers.equalTo(false));

        // Teamlead of the position's team: comp tier via involvement.
        Response teamleadResponse = given().header("X-Requested-By", teamlead)
                .when().get(EVENTS, candidate);
        teamleadResponse.then().statusCode(200);
        int tlIndex = teamleadResponse.jsonPath().getList("events.seq", Long.class)
                .indexOf(seqSalaryNote);
        assertTrue(tlIndex >= 0);
        assertFalse((Boolean) teamleadResponse.jsonPath()
                .get("events[" + tlIndex + "].piiRedacted"));
    }

    /**
     * The envelope reports the viewer's comp tier explicitly. The profile's
     * Facts rail needs it to decide whether to offer the salary-expectation
     * affordance, and it cannot infer that from the events: a candidate with
     * no salary note yet carries no redacted event to read the answer off.
     * It must agree with the per-event redaction on the same response.
     */
    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void compTier_reportedOnTheEnvelope_matchingThePerEventRedaction() {
        given().header("X-Requested-By", recruiter)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("compTier", Matchers.equalTo(true));

        given().header("X-Requested-By", teamlead)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("compTier", Matchers.equalTo(true));

        given().header("X-Requested-By", adminUser)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("compTier", Matchers.equalTo(true));

        given().header("X-Requested-By", assistant)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("compTier", Matchers.equalTo(true));

        // Practice leadership alone is no longer recruitment access.
        given().header("X-Requested-By", practiceLead)
                .when().get(EVENTS, candidate)
                .then().statusCode(404);
    }

    /**
     * The empty-timeline path returns before any event filtering runs — the
     * flag must still be resolved there, or the first salary expectation on a
     * brand-new candidate could never be recorded from the profile.
     */
    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void compTier_resolvedEvenWhenThePageIsEmpty() {
        // A cursor below the first seq yields an empty page for any viewer.
        given().header("X-Requested-By", recruiter)
                .queryParam("beforeSeq", 1)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(0))
                .body("hasMore", Matchers.equalTo(false))
                .body("compTier", Matchers.equalTo(true));

        given().header("X-Requested-By", practiceLead)
                .queryParam("beforeSeq", 1)
                .when().get(EVENTS, candidate)
                .then().statusCode(404);

        given().header("X-Requested-By", assistant)
                .queryParam("beforeSeq", 1)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(0))
                .body("compTier", Matchers.equalTo(true));
    }

    // ---- Offer-dossier boundary ---------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void dossierTimelineFacts_hiddenFromAssistant_visibleToEligibleTeamleadAndHr() {
        DossierBoundaryEvents inserted = insertDossierBoundaryEvents();

        Response assistantResponse = given().header("X-Requested-By", assistant)
                .when().get(EVENTS, candidate);
        assistantResponse.then().statusCode(200);
        List<Long> assistantSeqs = assistantResponse.jsonPath().getList("events.seq", Long.class);

        assertFalse(assistantSeqs.contains(inserted.dossierCreated()));
        assertFalse(assistantSeqs.contains(inserted.signingCompleted()));
        assertFalse(assistantSeqs.contains(inserted.contractDocument()));
        assertFalse(assistantSeqs.contains(inserted.contractKindChanged()));
        assertFalse(assistantSeqs.contains(inserted.flowLinkedDocument()));
        assertFalse(assistantSeqs.contains(inserted.dossierOnlyUpdate()));
        assertFalse(assistantSeqs.contains(inserted.recordCheckDrawn()));
        assertFalse(assistantSeqs.contains(inserted.recordCheckOutcome()));

        int offerIndex = assistantSeqs.indexOf(inserted.offerOpened());
        assertTrue(offerIndex >= 0, "OFFER_OPENED remains ordinary stage progress");
        assertEquals("INTERVIEW_1",
                assistantResponse.jsonPath().getString(
                        "events[" + offerIndex + "].payload.from_stage"));
        assertNull(assistantResponse.jsonPath().get(
                "events[" + offerIndex + "].payload.dossier_linked"));
        assertNull(assistantResponse.jsonPath().get(
                "events[" + offerIndex + "].payload.dossier_uuid"));

        int mixedIndex = assistantSeqs.indexOf(inserted.mixedUpdate());
        assertTrue(mixedIndex >= 0, "ordinary fields keep a mixed update visible");
        assertEquals(List.of("phone"), assistantResponse.jsonPath().getList(
                "events[" + mixedIndex + "].payload.changed_fields", String.class));
        assertNull(assistantResponse.jsonPath().get(
                "events[" + mixedIndex + "].payload.target_company_uuid"));
        assertNull(assistantResponse.jsonPath().get(
                "events[" + mixedIndex + "].pii.notes"));
        assertEquals("+45 12345678", assistantResponse.jsonPath().getString(
                "events[" + mixedIndex + "].pii.phone.after"));

        // The restricted reclassification is newer than the original generic
        // upload. Paging below it must not make that older filename reappear.
        Response olderAssistantPage = given().header("X-Requested-By", assistant)
                .queryParam("beforeSeq", inserted.contractKindChanged())
                .when().get(EVENTS, candidate);
        olderAssistantPage.then().statusCode(200);
        assertFalse(olderAssistantPage.jsonPath().getList("events.seq", Long.class)
                .contains(inserted.contractDocument()));

        assertDossierReaderSeesFullTimeline(hiringOwner, inserted);
        assertDossierReaderSeesFullTimeline(recruiter, inserted);
    }

    private void assertDossierReaderSeesFullTimeline(String viewer,
                                                      DossierBoundaryEvents inserted) {
        Response response = given().header("X-Requested-By", viewer)
                .when().get(EVENTS, candidate);
        response.then().statusCode(200);
        List<Long> seqs = response.jsonPath().getList("events.seq", Long.class);
        assertTrue(seqs.containsAll(List.of(
                inserted.dossierCreated(), inserted.offerOpened(),
                inserted.signingCompleted(), inserted.contractDocument(),
                inserted.contractKindChanged(), inserted.flowLinkedDocument(),
                inserted.dossierOnlyUpdate(), inserted.mixedUpdate(),
                inserted.recordCheckDrawn(), inserted.recordCheckOutcome())));

        int dossierIndex = seqs.indexOf(inserted.dossierCreated());
        assertEquals("dossier-secret", response.jsonPath().getString(
                "events[" + dossierIndex + "].payload.dossier_uuid"));
        int offerIndex = seqs.indexOf(inserted.offerOpened());
        assertEquals(Boolean.TRUE, response.jsonPath().get(
                "events[" + offerIndex + "].payload.dossier_linked"));
        int mixedIndex = seqs.indexOf(inserted.mixedUpdate());
        assertTrue(response.jsonPath().getList(
                "events[" + mixedIndex + "].payload.changed_fields", String.class)
                .containsAll(List.of("phone", "notes", "target_company_uuid")));
        assertEquals("Offer context", response.jsonPath().getString(
                "events[" + mixedIndex + "].pii.notes.after"));
    }

    private DossierBoundaryEvents insertDossierBoundaryEvents() {
        return QuarkusTransaction.requiringNew().call(() -> {
            long dossierCreated = P8ProfileFixtures.insertEvent(em, "DOSSIER_CREATED", candidate,
                    applicationUuid, journeyPosition, "USER", recruiter, "NORMAL",
                    "{\"template_uuid\":\"template-secret\","
                            + "\"dossier_uuid\":\"dossier-secret\",\"reopened\":false}",
                    null);
            long offerOpened = P8ProfileFixtures.insertEvent(em, "OFFER_OPENED", candidate,
                    applicationUuid, journeyPosition, "USER", assistant, "NORMAL",
                    "{\"from_stage\":\"INTERVIEW_1\",\"dossier_linked\":true,"
                            + "\"dossier_uuid\":\"dossier-secret\"}", null);
            long signingCompleted = P8ProfileFixtures.insertEvent(em, "SIGNING_COMPLETED", candidate,
                    applicationUuid, journeyPosition, "SYSTEM", null, "NORMAL",
                    "{\"case_key\":\"case-secret\",\"dossier_uuid\":\"dossier-secret\"}",
                    null);
            long contractDocument = P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidate,
                    null, null, "SYSTEM", null, "NORMAL",
                    "{\"file_uuid\":\"file-secret\",\"kind\":\"OTHER\","
                            + "\"origin\":\"manual\",\"content_type\":\"application/pdf\"}",
                    "{\"filename\":\"Jonna salary agreement.pdf\"}");
            long contractKindChanged = P8ProfileFixtures.insertEvent(em,
                    "DOCUMENT_KIND_CHANGED", candidate, null, null,
                    "USER", recruiter, "NORMAL",
                    "{\"file_uuid\":\"file-secret\",\"kind\":\"APPENDIX\","
                            + "\"previous_kind\":\"OTHER\"}", null);
            long flowLinkedDocument = P8ProfileFixtures.insertEvent(em,
                    "DOCUMENT_UPLOADED", candidate, null, null,
                    "SYSTEM", null, "NORMAL",
                    "{\"file_uuid\":\"flow-file-secret\",\"kind\":\"OTHER\","
                            + "\"origin\":\"manual\",\"content_type\":\"application/pdf\"}",
                    "{\"filename\":\"Legacy offer.pdf\"}");
            String dossierUuid = UUID.randomUUID().toString();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                            VALUES (:uuid, :candidate, :template, 'OPEN', NOW(), NOW())
                            """)
                    .setParameter("uuid", dossierUuid)
                    .setParameter("candidate", candidate)
                    .setParameter("template", UUID.randomUUID().toString())
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossier_revisions
                                (uuid, dossier_uuid, version_number, kind,
                                 placeholder_values_snapshot, signers_config_snapshot,
                                 appendices_snapshot, generated_pdfs_snapshot,
                                 recipient_email, sent_by_useruuid, created_at)
                            VALUES (:uuid, :dossier, 1, 'REVIEW_PDF', '{}', '[]', '[]',
                                    JSON_ARRAY(JSON_OBJECT('filename', 'Legacy offer.pdf',
                                                           'fileUuid', 'flow-file-secret')),
                                    'candidate@example.invalid', :actor, NOW())
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("dossier", dossierUuid)
                    .setParameter("actor", recruiter)
                    .executeUpdate();
            long dossierOnlyUpdate = P8ProfileFixtures.insertEvent(em, "CANDIDATE_UPDATED", candidate,
                    null, null, "USER", recruiter, "NORMAL",
                    "{\"changed_fields\":[\"target_company_uuid\",\"target_start_date\",\"notes\"],"
                            + "\"target_company_uuid\":{\"before\":null,\"after\":\"company-secret\"},"
                            + "\"target_start_date\":{\"before\":null,\"after\":\"2026-10-01\"}}",
                    "{\"notes\":{\"before\":null,\"after\":\"Secret offer note\"}}");
            long mixedUpdate = P8ProfileFixtures.insertEvent(em, "CANDIDATE_UPDATED", candidate,
                    null, null, "USER", recruiter, "NORMAL",
                    "{\"changed_fields\":[\"phone\",\"notes\",\"target_company_uuid\"],"
                            + "\"target_company_uuid\":{\"before\":null,\"after\":\"company-secret\"}}",
                    "{\"phone\":{\"before\":null,\"after\":\"+45 12345678\"},"
                            + "\"notes\":{\"before\":null,\"after\":\"Offer context\"}}");
            long recordCheckDrawn = P8ProfileFixtures.insertEvent(em,
                    "RECORD_CHECK_DRAWN", candidate, applicationUuid, journeyPosition,
                    "SYSTEM", null, "NORMAL",
                    "{\"selected\":true,\"rate_applied\":20,"
                            + "\"check_uuid\":\"record-check-secret\"}", null);
            long recordCheckOutcome = P8ProfileFixtures.insertEvent(em,
                    "RECORD_CHECK_OUTCOME_RECORDED", candidate, applicationUuid,
                    journeyPosition, "USER", recruiter, "NORMAL",
                    "{\"outcome\":\"NOT_CLEAN\","
                            + "\"check_uuid\":\"record-check-secret\"}", null);
            return new DossierBoundaryEvents(dossierCreated, offerOpened, signingCompleted,
                    contractDocument, contractKindChanged, flowLinkedDocument,
                    dossierOnlyUpdate, mixedUpdate, recordCheckDrawn, recordCheckOutcome);
        });
    }

    private record DossierBoundaryEvents(
            long dossierCreated,
            long offerOpened,
            long signingCompleted,
            long contractDocument,
            long contractKindChanged,
            long flowLinkedDocument,
            long dossierOnlyUpdate,
            long mixedUpdate,
            long recordCheckDrawn,
            long recordCheckOutcome) {
    }

    // ---- Pagination -----------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void pagination_beforeSeqCursor_walksTheVisibleStream_withExactHasMore() {
        // Recruiter sees 7 visible events; pages of 3 → 3 + 3 + 1.
        Response page1 = given().header("X-Requested-By", recruiter)
                .queryParam("limit", 3)
                .when().get(EVENTS, candidate);
        page1.then().statusCode(200)
                .body("events", Matchers.hasSize(3))
                .body("hasMore", Matchers.equalTo(true))
                .body("events.seq", Matchers.contains(
                        (int) seqSalaryNote, (int) seqPrivateNote, (int) seqPublicNote));

        long cursor1 = page1.jsonPath().getLong("events[2].seq");
        Response page2 = given().header("X-Requested-By", recruiter)
                .queryParam("limit", 3).queryParam("beforeSeq", cursor1)
                .when().get(EVENTS, candidate);
        page2.then().statusCode(200)
                .body("events", Matchers.hasSize(3))
                .body("hasMore", Matchers.equalTo(true))
                .body("events.seq", Matchers.contains(
                        (int) seqStageMove, (int) seqApplication, (int) seqDocument));

        long cursor2 = page2.jsonPath().getLong("events[2].seq");
        given().header("X-Requested-By", recruiter)
                .queryParam("limit", 3).queryParam("beforeSeq", cursor2)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(1))
                .body("events[0].seq", Matchers.equalTo((int) seqCreated))
                .body("hasMore", Matchers.equalTo(false));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void limitValidation_zeroAnswers400_oversizedIsCappedNotRejected() {
        given().header("X-Requested-By", recruiter)
                .queryParam("limit", 0)
                .when().get(EVENTS, candidate)
                .then().statusCode(400);
        given().header("X-Requested-By", recruiter)
                .queryParam("limit", 5000)
                .when().get(EVENTS, candidate)
                .then().statusCode(200)
                .body("events", Matchers.hasSize(7));
        given().header("X-Requested-By", recruiter)
                .queryParam("beforeSeq", -1)
                .when().get(EVENTS, candidate)
                .then().statusCode(400);
    }
}
