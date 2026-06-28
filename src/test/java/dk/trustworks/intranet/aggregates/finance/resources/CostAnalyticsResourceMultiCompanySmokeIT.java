package dk.trustworks.intranet.aggregates.finance.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Resource-level smoke test for the multi-company filter fix (TASK F1).
 *
 * <p>Hits an affected endpoint with a comma-joined {@code companyIds} value
 * (A/S,Tech) and asserts HTTP 200 + a non-null JSON body. This locks in that
 * the {@code String} + {@code parseCommaSeparated} binding accepts multi-company
 * input through the JAX-RS layer without error — the previous {@code Set<String>}
 * binding produced a single-element set and silently zeroed the chart.
 *
 * <p>Exact figures are validated separately against the prod oracle
 * (SUM(net_revenue_dkk) WHERE company_id IN ('A/S','Tech') FY25/26 = 143,045,744);
 * the test DB profile carries no such fixture, so this test asserts only the
 * HTTP contract (200 + body shape).
 */
@QuarkusTest
class CostAnalyticsResourceMultiCompanySmokeIT {

    private static final String AS = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String TECH = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    @Test
    @TestSecurity(user = "smoke-test", roles = {"dashboard:read"})
    void revenuePerFte_withTwoCompanies_returns200AndBody() {
        given()
                .when()
                .get("/finance/analytics/revenue-per-fte?companyIds=" + AS + "," + TECH)
                .then()
                .statusCode(200)
                .body(notNullValue());
    }
}
