package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.utils.services.WordDocumentService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;

/** Direct-backend regression coverage for template classification. */
@QuarkusTest
class TemplateResourceAuthorizationApiTest {

    @Inject
    EntityManager em;

    @InjectMock
    WordDocumentService wordDocumentService;

    private String hr;
    private String assistant;
    private String candidate;
    private String employeeTemplate;
    private String recruitmentTemplate;
    private String staleLinkedTemplate;
    private String employeeFile;
    private String recruitmentFile;
    private String staleFile;

    @BeforeEach
    void seed() {
        hr = UUID.randomUUID().toString();
        assistant = UUID.randomUUID().toString();
        candidate = UUID.randomUUID().toString();
        employeeTemplate = UUID.randomUUID().toString();
        recruitmentTemplate = UUID.randomUUID().toString();
        staleLinkedTemplate = UUID.randomUUID().toString();
        employeeFile = UUID.randomUUID().toString();
        recruitmentFile = UUID.randomUUID().toString();
        staleFile = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hr, "Helle", "HR");
            P8ProfileFixtures.insertUser(em, assistant, "Rikke", "Assistant");
            P8ProfileFixtures.insertRole(em, hr, "HR");
            P8ProfileFixtures.insertRole(em, assistant, "ASSISTANT_TEAMLEAD");
            insertTemplate(employeeTemplate, "Salary adjustment", "EMPLOYEE_SIGNING");
            insertTemplate(recruitmentTemplate, "Offer contract", "RECRUITMENT_DOSSIER");
            // Deliberately stale: the dossier reference must override this value.
            insertTemplate(staleLinkedTemplate, "Legacy offer", "EMPLOYEE_SIGNING");
            insertDocument(employeeTemplate, employeeFile);
            insertDocument(recruitmentTemplate, recruitmentFile);
            insertDocument(staleLinkedTemplate, staleFile);
            P8ProfileFixtures.insertCandidate(em, candidate, "Case", "Candidate",
                    "ACTIVE", null, null, hr);
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                            VALUES (:uuid, :candidate, :template, 'OPEN', NOW(), NOW())
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("candidate", candidate)
                    .setParameter("template", staleLinkedTemplate)
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid = :candidate")
                    .setParameter("candidate", candidate)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :candidate")
                    .setParameter("candidate", candidate)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM template_documents WHERE template_uuid IN :templates")
                    .setParameter("templates", templateUuids())
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM document_templates WHERE uuid IN :templates")
                    .setParameter("templates", templateUuids())
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :users")
                    .setParameter("users", List.of(hr, assistant))
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :users")
                    .setParameter("users", List.of(hr, assistant))
                    .executeUpdate();
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"documents:read"})
    void assistantOnlyGetsExplicitEmployeeSigningCollection() {
        given().header("X-Requested-By", assistant)
                .when().get("/templates")
                .then().statusCode(403);
        given().header("X-Requested-By", assistant)
                .queryParam("usage", "RECRUITMENT_DOSSIER")
                .when().get("/templates")
                .then().statusCode(403);

        given().header("X-Requested-By", assistant)
                .queryParam("usage", "EMPLOYEE_SIGNING")
                .when().get("/templates")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].uuid", equalTo(employeeTemplate));

        given().header("X-Requested-By", assistant)
                .when().get("/templates/{uuid}", employeeTemplate)
                .then().statusCode(200);
        given().header("X-Requested-By", assistant)
                .when().get("/templates/{uuid}", recruitmentTemplate)
                .then().statusCode(403);
        given().header("X-Requested-By", assistant)
                .when().get("/templates/{uuid}", staleLinkedTemplate)
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"documents:read"})
    void missingAndMalformedRequestedByFailClosed() {
        given().queryParam("usage", "EMPLOYEE_SIGNING")
                .when().get("/templates")
                .then().statusCode(403);
        given().header("X-Requested-By", "not-a-uuid")
                .queryParam("usage", "EMPLOYEE_SIGNING")
                .when().get("/templates")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"documents:read"})
    void explicitUsageFiltersEvenForHr() {
        given().header("X-Requested-By", hr)
                .when().get("/templates")
                .then().statusCode(200)
                .body("$", hasSize(3));
        given().header("X-Requested-By", hr)
                .queryParam("usage", "EMPLOYEE_SIGNING")
                .when().get("/templates")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].uuid", equalTo(employeeTemplate));
        given().header("X-Requested-By", hr)
                .queryParam("usage", "RECRUITMENT_DOSSIER")
                .when().get("/templates")
                .then().statusCode(200)
                .body("$", hasSize(2));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"documents:read", "documents:write"})
    void writesAreHrOnly() {
        given().header("X-Requested-By", assistant)
                .when().delete("/templates/{uuid}", recruitmentTemplate)
                .then().statusCode(403);
        given().header("X-Requested-By", hr)
                .when().delete("/templates/{uuid}", recruitmentTemplate)
                .then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"documents:read"})
    void rawDocumentAndPlaceholderReadsUseOwnerClassification() {
        when(wordDocumentService.extractPlaceholders(employeeFile)).thenReturn(Set.of("EMPLOYEE_NAME"));

        given().header("X-Requested-By", assistant)
                .when().get("/templates/documents/{file}/placeholders", employeeFile)
                .then().statusCode(200);
        given().header("X-Requested-By", assistant)
                .when().get("/templates/documents/{file}/placeholders", recruitmentFile)
                .then().statusCode(403);
        given().header("X-Requested-By", assistant)
                .when().get("/templates/documents/{file}/download", staleFile)
                .then().statusCode(403);
    }

    private void insertTemplate(String uuid, String name, String usage) {
        em.createNativeQuery("""
                        INSERT INTO document_templates
                            (uuid, name, description, category, template_usage,
                             sharepoint_type, active, created_at, updated_at)
                        VALUES (:uuid, :name, '', 'EMPLOYMENT', :usage,
                                'EMPLOYEE', 1, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("name", name)
                .setParameter("usage", usage)
                .executeUpdate();
    }

    private void insertDocument(String templateUuid, String fileUuid) {
        em.createNativeQuery("""
                        INSERT INTO template_documents
                            (uuid, template_uuid, document_name, file_uuid,
                             original_filename, display_order, created_at, updated_at)
                        VALUES (:uuid, :template, 'Document', :file,
                                'document.docx', 1, NOW(), NOW())
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("template", templateUuid)
                .setParameter("file", fileUuid)
                .executeUpdate();
    }

    private List<String> templateUuids() {
        return List.of(employeeTemplate, recruitmentTemplate, staleLinkedTemplate);
    }
}
