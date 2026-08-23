package dk.trustworks.intranet.recruitmentservice.resources;

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

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Direct API coverage for the assistant's mutable destination-practice boundary. */
@QuarkusTest
class RecruitmentPositionAssistantPracticeMutationApiTest {

    private static final String POSITION = "/recruitment/positions/{uuid}";

    @Inject
    EntityManager em;

    private String ownPractice;
    private String otherPractice;
    private String positionUuid;
    private String assistant;
    private String assistantWithoutPractice;
    private String assistantAndTeamlead;
    private String previousFlag;

    @BeforeEach
    void seed() {
        ownPractice = UUID.randomUUID().toString();
        otherPractice = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        assistant = UUID.randomUUID().toString();
        assistantWithoutPractice = UUID.randomUUID().toString();
        assistantAndTeamlead = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertPractice(em, ownPractice);
            P8ProfileFixtures.insertPractice(em, otherPractice);
            P8ProfileFixtures.insertUser(em, assistant, "Assistant", "Scoped");
            P8ProfileFixtures.insertUser(em, assistantWithoutPractice, "Assistant", "Unscoped");
            P8ProfileFixtures.insertUser(em, assistantAndTeamlead, "Assistant", "Lead");
            P8ProfileFixtures.insertRole(em, assistant, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantWithoutPractice, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantAndTeamlead, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantAndTeamlead, "TEAMLEAD");
            setPractice(assistant, ownPractice);
            setPractice(assistantAndTeamlead, ownPractice);
            P8ProfileFixtures.insertPosition(em, positionUuid, "Own-practice role",
                    "PRACTICE_TEAM", ownPractice, null, null);
            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(
                    em,
                    List.of(),
                    List.of(positionUuid),
                    List.of(assistant, assistantWithoutPractice, assistantAndTeamlead),
                    ownPractice);
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :uuid")
                    .setParameter("uuid", otherPractice)
                    .executeUpdate();
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCannotMoveOwnPositionToAnotherPracticeOrClearItsPractice() {
        put(assistant, body("Cross-practice move", otherPractice), 403);
        assertEquals(ownPractice, storedPractice());
        assertEquals("Own-practice role", storedTitle());

        put(assistant, body("Practice cleared", null), 403);
        assertEquals(ownPractice, storedPractice());
        assertEquals("Own-practice role", storedTitle());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantMayKeepAndEditAPositionInTheirOwnPractice() {
        put(assistant, body("Own-practice role renamed", ownPractice), 200);

        assertEquals(ownPractice, storedPractice());
        assertEquals("Own-practice role renamed", storedTitle());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantWithoutPracticeFailsClosedBeforeMutation() {
        put(assistantWithoutPractice, body("Unscoped edit", ownPractice), 404);

        assertEquals(ownPractice, storedPractice());
        assertEquals("Own-practice role", storedTitle());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void additiveTeamleadRoleKeepsItsBroaderNonPartnerAuthority() {
        put(assistantAndTeamlead, body("Broader-role move", otherPractice), 200);

        assertEquals(otherPractice, storedPractice());
        assertEquals("Broader-role move", storedTitle());
    }

    private void put(String actor, Map<String, Object> body, int expectedStatus) {
        given().header("X-Requested-By", actor)
                .contentType(ContentType.JSON)
                .body(body)
                .when().put(POSITION, positionUuid)
                .then().statusCode(expectedStatus);
    }

    private Map<String, Object> body(String title, String practiceUuid) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("hiringTrack", "PRACTICE_TEAM");
        if (practiceUuid != null) {
            body.put("practiceUuid", practiceUuid);
        }
        return body;
    }

    private void setPractice(String userUuid, String practiceUuid) {
        em.createNativeQuery("UPDATE user SET practice_uuid = :practice WHERE uuid = :user")
                .setParameter("practice", practiceUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    private String storedPractice() {
        return scalar("SELECT practice_uuid FROM recruitment_positions WHERE uuid = :uuid");
    }

    private String storedTitle() {
        return scalar("SELECT title FROM recruitment_positions WHERE uuid = :uuid");
    }

    private String scalar(String sql) {
        return QuarkusTransaction.requiringNew().call(() -> (String) em.createNativeQuery(sql)
                .setParameter("uuid", positionUuid)
                .getSingleResult());
    }
}
