package dk.trustworks.intranet.aggregates.finance.resources;

import dk.trustworks.intranet.aggregates.finance.services.OpexDistributionRefreshService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test for the EBITDA Forecast endpoint, exercising the new
 * read-from-mat-table path introduced in PR 2. Locks in the user-visible
 * perf goal so a future regression of the read path is caught in CI.
 *
 * <p>Spec: docs/superpowers/specs/2026-05-11-fact-opex-distribution-mat-design.md
 * § 7 test #4.
 */
@QuarkusTest
class CxoFinanceResourceEbitdaSmokeIT {

    @Inject
    OpexDistributionRefreshService refreshService;

    @BeforeEach
    @Transactional
    void warmTable() {
        refreshService.refresh();
    }

    @Test
    @TestSecurity(user = "smoke-test", roles = {"dashboard:read"})
    void getExpectedAccumulatedEBITDA_returns_12_data_points() {
        Response r = given()
                .when()
                .get("/finance/cxo/expected-accumulated-ebitda")
                .then()
                .statusCode(200)
                .extract().response();

        assertEquals(12, r.jsonPath().getList(".").size(),
                "EBITDA forecast must return 12 monthly data points");
    }

    @Test
    @TestSecurity(user = "smoke-test", roles = {"dashboard:read"})
    void getExpectedAccumulatedEBITDA_p95_under_2000ms() {
        long[] timings = new long[20];
        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            given()
                    .when()
                    .get("/finance/cxo/expected-accumulated-ebitda")
                    .then()
                    .statusCode(200);
            timings[i] = (System.nanoTime() - start) / 1_000_000L;
        }
        Arrays.sort(timings);
        long p95 = timings[19];  // p95 of 20 samples ≈ max
        assertTrue(p95 < 2000L,
                "EBITDA endpoint p95 was " + p95 + "ms (must be < 2000ms)");
    }
}
