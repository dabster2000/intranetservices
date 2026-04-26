package dk.trustworks.intranet.recruitmentservice.api;

import dk.trustworks.intranet.recruitmentservice.AiEnabledTestProfile;
import dk.trustworks.intranet.recruitmentservice.application.CvFileExtractor;
import dk.trustworks.intranet.recruitmentservice.application.CvFileStorageService;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Resource-level test for {@link CandidateCvResource} (spec §6.3).
 *
 * <p>Mocking strategy mirrors {@link dk.trustworks.intranet.recruitmentservice.application.CvUploadServiceTest}:
 * we mock {@link CvFileStorageService} (so SharePoint is never contacted) and
 * {@link CvFileExtractor} (so PDFBox/POI is not exercised here — the dedicated extractor
 * test covers that). Real {@link dk.trustworks.intranet.recruitmentservice.application.CvUploadService}
 * runs end-to-end so the integration with {@link dk.trustworks.intranet.recruitmentservice.application.AiArtifactService}
 * is exercised, including the persisted artifact + outbox row.
 *
 * <p>Uses {@link AiEnabledTestProfile} so {@code recruitment.ai.enabled} and
 * {@code recruitment.ai.cv-extraction.enabled} are both true; otherwise
 * {@code AiArtifactService.requestArtifact} refuses with 503.
 */
@QuarkusTest
@TestProfile(AiEnabledTestProfile.class)
class CandidateCvResourceTest {

    private static final String TAM_UUID = "00000000-0000-0000-0000-000000000010";

    @InjectMock CvFileStorageService storage;
    @InjectMock CvFileExtractor extractor;

    @BeforeEach
    void wireMocks() {
        when(storage.store(anyString(), anyString(), anyString(), any(byte[].class)))
                .thenReturn("https://sp/x");
        when(storage.sha256(any(byte[].class))).thenReturn("a".repeat(64));
        when(extractor.extract(any(byte[].class), anyString())).thenReturn("Alice Example. Java...");
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:read", "recruitment:admin"})
    void uploadCv_createsArtifact_returns201() throws Exception {
        String candidateUuid = seedCandidate();
        byte[] pdf = readFixture("recruitment/cv-fixtures/sample-consultant-cv.pdf");

        given()
                .header("X-Requested-By", TAM_UUID)
                .multiPart("file", "alice.pdf", pdf, "application/pdf")
                .when().post("/api/recruitment/candidates/" + candidateUuid + "/cv")
                .then().statusCode(201)
                .body("uuid", notNullValue())
                .body("extractionArtifactUuid", notNullValue())
                .body("isCurrent", is(true))
                .body("fileSha256", matchesPattern("[0-9a-f]{64}"));
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:read", "recruitment:admin"})
    void getExtraction_returnsArtifactState() throws Exception {
        String candidateUuid = seedCandidateWithCv();

        given()
                .header("X-Requested-By", TAM_UUID)
                .when().get("/api/recruitment/candidates/" + candidateUuid + "/cv/extraction")
                .then().statusCode(200)
                .body("state", oneOf("GENERATING", "GENERATED", "REVIEWED", "OVERRIDDEN", "FAILED"));
    }

    @Test
    @TestSecurity(user = "noscope", roles = {})
    void uploadCv_withoutScope_returns403() {
        given()
                .multiPart("file", "x.pdf", new byte[]{1}, "application/pdf")
                .when().post("/api/recruitment/candidates/" + java.util.UUID.randomUUID() + "/cv")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write", "recruitment:read", "recruitment:admin"})
    void uploadCv_unsupportedType_returns400() {
        // Override the extractor mock for this test to throw the same IAE the real
        // extractor throws on a non-{pdf,docx} extension. The resource must catch
        // and remap to 400 — verifying that contract is the point of this test.
        when(extractor.extract(any(byte[].class), anyString()))
                .thenThrow(new IllegalArgumentException("unsupported file type: x.exe (only .pdf and .docx)"));

        String candidateUuid = seedCandidate();

        given()
                .header("X-Requested-By", TAM_UUID)
                .multiPart("file", "x.exe", new byte[]{1, 2, 3}, "application/octet-stream")
                .when().post("/api/recruitment/candidates/" + candidateUuid + "/cv")
                .then().statusCode(400);
    }

    /** Seed a candidate via REST POST and return its UUID. */
    private String seedCandidate() {
        String body = """
            {"firstName":"Alice","lastName":"Example","email":"alice@example.com","desiredPractice":"DEV"}""";
        return given().contentType("application/json").body(body)
                .header("X-Requested-By", TAM_UUID)
                .when().post("/api/recruitment/candidates")
                .then().statusCode(201)
                .extract().path("uuid");
    }

    /** Seed a candidate and upload a CV — the upload triggers AI artifact creation. */
    private String seedCandidateWithCv() throws Exception {
        String candidateUuid = seedCandidate();
        byte[] pdf = readFixture("recruitment/cv-fixtures/sample-consultant-cv.pdf");
        given()
                .header("X-Requested-By", TAM_UUID)
                .multiPart("file", "alice.pdf", pdf, "application/pdf")
                .when().post("/api/recruitment/candidates/" + candidateUuid + "/cv")
                .then().statusCode(201);
        return candidateUuid;
    }

    private static byte[] readFixture(String classpathPath) throws Exception {
        try (var in = Objects.requireNonNull(
                Thread.currentThread().getContextClassLoader().getResourceAsStream(classpathPath),
                "fixture not on classpath: " + classpathPath)) {
            return in.readAllBytes();
        }
    }
}
