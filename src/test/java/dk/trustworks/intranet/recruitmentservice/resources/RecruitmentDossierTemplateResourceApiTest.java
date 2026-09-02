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

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.DOSSIER_FLAG;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** Candidate-context access regression tests for dossier template metadata. */
@QuarkusTest
class RecruitmentDossierTemplateResourceApiTest {

    @Inject
    EntityManager em;

    private String hr;
    private String owner;
    private String otherTeamlead;
    private String assistantOwner;
    private String candidate;
    private String practice;
    private String ownerPosition;
    private String assistantPosition;
    private String template;
    private String previousDossierFlag;

    @BeforeEach
    void seed() {
        hr = UUID.randomUUID().toString();
        owner = UUID.randomUUID().toString();
        otherTeamlead = UUID.randomUUID().toString();
        assistantOwner = UUID.randomUUID().toString();
        candidate = UUID.randomUUID().toString();
        practice = UUID.randomUUID().toString();
        ownerPosition = UUID.randomUUID().toString();
        assistantPosition = UUID.randomUUID().toString();
        template = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hr, "Helle", "HR");
            P8ProfileFixtures.insertUser(em, owner, "Tina", "Owner");
            P8ProfileFixtures.insertUser(em, otherTeamlead, "Tom", "Other");
            P8ProfileFixtures.insertUser(em, assistantOwner, "Anja", "Assistant");
            P8ProfileFixtures.insertRole(em, hr, "HR");
            P8ProfileFixtures.insertRole(em, owner, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, otherTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, assistantOwner, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertPractice(em, practice);
            P8ProfileFixtures.insertPosition(em, ownerPosition, "Owner role",
                    "PRACTICE_TEAM", practice, null, owner);
            P8ProfileFixtures.insertPosition(em, assistantPosition, "Assistant owner role",
                    "PRACTICE_TEAM", practice, null, assistantOwner);
            P8ProfileFixtures.insertCandidate(em, candidate, "Dora", "Dossier",
                    "ACTIVE", null, null, hr);
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    candidate, ownerPosition, "OFFER");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    candidate, assistantPosition, "OFFER");
            em.createNativeQuery("""
                            INSERT INTO document_templates
                                (uuid, name, description, category, template_usage,
                                 active, created_at, updated_at)
                            VALUES (:uuid, 'Stale legacy offer', '', 'EMPLOYMENT',
                                    'EMPLOYEE_SIGNING', 1, NOW(), NOW())
                            """)
                    .setParameter("uuid", template)
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                            VALUES (:uuid, :candidate, :template, 'OPEN', NOW(), NOW())
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("candidate", candidate)
                    .setParameter("template", template)
                    .executeUpdate();
            previousDossierFlag = P8ProfileFixtures.setFlag(em, DOSSIER_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid = :candidate")
                    .setParameter("candidate", candidate)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM document_templates WHERE uuid = :template")
                    .setParameter("template", template)
                    .executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidate),
                    List.of(ownerPosition, assistantPosition),
                    List.of(hr, owner, otherTeamlead, assistantOwner),
                    practice);
            P8ProfileFixtures.restoreFlag(em, DOSSIER_FLAG, previousDossierFlag);
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void eligibleNamedTeamleadReadsTemplateThroughCandidateContext() {
        given().header("X-Requested-By", owner)
                .when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(200)
                .body("uuid", equalTo(template));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void hrReadsTemplateThroughCandidateContext() {
        given().header("X-Requested-By", hr)
                .when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(200)
                .body("uuid", equalTo(template));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantNamedOwnerAndUnrelatedTeamleadAreHidden() {
        given().header("X-Requested-By", assistantOwner)
                .when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(404);
        given().header("X-Requested-By", otherTeamlead)
                .when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void missingAndMalformedActorFailClosed() {
        given().when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(404);
        given().header("X-Requested-By", "not-a-uuid")
                .when().get("/recruitment/candidates/{candidate}/dossier/template", candidate)
                .then().statusCode(404);
    }
}
