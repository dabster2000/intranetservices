package dk.trustworks.intranet.recruitmentservice.resources;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * P9 §8.9 (pending-referrals DTO extension): the {@code aiSuggestions}
 * block on {@code GET /recruitment/referrals/pending} — present with
 * batched names when valid, per-field nulled by read-time re-validation
 * (deactivated practice, departed teamlead), and absent entirely when the
 * referral-triage toggle is off or no generation exists.
 */
@QuarkusTest
class RecruitmentReferralAiPendingApiTest {

    private static final String TRIAGE_FLAG = "recruitment.ai.referral-triage.enabled";

    @Inject
    EntityManager em;

    private String practiceUuid;
    private String teamUuid;
    private String hrUser;
    private String teamleadUser;
    private String referralUuid;
    private String bareReferralUuid;

    private String previousPipeline;
    private String previousTriage;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();
        bareReferralUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertUser(em, teamleadUser, "Tim", "Teamlead");
            P8ProfileFixtures.insertTeamLeader(em, teamleadUser, teamUuid);
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            insertReferral(referralUuid);
            insertReferral(bareReferralUuid);
            insertSuggestionEvent(referralUuid, "gen1");
            previousPipeline = P8ProfileFixtures.setFlag(em, P8ProfileFixtures.PIPELINE_FLAG, "true");
            previousTriage = P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE payload LIKE :a OR payload LIKE :b")
                    .setParameter("a", "%" + referralUuid + "%")
                    .setParameter("b", "%" + bareReferralUuid + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid IN :u")
                    .setParameter("u", List.of(referralUuid, bareReferralUuid)).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em, List.of(), List.of(),
                    List.of(hrUser, teamleadUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, P8ProfileFixtures.PIPELINE_FLAG, previousPipeline);
            P8ProfileFixtures.restoreFlag(em, TRIAGE_FLAG, previousTriage);
        });
    }

    private void insertReferral(String uuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_referrals
                            (uuid, referrer_uuid, referrer_relation, candidate_name, why_text,
                             status, submitted_at, version, created_at, updated_at, created_by)
                        VALUES (:uuid, :referrer, 'COLLEAGUE', :name, :why,
                                'SUBMITTED', UTC_TIMESTAMP(3), 0, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("referrer", hrUser)
                .setParameter("name", PII_SENTINEL + " Karla Kandidat")
                .setParameter("why", PII_SENTINEL + " Dygtig arkitekt")
                .executeUpdate();
    }

    private void insertSuggestionEvent(String referral, String generationId) {
        String payload = """
                {"generation_id":"%s","referral_uuid":"%s","model":"m",
                 "prompt_version":"referral-triage-v1",
                 "fields":["PRACTICE","EXPERIENCE_LEVEL","RELEVANT_TEAMLEAD"]}
                """.formatted(generationId, referral);
        String pii = """
                {"suggestions":[
                  {"id":"%1$s:PRACTICE","field":"PRACTICE","value":"%2$s",
                   "rationale":"Arkitekturprofil passer"},
                  {"id":"%1$s:EXPERIENCE_LEVEL","field":"EXPERIENCE_LEVEL","value":"SENIOR",
                   "rationale":"Otte aars erfaring"},
                  {"id":"%1$s:RELEVANT_TEAMLEAD","field":"RELEVANT_TEAMLEAD","value":"%3$s",
                   "rationale":"Leder det relevante hold"}]}
                """.formatted(generationId, practiceUuid, teamleadUser);
        P8ProfileFixtures.insertEvent(em, "AI_SUGGESTIONS_GENERATED", null, null, null,
                "SYSTEM", null, "NORMAL", payload, pii);
    }

    private io.restassured.response.ValidatableResponse pending() {
        return given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/referrals/pending")
                .then().statusCode(200);
    }

    private String row(String referral) {
        return "referrals.find { it.uuid == '" + referral + "' }";
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void pendingRow_carriesValidatedSuggestionsWithBatchedNames() {
        pending()
                .body(row(referralUuid) + ".aiSuggestions.practiceUuid", equalTo(practiceUuid))
                .body(row(referralUuid) + ".aiSuggestions.practiceName", equalTo("P8 Fixture"))
                .body(row(referralUuid) + ".aiSuggestions.experienceLevel", equalTo("SENIOR"))
                .body(row(referralUuid) + ".aiSuggestions.relevantTeamleadUuid", equalTo(teamleadUser))
                .body(row(referralUuid) + ".aiSuggestions.teamleadName", equalTo("Tim Teamlead"))
                .body(row(referralUuid) + ".aiSuggestions.rationales.practice", notNullValue())
                .body(row(referralUuid) + ".aiSuggestions.generatedAt", notNullValue())
                .body(row(bareReferralUuid) + ".aiSuggestions", nullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void deactivatedPractice_isNulledAtReadTime_otherFieldsSurvive() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE practice SET active = 0 WHERE uuid = :p")
                .setParameter("p", practiceUuid).executeUpdate());

        pending()
                .body(row(referralUuid) + ".aiSuggestions.practiceUuid", nullValue())
                .body(row(referralUuid) + ".aiSuggestions.practiceName", nullValue())
                .body(row(referralUuid) + ".aiSuggestions.experienceLevel", equalTo("SENIOR"))
                .body(row(referralUuid) + ".aiSuggestions.relevantTeamleadUuid", equalTo(teamleadUser));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void departedTeamlead_isNulledAtReadTime() {
        QuarkusTransaction.requiringNew().run(() -> em.createNativeQuery(
                        "UPDATE teamroles SET enddate = '2020-01-01' WHERE useruuid = :u")
                .setParameter("u", teamleadUser).executeUpdate());

        pending()
                .body(row(referralUuid) + ".aiSuggestions.relevantTeamleadUuid", nullValue())
                .body(row(referralUuid) + ".aiSuggestions.teamleadName", nullValue())
                .body(row(referralUuid) + ".aiSuggestions.practiceUuid", equalTo(practiceUuid));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void triageFlagOff_hidesTheWholeBlock() {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, "false"));

        pending().body(row(referralUuid) + ".aiSuggestions", nullValue());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void newerGenerationWins() {
        QuarkusTransaction.requiringNew().run(() -> {
            String payload = """
                    {"generation_id":"gen2","referral_uuid":"%s","model":"m",
                     "prompt_version":"referral-triage-v1","fields":["EXPERIENCE_LEVEL"]}
                    """.formatted(referralUuid);
            String pii = """
                    {"suggestions":[{"id":"gen2:EXPERIENCE_LEVEL","field":"EXPERIENCE_LEVEL",
                      "value":"JUNIOR","rationale":"Nyuddannet profil"}]}
                    """;
            P8ProfileFixtures.insertEvent(em, "AI_SUGGESTIONS_GENERATED", null, null, null,
                    "SYSTEM", null, "NORMAL", payload, pii);
        });

        pending()
                .body(row(referralUuid) + ".aiSuggestions.experienceLevel", equalTo("JUNIOR"))
                // The latest generation replaces the whole block.
                .body(row(referralUuid) + ".aiSuggestions.practiceUuid", nullValue());
    }
}
