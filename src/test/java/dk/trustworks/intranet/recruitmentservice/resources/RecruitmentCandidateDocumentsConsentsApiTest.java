package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.services.S3FileService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * P8 DoD (documents + consents):
 * <ul>
 *   <li>the documents list enriches {@code files} rows from their
 *       {@code DOCUMENT_UPLOADED} events (kind, origin, content type, size,
 *       {@code duplicateReason} from {@code payload.reason}); files without
 *       a matching event render as kind {@code OTHER};</li>
 *   <li>the download leg streams bytes through the existing S3 storage
 *       service with the stored filename;</li>
 *   <li>the IDOR guard: a real file whose {@code relateduuid} belongs to
 *       another candidate answers 404 — and never touches S3;</li>
 *   <li>the consents tab exposes exactly the contract's five fields and
 *       {@code token_hash} never serializes.</li>
 * </ul>
 */
@QuarkusTest
class RecruitmentCandidateDocumentsConsentsApiTest {

    // Two DISTINCT sentinels since P19: token_hash carries a UNIQUE index
    // (V448 — the consent-page lookup), so two rows can no longer share one.
    private static final String TOKEN_HASH_SENTINEL =
            "TOKEN_HASH_SENTINEL_0123456789abcdef0123456789abcdef";
    private static final String TOKEN_HASH_SENTINEL_2 =
            "TOKEN_HASH_SENTINEL_fedcba9876543210fedcba9876543210";

    @Inject
    EntityManager em;

    @InjectMock
    S3FileService s3FileService;

    private String hrUser;
    private String candidate;
    private String otherCandidate;
    private String cvFile;
    private String plainFile;
    private String foreignFile;

    private String previousFlag;

    @BeforeEach
    void seed() {
        hrUser = UUID.randomUUID().toString();
        candidate = UUID.randomUUID().toString();
        otherCandidate = UUID.randomUUID().toString();
        cvFile = UUID.randomUUID().toString();
        plainFile = UUID.randomUUID().toString();
        foreignFile = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");

            P8ProfileFixtures.insertCandidate(em, candidate,
                    "PII_SENTINEL Dora", "PII_SENTINEL Dokument", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, otherCandidate,
                    "PII_SENTINEL Frede", "PII_SENTINEL Fremmed", "ACTIVE", null, null, hrUser);

            P8ProfileFixtures.insertFileRow(em, cvFile, candidate, "cv.pdf");
            P8ProfileFixtures.insertFileRow(em, plainFile, candidate, "underskrevet-kontrakt.pdf");
            P8ProfileFixtures.insertFileRow(em, foreignFile, otherCandidate, "cv.pdf");

            // P5-shaped upload event: kind/origin/reason/content_type/size in
            // payload, the raw filename in pii.
            P8ProfileFixtures.insertEvent(em, "DOCUMENT_UPLOADED", candidate,
                    null, null, "CANDIDATE", null, "NORMAL",
                    "{\"file_uuid\":\"" + cvFile + "\",\"kind\":\"CV\","
                            + "\"origin\":\"public_form\","
                            + "\"reason\":\"DUPLICATE_PUBLIC_SUBMISSION\","
                            + "\"content_type\":\"application/pdf\",\"size_bytes\":12345}",
                    "{\"filename\":\"PII_SENTINEL mit rigtige cv.pdf\"}");

            P8ProfileFixtures.insertConsent(em, UUID.randomUUID().toString(), candidate,
                    "TALENT_POOL_RETENTION", "GRANTED",
                    "2026-01-10 12:00:00", "2027-01-10 12:00:00", TOKEN_HASH_SENTINEL);
            P8ProfileFixtures.insertConsent(em, UUID.randomUUID().toString(), candidate,
                    "TALENT_POOL_RETENTION", "REQUESTED", null, null, TOKEN_HASH_SENTINEL_2);

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(candidate, otherCandidate),
                    List.of(),
                    List.of(hrUser),
                    null);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Documents list -----------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void documents_enrichedFromUploadEvents_unmatchedFilesRenderAsOther() {
        String cvPath = "documents.find { it.fileUuid == '" + cvFile + "' }";
        String plainPath = "documents.find { it.fileUuid == '" + plainFile + "' }";
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/documents", candidate)
                .then()
                .statusCode(200)
                .body("documents", Matchers.hasSize(2))
                // Never another candidate's files.
                .body("documents.fileUuid", Matchers.not(Matchers.hasItem(foreignFile)))
                // Event-enriched row: kind, origin, duplicate reason, sizes.
                .body(cvPath + ".filename", Matchers.equalTo("cv.pdf"))
                .body(cvPath + ".kind", Matchers.equalTo("CV"))
                .body(cvPath + ".origin", Matchers.equalTo("public_form"))
                .body(cvPath + ".duplicateReason",
                        Matchers.equalTo("DUPLICATE_PUBLIC_SUBMISSION"))
                .body(cvPath + ".contentType", Matchers.equalTo("application/pdf"))
                .body(cvPath + ".sizeBytes", Matchers.equalTo(12345))
                .body(cvPath + ".uploadedAt", Matchers.notNullValue())
                // No matching event → OTHER with null enrichment.
                .body(plainPath + ".kind", Matchers.equalTo("OTHER"))
                .body(plainPath + ".origin", Matchers.nullValue())
                .body(plainPath + ".duplicateReason", Matchers.nullValue())
                .body(plainPath + ".contentType", Matchers.nullValue());
    }

    // ---- Download -----------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void download_streamsBytes_withStoredFilenameAndEventContentType() {
        byte[] bytes = "P8-PDF-BYTES".getBytes(StandardCharsets.UTF_8);
        File stub = new File();
        stub.setUuid(cvFile);
        stub.setRelateduuid(candidate);
        stub.setFilename("cv.pdf");
        stub.setFile(bytes);
        Mockito.when(s3FileService.findOne(cvFile)).thenReturn(stub);

        Response response = given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/documents/{fileUuid}",
                        candidate, cvFile);
        response.then()
                .statusCode(200)
                .header("Content-Disposition", Matchers.containsString("cv.pdf"))
                .contentType(Matchers.containsString("application/pdf"));
        assertArrayEquals(bytes, response.asByteArray());
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void download_idorMismatchAndUnknownFile_answer404_withoutTouchingS3() {
        // A REAL file that belongs to another candidate: 404, not 403 —
        // and the guard fires before any S3 round-trip.
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/documents/{fileUuid}",
                        candidate, foreignFile)
                .then().statusCode(404);
        // A nonexistent file uuid answers the same 404.
        given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/documents/{fileUuid}",
                        candidate, UUID.randomUUID().toString())
                .then().statusCode(404);
        Mockito.verify(s3FileService, Mockito.never()).findOne(anyString());
    }

    // ---- Consents -----------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read"})
    void consents_exposeContractFields_andNeverTheTokenHash() {
        String granted = "consents.find { it.status == 'GRANTED' }";
        String requested = "consents.find { it.status == 'REQUESTED' }";
        Response response = given().header("X-Requested-By", hrUser)
                .when().get("/recruitment/candidates/{uuid}/consents", candidate);
        response.then()
                .statusCode(200)
                .body("consents", Matchers.hasSize(2))
                .body(granted + ".kind", Matchers.equalTo("TALENT_POOL_RETENTION"))
                .body(granted + ".requestedAt", Matchers.notNullValue())
                .body(granted + ".grantedAt", Matchers.startsWith("2026-01-10"))
                .body(granted + ".expiresAt", Matchers.startsWith("2027-01-10"))
                .body(requested + ".grantedAt", Matchers.nullValue())
                .body(requested + ".expiresAt", Matchers.nullValue());

        String body = response.asString();
        assertFalse(body.contains(TOKEN_HASH_SENTINEL),
                "token_hash value must never serialize");
        assertFalse(body.contains(TOKEN_HASH_SENTINEL_2),
                "token_hash value must never serialize (REQUESTED row)");
        assertFalse(body.toLowerCase(Locale.ROOT).contains("token"),
                "no token-ish key may appear in the consents JSON");
        assertTrue(body.contains("\"kind\""), "sanity: contract fields present");
    }
}
