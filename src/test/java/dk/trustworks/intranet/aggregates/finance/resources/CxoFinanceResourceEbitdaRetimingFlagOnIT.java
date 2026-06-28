package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F3 — flag-ON smoke for the Expected Accumulated EBITDA endpoint. Enables
 * {@code finance.internal-cost-timing-alignment.enabled=true} via a test profile and proves the
 * re-timing path executes cleanly end-to-end (HTTP 200, 12 monthly data points, valid shape).
 *
 * <p>This guards the ON branch — the two new native queries (synthesized CREATED-internal cost +
 * GL CREATED-internal cost) and the per-month adjustment must run against the real schema without
 * error. Exact production numbers are validated by the orchestrator against prod; under the
 * lightweight test DB this asserts contract stability only.
 */
@QuarkusTest
@TestProfile(CxoFinanceResourceEbitdaRetimingFlagOnIT.RetimingOn.class)
class CxoFinanceResourceEbitdaRetimingFlagOnIT {

    public static class RetimingOn implements QuarkusTestProfile {
        @Override public Map<String, String> getConfigOverrides() {
            return Map.of("finance.internal-cost-timing-alignment.enabled", "true");
        }
    }

    @Inject
    OpexDistributionRefreshService refreshService;

    @BeforeEach
    @Transactional
    void warmTable() {
        refreshService.refresh();
    }

    @Test
    void profileEnablesFlag() {
        boolean enabled = ConfigProvider.getConfig()
                .getValue("finance.internal-cost-timing-alignment.enabled", Boolean.class);
        assertTrue(enabled, "Test profile must turn the F3 flag ON via the exact property name");
    }

    @Test
    @TestSecurity(user = "smoke-test", roles = {"dashboard:read"})
    void getExpectedAccumulatedEBITDA_flagOn_returns12DataPoints() {
        Response r = given()
                .when()
                .get("/finance/cxo/expected-accumulated-ebitda")
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals(12, r.jsonPath().getList(".").size(),
                "EBITDA forecast must still return 12 monthly data points with F3 re-timing ON");
    }
}
