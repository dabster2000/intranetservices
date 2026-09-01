package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.utils.dto.signing.PreviewTemplateResponse;
import dk.trustworks.intranet.utils.dto.signing.SigningCaseResponse;
import dk.trustworks.intranet.utils.services.SigningService;
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
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Direct-backend regression coverage for the generic signing/template boundary. */
@QuarkusTest
class SigningResourceTemplateBoundaryApiTest {

    @Inject
    EntityManager em;

    @InjectMock
    SigningService signingService;

    private String assistant;
    private String hr;
    private String signingScopeRole;
    private String candidate;
    private String employeeTemplate;
    private String recruitmentTemplate;
    private String dossierLinkedTemplate;
    private String employeeFile;
    private String recruitmentFile;
    private String dossierLinkedFile;

    @BeforeEach
    void seed() {
        assistant = UUID.randomUUID().toString();
        hr = UUID.randomUUID().toString();
        signingScopeRole = "SIGNING_TEST_" + UUID.randomUUID().toString().substring(0, 12);
        candidate = UUID.randomUUID().toString();
        employeeTemplate = UUID.randomUUID().toString();
        recruitmentTemplate = UUID.randomUUID().toString();
        dossierLinkedTemplate = UUID.randomUUID().toString();
        employeeFile = UUID.randomUUID().toString();
        recruitmentFile = UUID.randomUUID().toString();
        dossierLinkedFile = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, assistant, "Anja", "Assistant");
            P8ProfileFixtures.insertUser(em, hr, "Helle", "HR");
            P8ProfileFixtures.insertRole(em, assistant, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, hr, "HR");
            insertSigningScopeGrant();
            P8ProfileFixtures.insertRole(em, hr, signingScopeRole);
            insertTemplate(employeeTemplate, "Salary adjustment", "EMPLOYEE_SIGNING");
            insertTemplate(recruitmentTemplate, "Offer contract", "RECRUITMENT_DOSSIER");
            // Deliberately stale: dossier linkage remains recruitment-restricted.
            insertTemplate(dossierLinkedTemplate, "Legacy offer", "EMPLOYEE_SIGNING");
            insertDocument(employeeTemplate, employeeFile);
            insertDocument(recruitmentTemplate, recruitmentFile);
            insertDocument(dossierLinkedTemplate, dossierLinkedFile);
            P8ProfileFixtures.insertCandidate(em, candidate, "Dora", "Dossier",
                    "ACTIVE", null, null, hr);
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status, created_at, updated_at)
                            VALUES (:uuid, :candidate, :template, 'OPEN', NOW(), NOW())
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("candidate", candidate)
                    .setParameter("template", dossierLinkedTemplate)
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
                    .setParameter("users", List.of(assistant, hr))
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM role_permission WHERE role = :role")
                    .setParameter("role", signingScopeRole)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM role_definition WHERE name = :role")
                    .setParameter("role", signingScopeRole)
                    .executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :users")
                    .setParameter("users", List.of(assistant, hr))
                    .executeUpdate();
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"signing:read", "signing:write"})
    void assistantCannotReachEmployeeSigningPreviewDirectly() {
        given().header("X-Requested-By", assistant)
                .contentType("application/json")
                .body(previewRequest(employeeTemplate, employeeFile))
                .queryParam("userUuid", assistant)
                .when().post("/utils/signing/preview/template")
                .then().statusCode(403);

        given().header("X-Requested-By", assistant)
                .contentType("application/json")
                .body(signingRequest(employeeTemplate, employeeFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/cases/from-template")
                .then().statusCode(403);

        verify(signingService, never()).generatePreviewDocuments(anyList(), anyMap(), anyString(), anyString());
        verify(signingService, never()).createMultiDocumentCaseFromTemplate(
                anyList(), anyMap(), anyString(), anyList(), isNull(), anyList(), anyString(), anyList(), anyString());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"signing:read"})
    void authorizedHrStillCannotPreviewRecruitmentOrMismatchedTemplateDocuments() {
        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(previewRequest(recruitmentTemplate, recruitmentFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/preview/template")
                .then().statusCode(400)
                .body("message", containsString("not available for employee signing"));

        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(previewRequest(employeeTemplate, recruitmentFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/preview/template")
                .then().statusCode(400)
                .body("message", containsString("not part of the selected"));

        verify(signingService, never()).generatePreviewDocuments(anyList(), anyMap(), anyString(), anyString());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"signing:write"})
    void authorizedHrCannotSendRecruitmentOrDossierLinkedTemplates() {
        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(signingRequest(recruitmentTemplate, recruitmentFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/cases/from-template")
                .then().statusCode(400)
                .body("message", containsString("not available for employee signing"));

        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(signingRequest(dossierLinkedTemplate, dossierLinkedFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/cases/from-template")
                .then().statusCode(400)
                .body("message", containsString("not available for employee signing"));

        verify(signingService, never()).createMultiDocumentCaseFromTemplate(
                anyList(), anyMap(), anyString(), anyList(), isNull(), anyList(), anyString(), anyList(), anyString());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"signing:read", "signing:write"})
    void ordinaryEmployeeSigningTemplateStillPreviewsAndSends() {
        when(signingService.generatePreviewDocuments(anyList(), anyMap(), eq(employeeTemplate), anyString()))
                .thenReturn(List.of(new PreviewTemplateResponse.PreviewDocumentDTO(
                        "Salary adjustment.pdf", "cGRm", 0)));
        when(signingService.createMultiDocumentCaseFromTemplate(
                anyList(), anyMap(), anyString(), anyList(), isNull(), anyList(),
                eq(employeeTemplate), anyList(), anyString()))
                .thenReturn(SigningCaseResponse.created("case-employee", "Salary adjustment"));

        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(previewRequest(employeeTemplate, employeeFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/preview/template")
                .then().statusCode(200)
                .body("documents[0].documentName", equalTo("Salary adjustment.pdf"));

        given().header("X-Requested-By", hr)
                .contentType("application/json")
                .body(signingRequest(employeeTemplate, employeeFile))
                .queryParam("userUuid", hr)
                .when().post("/utils/signing/cases/from-template")
                .then().statusCode(201)
                .body("caseKey", equalTo("case-employee"));
    }

    private Map<String, Object> previewRequest(String templateUuid, String fileUuid) {
        return Map.of(
                "templateUuid", templateUuid,
                "documents", List.of(document(fileUuid)),
                "formValues", Map.of("EMPLOYEE_NAME", "Ada Example"));
    }

    private Map<String, Object> signingRequest(String templateUuid, String fileUuid) {
        return Map.of(
                "documentName", "Salary adjustment",
                "templateUuid", templateUuid,
                "documents", List.of(document(fileUuid)),
                "formValues", Map.of("EMPLOYEE_NAME", "Ada Example"),
                "signers", List.of(Map.of(
                        "name", "Ada Example",
                        "email", "ada@example.com",
                        "role", "Employee",
                        "group", 1,
                        "signing", true,
                        "needsCpr", false)),
                "signingSchemas", List.of("urn:test"),
                "additionalDocuments", List.of());
    }

    private Map<String, Object> document(String fileUuid) {
        return Map.of(
                "uuid", UUID.randomUUID().toString(),
                "documentName", "Document",
                "fileUuid", fileUuid,
                "originalFilename", "document.docx",
                "displayOrder", 1);
    }

    private void insertTemplate(String uuid, String name, String usage) {
        em.createNativeQuery("""
                        INSERT INTO document_templates
                            (uuid, name, description, category, template_usage,
                             active, created_at, updated_at)
                        VALUES (:uuid, :name, '', 'EMPLOYMENT', :usage,
                                1, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("name", name)
                .setParameter("usage", usage)
                .executeUpdate();
    }

    private void insertSigningScopeGrant() {
        em.createNativeQuery("""
                        INSERT INTO permission
                            (permission_key, display_name, description, category,
                             origin, state, enforce_acting_user)
                        VALUES ('salaries:read', 'Read salaries',
                                'Signing authorization test fixture', 'salary',
                                'CODE', 'ACTIVE', 1)
                        ON DUPLICATE KEY UPDATE permission_key = VALUES(permission_key)
                        """)
                .executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO role_definition
                            (name, display_label, is_system)
                        VALUES (:role, 'Signing authorization test', 0)
                        """)
                .setParameter("role", signingScopeRole)
                .executeUpdate();
        em.createNativeQuery("""
                        INSERT INTO role_permission
                            (role, permission_key, data_scope)
                        VALUES (:role, 'salaries:read', 'ALL')
                        """)
                .setParameter("role", signingScopeRole)
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
        return List.of(employeeTemplate, recruitmentTemplate, dossierLinkedTemplate);
    }
}
