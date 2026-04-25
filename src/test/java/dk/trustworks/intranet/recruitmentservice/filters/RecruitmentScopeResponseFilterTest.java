package dk.trustworks.intranet.recruitmentservice.filters;

import dk.trustworks.intranet.recruitmentservice.domain.entities.Candidate;
import dk.trustworks.intranet.recruitmentservice.domain.enums.CandidateState;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Verifies {@link RecruitmentScopeResponseFilter} masks consent fields based on caller scopes.
 *
 * <p>Tests use the {@code X-Requested-By} header to set
 * {@link dk.trustworks.intranet.security.RequestHeaderHolder#getUserUuid()} to the candidate's
 * owner — that gives the caller record-level access via owner-check regardless of scope, so we
 * isolate the mask layer from {@link dk.trustworks.intranet.recruitmentservice.application.RecruitmentRecordAccessService}.
 */
@QuarkusTest
class RecruitmentScopeResponseFilterTest {

    private static String SEEDED_UUID;
    private static final String OWNER_UUID = UUID.randomUUID().toString();

    @BeforeEach
    @Transactional
    void seed() {
        if (SEEDED_UUID == null || Candidate.findById(SEEDED_UUID) == null) {
            Candidate c = Candidate.withFreshUuid();
            c.firstName = "Pat";
            c.lastName = "Doe";
            c.email = "pat-filtertest@example.com";
            c.consentStatus = "GIVEN";
            c.consentGivenAt = java.time.LocalDateTime.now();
            c.state = CandidateState.ACTIVE;
            c.ownerUserUuid = OWNER_UUID;
            c.persist();
            SEEDED_UUID = c.uuid;
        }
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read"})
    void readOnlyOwnerSeesNoConsentFieldsOnCandidate() {
        // Owner reaches the row (record-access OK); :read alone fails mask gate → consent stripped.
        given().header("X-Requested-By", OWNER_UUID)
                .when().get("/api/recruitment/candidates/" + SEEDED_UUID)
                .then().statusCode(200)
                .body("consentStatus", nullValue())
                .body("consentGivenAt", nullValue());
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void writerOwnerSeesConsentFields() {
        given().header("X-Requested-By", OWNER_UUID)
                .when().get("/api/recruitment/candidates/" + SEEDED_UUID)
                .then().statusCode(200)
                .body("consentStatus", notNullValue());
    }

    @Test
    @TestSecurity(user = "admin", roles = {"recruitment:read", "recruitment:admin"})
    void adminSeesConsentFields() {
        // admin bypasses record-access too, so X-Requested-By isn't required, but include it for symmetry.
        given().header("X-Requested-By", OWNER_UUID)
                .when().get("/api/recruitment/candidates/" + SEEDED_UUID)
                .then().statusCode(200)
                .body("consentStatus", notNullValue());
    }
}
