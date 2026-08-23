package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import dk.trustworks.intranet.recruitmentservice.services.CandidateDocumentClassifier;
import dk.trustworks.intranet.recruitmentservice.services.CandidateBriefService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression coverage for the candidate Documents-tab boundary. The profile
 * gate and the offer-dossier gate are intentionally different capabilities:
 * a profile reader may read ordinary recruitment files, but contract drafts,
 * signed documents, appendices and identity documents require the canonical
 * candidate-scoped dossier capability.
 */
@QuarkusTest
class RecruitmentCandidateDocumentDossierAuthzApiTest {

    private static final List<String> ORDINARY_KINDS = List.of(
            CandidateDocumentClassifier.KIND_CV,
            CandidateDocumentClassifier.KIND_COVER_LETTER,
            CandidateDocumentClassifier.KIND_OTHER);

    private static final List<String> RESTRICTED_KINDS = List.of(
            CandidateDocumentClassifier.KIND_CONTRACT_DRAFT,
            CandidateDocumentClassifier.KIND_SIGNED_DOCUMENT,
            CandidateDocumentClassifier.KIND_APPENDIX,
            CandidateDocumentClassifier.KIND_ID_DOCUMENT);

    @Inject
    EntityManager em;

    @InjectMock
    S3FileService s3FileService;

    @Inject
    CandidateBriefService candidateBriefService;

    private String practiceUuid;
    private String candidateUuid;
    private String foreignCandidateUuid;
    private String foreignFileUuid;
    private String assistantUser;
    private String namedTeamlead;
    private String unnamedTeamlead;
    private String recruitmentUser;
    private String hrUser;
    private String adminUser;
    private String assistantPosition;
    private String teamleadPosition;
    private String previousFlag;
    private Map<String, String> filesByKind;
    private String editableRestrictedFile;
    private String historicallyRestrictedFile;

    @BeforeEach
    void seed() {
        Mockito.reset(s3FileService);
        practiceUuid = UUID.randomUUID().toString();
        candidateUuid = UUID.randomUUID().toString();
        foreignCandidateUuid = UUID.randomUUID().toString();
        foreignFileUuid = UUID.randomUUID().toString();
        assistantUser = UUID.randomUUID().toString();
        namedTeamlead = UUID.randomUUID().toString();
        unnamedTeamlead = UUID.randomUUID().toString();
        recruitmentUser = UUID.randomUUID().toString();
        hrUser = UUID.randomUUID().toString();
        adminUser = UUID.randomUUID().toString();
        assistantPosition = UUID.randomUUID().toString();
        teamleadPosition = UUID.randomUUID().toString();
        filesByKind = new LinkedHashMap<>();
        ORDINARY_KINDS.forEach(kind -> filesByKind.put(kind, UUID.randomUUID().toString()));
        RESTRICTED_KINDS.forEach(kind -> filesByKind.put(kind, UUID.randomUUID().toString()));
        editableRestrictedFile = UUID.randomUUID().toString();
        historicallyRestrictedFile = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, assistantUser, "Rita", "Assistant");
            P8ProfileFixtures.insertUser(em, namedTeamlead, "Tina", "Owner");
            P8ProfileFixtures.insertUser(em, unnamedTeamlead, "Una", "Teamlead");
            P8ProfileFixtures.insertUser(em, recruitmentUser, "Rene", "Recruitment");
            P8ProfileFixtures.insertUser(em, hrUser, "Hanne", "HR");
            P8ProfileFixtures.insertUser(em, adminUser, "Alma", "Admin");
            P8ProfileFixtures.insertRole(em, assistantUser, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, namedTeamlead, "TEAMLEAD");
            // Additive-role regression: assistant standing must not narrow a
            // simultaneously eligible named TEAMLEAD.
            P8ProfileFixtures.insertRole(em, namedTeamlead, "ASSISTANT_TEAMLEAD");
            P8ProfileFixtures.insertRole(em, unnamedTeamlead, "TEAMLEAD");
            P8ProfileFixtures.insertRole(em, recruitmentUser, "RECRUITMENT");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");
            P8ProfileFixtures.insertRole(em, adminUser, "ADMIN");

            P8ProfileFixtures.insertPractice(em, practiceUuid);
            setUserPractice(assistantUser, practiceUuid);
            setUserPractice(namedTeamlead, practiceUuid);

            // The assistant is deliberately a named owner too: ownership
            // alone must not open dossier/onboarding files.
            P8ProfileFixtures.insertPosition(em, assistantPosition, "Assistant-owned hire",
                    "PRACTICE_TEAM", practiceUuid, null, assistantUser);
            P8ProfileFixtures.insertPosition(em, teamleadPosition, "Teamlead-owned hire",
                    "PRACTICE_TEAM", practiceUuid, null, namedTeamlead);
            P8ProfileFixtures.insertCandidate(em, candidateUuid,
                    "PII_SENTINEL Dana", "PII_SENTINEL Documents", "ACTIVE",
                    null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, foreignCandidateUuid,
                    "PII_SENTINEL Foreign", "PII_SENTINEL Candidate", "ACTIVE",
                    null, null, hrUser);
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    candidateUuid, assistantPosition, "SCREENING");
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    candidateUuid, teamleadPosition, "SCREENING");

            filesByKind.forEach((kind, fileUuid) -> {
                String filename = kind.toLowerCase() + ".pdf";
                P8ProfileFixtures.insertFileRow(em, fileUuid, candidateUuid, filename);
                P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidateUuid,
                        null, null, "CANDIDATE", null, "NORMAL",
                        "{\"file_uuid\":\"" + fileUuid + "\",\"kind\":\"" + kind + "\","
                                + "\"origin\":\"authz-test\",\"content_type\":\"application/pdf\"}",
                        "{\"filename\":\"" + filename + "\"}");

                File stored = new File();
                stored.setUuid(fileUuid);
                stored.setRelateduuid(candidateUuid);
                stored.setFilename(filename);
                stored.setFile(("bytes-" + kind).getBytes(StandardCharsets.UTF_8));
                Mockito.when(s3FileService.findOne(fileUuid)).thenReturn(stored);
            });

            // A manually classified restricted document: its upload itself
            // was unclassified, so it remains kind-editable. This is the
            // downgrade regression fixture; dossier write, not editability,
            // must decide who can move it back to an ordinary kind.
            P8ProfileFixtures.insertFileRow(em, editableRestrictedFile,
                    candidateUuid, "editable-restricted.pdf");
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidateUuid,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + editableRestrictedFile
                            + "\",\"kind\":\"OTHER\",\"origin\":\"authz-test\","
                            + "\"content_type\":\"application/pdf\"}",
                    "{\"filename\":\"editable-restricted.pdf\"}");
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_KIND_CHANGED", candidateUuid,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + editableRestrictedFile
                            + "\",\"kind\":\"CONTRACT_DRAFT\","
                            + "\"previous_kind\":\"OTHER\",\"origin\":\"manual\"}",
                    null);

            // The latest display label is ordinary, but an earlier override
            // classified this file as contract material. Security must remain
            // restricted monotonically across the whole event history.
            P8ProfileFixtures.insertFileRow(em, historicallyRestrictedFile,
                    candidateUuid, "historically-restricted.pdf");
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidateUuid,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + historicallyRestrictedFile
                            + "\",\"kind\":\"OTHER\",\"origin\":\"authz-test\","
                            + "\"content_type\":\"application/pdf\"}",
                    "{\"filename\":\"historically-restricted.pdf\"}");
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_KIND_CHANGED", candidateUuid,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + historicallyRestrictedFile
                            + "\",\"kind\":\"CONTRACT_DRAFT\","
                            + "\"previous_kind\":\"OTHER\",\"origin\":\"manual\"}",
                    null);
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_KIND_CHANGED", candidateUuid,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + historicallyRestrictedFile
                            + "\",\"kind\":\"OTHER\","
                            + "\"previous_kind\":\"CONTRACT_DRAFT\",\"origin\":\"manual\"}",
                    null);
            File historicallyRestricted = new File();
            historicallyRestricted.setUuid(historicallyRestrictedFile);
            historicallyRestricted.setRelateduuid(candidateUuid);
            historicallyRestricted.setFilename("historically-restricted.pdf");
            historicallyRestricted.setFile("historically-restricted-bytes"
                    .getBytes(StandardCharsets.UTF_8));
            Mockito.when(s3FileService.findOne(historicallyRestrictedFile))
                    .thenReturn(historicallyRestricted);

            // Exists, but belongs to another candidate. Kind-change requests
            // through this candidate URL must be indistinguishable from an
            // unknown UUID and a hidden restricted file.
            P8ProfileFixtures.insertFileRow(em, foreignFileUuid,
                    foreignCandidateUuid, "foreign.pdf");

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidateUuid, foreignCandidateUuid),
                    List.of(assistantPosition, teamleadPosition),
                    List.of(assistantUser, namedTeamlead, unnamedTeamlead,
                            recruitmentUser, hrUser, adminUser),
                    practiceUuid);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void profileReadersWithoutDossierCapability_listOnlyOrdinaryDocuments() {
        assertListedKinds(assistantUser, ORDINARY_KINDS);
        assertListedKinds(unnamedTeamlead, ORDINARY_KINDS);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void dossierReaders_listAllDocuments_includingAdditiveNamedTeamlead() {
        List<String> allKinds = new java.util.ArrayList<>(filesByKind.keySet());
        allKinds.add(CandidateDocumentClassifier.KIND_CONTRACT_DRAFT);
        allKinds.add(CandidateDocumentClassifier.KIND_OTHER);
        assertListedKinds(namedTeamlead, allKinds);
        assertListedKinds(hrUser, allKinds);
        assertListedKinds(adminUser, allKinds);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistantNamedOwner_cannotDirectDownloadRestrictedKinds_andStorageIsUntouched() {
        for (String kind : RESTRICTED_KINDS) {
            String fileUuid = filesByKind.get(kind);
            download(assistantUser, fileUuid, 404);
            Mockito.verify(s3FileService, Mockito.never()).findOne(eq(fileUuid));
        }
        download(assistantUser, UUID.randomUUID().toString(), 404);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void laterOrdinaryOverrideDoesNotExposeHistoricallyRestrictedDocument() {
        assertListedKinds(assistantUser, ORDINARY_KINDS);
        assertListedKinds(recruitmentUser, ORDINARY_KINDS);
        download(assistantUser, historicallyRestrictedFile, 404);
        download(recruitmentUser, historicallyRestrictedFile, 404);
        Mockito.verify(s3FileService, Mockito.never())
                .findOne(eq(historicallyRestrictedFile));

        download(namedTeamlead, historicallyRestrictedFile, 200);
        download(hrUser, historicallyRestrictedFile, 200);
        download(adminUser, historicallyRestrictedFile, 200);
    }

    @Test
    void restrictedBriefDoesNotExposeHistoricallyRestrictedOrdinaryDisplayLabel() {
        assertFalse(candidateBriefService.briefDocuments(candidateUuid).stream()
                        .anyMatch(document -> historicallyRestrictedFile.equals(document.fileUuid())),
                "the restricted brief must apply monotonic dossier classification before its "
                        + "ordinary-kind allow-list");
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void assistant_canDirectDownloadOrdinaryDocuments() {
        for (String kind : ORDINARY_KINDS) {
            download(assistantUser, filesByKind.get(kind), 200);
        }
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void eligibleNamedTeamlead_hrAndAdmin_canDirectDownloadRestrictedDocuments() {
        for (String kind : RESTRICTED_KINDS) {
            download(namedTeamlead, filesByKind.get(kind), 200);
        }
        download(hrUser, filesByKind.get(CandidateDocumentClassifier.KIND_CONTRACT_DRAFT), 200);
        download(adminUser, filesByKind.get(CandidateDocumentClassifier.KIND_ID_DOCUMENT), 200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantAndRecruitmentCannotPromoteOrdinaryDocumentsToRestrictedKinds() {
        for (String viewer : List.of(assistantUser, recruitmentUser)) {
            changeKind(viewer,
                    filesByKind.get(CandidateDocumentClassifier.KIND_OTHER),
                    CandidateDocumentClassifier.KIND_CONTRACT_DRAFT,
                    403);
        }
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void restrictedCurrentUnknownAndForeignKindChangesAreUniform404ForNonWriters() {
        String unknownFileUuid = UUID.randomUUID().toString();
        for (String viewer : List.of(assistantUser, recruitmentUser)) {
            changeKind(viewer,
                    editableRestrictedFile,
                    CandidateDocumentClassifier.KIND_OTHER,
                    404);
            changeKind(viewer,
                    historicallyRestrictedFile,
                    CandidateDocumentClassifier.KIND_CV,
                    404);
            changeKind(viewer,
                    unknownFileUuid,
                    CandidateDocumentClassifier.KIND_CV,
                    404);
            changeKind(viewer,
                    foreignFileUuid,
                    CandidateDocumentClassifier.KIND_CV,
                    404);
        }
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void hrAndAdminCanPromoteAndDowngradeRestrictedClassifications() {
        changeKind(hrUser,
                filesByKind.get(CandidateDocumentClassifier.KIND_OTHER),
                CandidateDocumentClassifier.KIND_CONTRACT_DRAFT,
                200);
        changeKind(adminUser,
                editableRestrictedFile,
                CandidateDocumentClassifier.KIND_OTHER,
                200);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void assistantCanStillRetypeOrdinaryDocuments() {
        changeKind(assistantUser,
                filesByKind.get(CandidateDocumentClassifier.KIND_OTHER),
                CandidateDocumentClassifier.KIND_CV,
                200);
    }

    private void assertListedKinds(String viewer, List<String> expectedKinds) {
        given().header("X-Requested-By", viewer)
                .when().get("/recruitment/candidates/{uuid}/documents", candidateUuid)
                .then().statusCode(200)
                .body("documents", Matchers.hasSize(expectedKinds.size()))
                .body("documents.kind", Matchers.containsInAnyOrder(expectedKinds.toArray()));
    }

    private void download(String viewer, String fileUuid, int expectedStatus) {
        given().header("X-Requested-By", viewer)
                .when().get("/recruitment/candidates/{uuid}/documents/{fileUuid}",
                        candidateUuid, fileUuid)
                .then().statusCode(expectedStatus);
    }

    private void changeKind(String viewer, String fileUuid, String kind, int expectedStatus) {
        var response = given().header("X-Requested-By", viewer)
                .contentType("application/json")
                .body(Map.of("kind", kind))
                .when().put("/recruitment/candidates/{uuid}/documents/{fileUuid}/kind",
                        candidateUuid, fileUuid)
                .then().statusCode(expectedStatus);
        if (expectedStatus == 200) {
            response.body("kind", Matchers.equalTo(kind));
        }
    }

    private void setUserPractice(String userUuid, String userPracticeUuid) {
        em.createNativeQuery("UPDATE user SET practice_uuid = :practice WHERE uuid = :user")
                .setParameter("practice", userPracticeUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }
}
