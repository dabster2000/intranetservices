package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sender identity, reply routing and internal copies (the P15 email
 * loose-ends round, 2026-07-24). The contract under test:
 * <ul>
 *   <li>every candidate email carries a display name and a Reply-To — the
 *       acting recruiter when a human sent it, the configured fallback
 *       otherwise; a reply is never a black hole;</li>
 *   <li>a template's copy policy resolves to real people and lands in
 *       {@code bcc} (invisible) or {@code cc} (visible), never both;</li>
 *   <li><b>the copy list is authorization, not formatting</b>: a person
 *       who may not read the candidate is dropped even when the caller
 *       names them explicitly;</li>
 *   <li>the widened {@code mail.subject} column accepts the 300 characters
 *       the API has always validated (pre-V455 this died at 256 with
 *       error 1406 under STRICT_TRANS_TABLES).</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentEmailSenderApiTest {

    private static final String INTERVIEWS_FLAG = "recruitment.interviews.enabled";
    private static final String REPLY_TO_KEY = "recruitment.email.reply-to-fallback";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String recruiterUser;
    private String ownerUser;
    private String outsiderUser;
    private String positionUuid;
    private String candidateUuid;
    private String applicationUuid;
    private String candidateEmail;
    private String recruiterEmail;
    private String ownerEmail;
    private String templateUuid;
    private String templateKey;

    private String previousFlag;
    private String previousReplyTo;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        recruiterUser = UUID.randomUUID().toString();
        ownerUser = UUID.randomUUID().toString();
        outsiderUser = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        applicationUuid = UUID.randomUUID().toString();
        candidateEmail = "kandidat." + UUID.randomUUID() + "@example.com";
        recruiterEmail = "rina." + UUID.randomUUID() + "@trustworks.dk";
        ownerEmail = "olga." + UUID.randomUUID() + "@trustworks.dk";
        templateKey = "COPYTEST_" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 10).toUpperCase();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, recruiterUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, ownerUser, "Olga", "Owner");
            P8ProfileFixtures.insertUser(em, outsiderUser, "Ole", "Outsider");
            P8ProfileFixtures.insertRole(em, recruiterUser, "HR");
            // The hiring owner is deliberately NOT a recruiter: they see the
            // candidate through position ownership alone, which is exactly
            // the involvement the copy gate must honour.
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Løsningsarkitekt",
                    "PRACTICE_TEAM", practiceUuid, null, ownerUser);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "Søren", "Kjærgård", "ACTIVE", null, null, recruiterUser);
            em.createNativeQuery(
                            "UPDATE recruitment_candidates SET email = :email WHERE uuid = :uuid")
                    .setParameter("email", candidateEmail)
                    .setParameter("uuid", candidateUuid)
                    .executeUpdate();
            P8ProfileFixtures.insertOpenApplication(em, applicationUuid,
                    candidateUuid, positionUuid, "SCREENING");
            setUserEmail(recruiterUser, recruiterEmail);
            setUserEmail(ownerUser, ownerEmail);

            previousFlag = P8ProfileFixtures.setFlag(em, INTERVIEWS_FLAG, "true");
            previousReplyTo = P8ProfileFixtures.setFlag(em, REPLY_TO_KEY, "hr@trustworks.dk");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_pending_emails WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_email_templates WHERE template_key = :key")
                    .setParameter("key", templateKey).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid), List.of(positionUuid),
                    List.of(recruiterUser, ownerUser, outsiderUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, INTERVIEWS_FLAG, previousFlag);
            P8ProfileFixtures.restoreFlag(em, REPLY_TO_KEY, previousReplyTo);
        });
    }

    // ---- helpers ---------------------------------------------------------------

    private void setUserEmail(String userUuid, String email) {
        em.createNativeQuery("UPDATE user SET email = :email WHERE uuid = :uuid")
                .setParameter("email", email)
                .setParameter("uuid", userUuid)
                .executeUpdate();
    }

    /** The single queued mail row for this candidate, as columns. */
    private Map<String, String> mailRow() {
        return QuarkusTransaction.requiringNew().call(() -> {
            Object[] row = (Object[]) em.createNativeQuery(
                            "SELECT subject, from_name, reply_to, cc, bcc FROM mail WHERE mail = :to")
                    .setParameter("to", candidateEmail)
                    .getSingleResult();
            Map<String, String> out = new HashMap<>();
            out.put("subject", (String) row[0]);
            out.put("fromName", (String) row[1]);
            out.put("replyTo", (String) row[2]);
            out.put("cc", (String) row[3]);
            out.put("bcc", (String) row[4]);
            return out;
        });
    }

    /** Create a template with a copy policy and return its uuid. */
    private String createTemplate(List<String> copyRoles, String copyMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("templateKey", templateKey);
        body.put("name", "Kopitest");
        body.put("subject", "Hej {{candidate_first_name}}");
        body.put("body", "Brødtekst");
        body.put("autoSend", false);
        body.put("active", true);
        body.put("copyRoles", copyRoles);
        body.put("copyMode", copyMode);
        templateUuid = given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON).body(body)
                .when().post("/recruitment/email-templates")
                .then().statusCode(201)
                .extract().path("uuid");
        return templateUuid;
    }

    private void send(Map<String, Object> body) {
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON).body(body)
                .when().post("/recruitment/candidates/{uuid}/emails/send", candidateUuid)
                .then().statusCode(201);
    }

    // ---- Sender identity & reply routing -----------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void manualSend_carriesDisplayNameAndTheActingRecruiterAsReplyTo() {
        send(Map.of("subject", "Opfølgning", "body", "Kære Søren",
                "applicationUuid", applicationUuid));

        Map<String, String> mail = mailRow();
        assertEquals("Trustworks Rekruttering", mail.get("fromName"));
        assertEquals(recruiterEmail, mail.get("replyTo"),
                "a reply to a hand-sent email must reach the person who sent it");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void replyToLandsInPii_neverInPayload() {
        send(Map.of("subject", "Opfølgning", "body", "Kære Søren"));

        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentEvent event = RecruitmentEvent.<RecruitmentEvent>find(
                            "candidateUuid = ?1 and eventType = ?2",
                            candidateUuid, RecruitmentEventType.EMAIL_SENT)
                    .firstResult();
            assertNotNull(event);
            // The fixture rejects any email-shaped value in payload — this
            // is the guard that keeps reply/copy addresses anonymizable.
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
            assertTrue(event.getPii().contains(recruiterEmail));
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void emailSettings_readAndUpdate_withValidation() {
        given().header("X-Requested-By", recruiterUser)
                .when().get("/recruitment/email-settings")
                .then().statusCode(200)
                .body("replyToFallback", equalTo("hr@trustworks.dk"))
                .body("fromName", equalTo("Trustworks Rekruttering"));

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("replyToFallback", "rekruttering@trustworks.dk"))
                .when().put("/recruitment/email-settings")
                .then().statusCode(200)
                .body("replyToFallback", equalTo("rekruttering@trustworks.dk"));

        // Empty is a legal, deliberate "no Reply-To header" choice.
        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("replyToFallback", ""))
                .when().put("/recruitment/email-settings")
                .then().statusCode(200)
                .body("replyToFallback", equalTo(""));

        given().header("X-Requested-By", recruiterUser)
                .contentType(ContentType.JSON)
                .body(Map.of("replyToFallback", "not-an-address"))
                .when().put("/recruitment/email-settings")
                .then().statusCode(409);
    }

    // ---- Copies ------------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void templateCopyPolicy_bccsTheHiringOwner_invisibleToTheCandidate() {
        String template = createTemplate(List.of("HIRING_OWNER"), "BCC");

        send(Map.of("subject", "Opfølgning", "body", "Kære Søren",
                "templateUuid", template, "applicationUuid", applicationUuid));

        Map<String, String> mail = mailRow();
        assertEquals(ownerEmail, mail.get("bcc"));
        assertNull(mail.get("cc"), "a BCC template must never populate the visible header");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void ccMode_putsTheSamePeopleInTheVisibleHeaderInstead() {
        String template = createTemplate(List.of("HIRING_OWNER"), "CC");

        send(Map.of("subject", "Opfølgning", "body", "Kære Søren",
                "templateUuid", template, "applicationUuid", applicationUuid));

        Map<String, String> mail = mailRow();
        assertEquals(ownerEmail, mail.get("cc"));
        assertNull(mail.get("bcc"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void senderRole_copiesTheRecruiterWhoPressedSend() {
        String template = createTemplate(List.of("SENDER"), "BCC");

        send(Map.of("subject", "Opfølgning", "body", "Kære Søren",
                "templateUuid", template, "applicationUuid", applicationUuid));

        assertEquals(recruiterEmail, mailRow().get("bcc"));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void explicitCopyList_overridesTheTemplate_andEmptyMeansCopyNobody() {
        String template = createTemplate(List.of("HIRING_OWNER"), "BCC");

        Map<String, Object> body = new HashMap<>();
        body.put("subject", "Opfølgning");
        body.put("body", "Kære Søren");
        body.put("templateUuid", template);
        body.put("applicationUuid", applicationUuid);
        body.put("copyUserUuids", List.of()); // explicit "nobody"
        send(body);

        assertNull(mailRow().get("bcc"),
                "an explicit empty list is a recruiter decision, not a missing field");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void unauthorizedCopyRecipient_isDroppedEvenWhenNamedExplicitly() {
        // The outsider has no role and no involvement — canReadCandidateProfile
        // says no, so their address must never reach the outbox however the
        // request is crafted.
        Map<String, Object> body = new HashMap<>();
        body.put("subject", "Opfølgning");
        body.put("body", "Kære Søren");
        body.put("applicationUuid", applicationUuid);
        body.put("copyUserUuids", List.of(outsiderUser, ownerUser));
        send(body);

        Map<String, String> mail = mailRow();
        assertEquals(ownerEmail, mail.get("bcc"),
                "the authorized recipient survives; the unauthorized one is silently dropped");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void copyOptions_returnsThePool_withTemplateDefaultsPreselected() {
        String template = createTemplate(List.of("HIRING_OWNER"), "BCC");

        given().header("X-Requested-By", recruiterUser)
                .queryParam("templateUuid", template)
                .queryParam("applicationUuid", applicationUuid)
                .when().get("/recruitment/candidates/{uuid}/emails/copy-options", candidateUuid)
                .then().statusCode(200)
                .body("copyMode", equalTo("BCC"))
                .body("replyTo", equalTo(recruiterEmail))
                .body("recipients.userUuid", hasItem(ownerUser))
                // The sender is offered but not preselected — the template
                // asks for the hiring owner only.
                .body("recipients.userUuid", hasItem(recruiterUser))
                .body("recipients.find { it.userUuid == '" + ownerUser + "' }.selected",
                        equalTo(true))
                .body("recipients.find { it.userUuid == '" + recruiterUser + "' }.selected",
                        equalTo(false))
                .body("recipients.userUuid", not(hasItem(outsiderUser)));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void copiedPeopleAreStructuralInPayload_addressesStayInPii() {
        String template = createTemplate(List.of("HIRING_OWNER"), "BCC");

        send(Map.of("subject", "Opfølgning", "body", "Kære Søren",
                "templateUuid", template, "applicationUuid", applicationUuid));

        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentEvent event = RecruitmentEvent.<RecruitmentEvent>find(
                            "candidateUuid = ?1 and eventType = ?2",
                            candidateUuid, RecruitmentEventType.EMAIL_SENT)
                    .firstResult();
            assertNotNull(event);
            RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
            // UUIDs and counts survive anonymization so P20 can still report
            // comms reach; the addresses do not.
            assertTrue(event.getPayload().contains("\"copy_mode\":\"BCC\""));
            assertTrue(event.getPayload().contains(ownerUser));
            assertTrue(event.getPii().contains(ownerEmail));
        });
    }

    // ---- The widened subject column ----------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void subjectOf300Characters_sendsInsteadOfBlowingUpAt256() {
        String longSubject = "æ".repeat(300);

        send(Map.of("subject", longSubject, "body", "Kære Søren"));

        assertEquals(longSubject, mailRow().get("subject"),
                "the API validates 300; the outbox column must store 300");
    }
}
