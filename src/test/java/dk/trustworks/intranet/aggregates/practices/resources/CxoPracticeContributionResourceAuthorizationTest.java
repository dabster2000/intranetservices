package dk.trustworks.intranet.aggregates.practices.resources;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.services.CxoPracticeContributionService;
import dk.trustworks.intranet.aggregates.practices.services.PracticeContributionReadTransactionRunner;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** HTTP authorization boundary for the aggregate-only contribution endpoint. */
@QuarkusTest
class CxoPracticeContributionResourceAuthorizationTest {

    @InjectMock
    CxoPracticeContributionService service;

    @Inject
    PracticeContributionReadTransactionRunner readTransactions;

    @Test
    void configuredDatasourceActuallyOpensRepeatableReadTransactions() {
        org.junit.jupiter.api.Assertions.assertEquals(
                "repeatable-read",
                readTransactions.requiringNew(2, () -> "repeatable-read"));
    }

    @Test
    void anonymousRequestIsDenied() {
        given()
                .queryParam("costSource", "BOOKED")
        .when()
                .get("/practices/cxo/contribution")
        .then()
                .statusCode(401);
    }

    @Test
    @TestSecurity(user = "cost-reader", roles = {"accounting:read"})
    void authenticatedRequestWithoutDashboardReadIsDenied() {
        given()
                .queryParam("costSource", "BOOKED")
        .when()
                .get("/practices/cxo/contribution")
        .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "dashboard-reader", roles = {"dashboard:read"})
    void dashboardReadRequestIsAllowed() {
        when(service.getContribution(CostSource.BOOKED)).thenReturn(availableResponse());

        given()
                .queryParam("costSource", "BOOKED")
        .when()
                .get("/practices/cxo/contribution")
        .then()
                .statusCode(200)
                .body("costSource", equalTo("BOOKED"));

        verify(service).getContribution(CostSource.BOOKED);
    }

    private static PracticeContributionResponseDTO availableResponse() {
        return new PracticeContributionResponseDTO(
                "CONSOLIDATED",
                "NET_ATTRIBUTED",
                "BOOKED",
                "AVAILABLE",
                null,
                "2026-06",
                null,
                null,
                "test-generation",
                null,
                null,
                "test-refresh",
                Map.of(),
                null,
                null,
                "test-practice-basis",
                "DELIVERY_EVIDENCE",
                "SIGNED_OPERATING_COST",
                null,
                null,
                true,
                "ALIGNED",
                null,
                null,
                List.of(),
                List.of());
    }
}
