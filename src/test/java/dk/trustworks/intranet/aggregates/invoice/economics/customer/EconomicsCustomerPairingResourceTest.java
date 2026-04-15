package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.AutoRunResultDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingCandidateDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRowDto;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REST contract tests for {@link EconomicsCustomerPairingResource}. Mocks the
 * service with {@code @InjectMock} so the full backing agreement / index
 * machinery is not exercised here.
 */
@QuarkusTest
class EconomicsCustomerPairingResourceTest {

    private static final UUID COMPANY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID CLIENT_UUID  = UUID.fromString("00000000-0000-0000-0000-00000000000c");

    @InjectMock
    EconomicsCustomerPairingService service;

    // --------------------------------------------------- GET /pairing

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:read"})
    void get_pairing_returns_rows() {
        when(service.listPairingRows(COMPANY_UUID)).thenReturn(List.of(
                new PairingRowDto(CLIENT_UUID.toString(), "Acme A/S", "12345678",
                        "CLIENT", "PAIRED", PairingSource.AUTO_CVR, 101, List.of())));

        given().queryParam("companyUuid", COMPANY_UUID.toString())
                .when().get("/economics/customers/pairing")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].clientUuid", equalTo(CLIENT_UUID.toString()))
                .body("[0].pairingStatus", equalTo("PAIRED"))
                .body("[0].economicsCustomerNumber", equalTo(101));
    }

    @Test
    void get_pairing_requires_auth() {
        given().queryParam("companyUuid", COMPANY_UUID.toString())
                .when().get("/economics/customers/pairing")
                .then().statusCode(401);
    }

    // --------------------------------------------------- POST /pair

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:write"})
    void post_pair_delegates_to_service() {
        String body = """
                {
                  "clientUuid":"%s",
                  "companyUuid":"%s",
                  "economicsCustomerNumber":42,
                  "pairingSource":"MANUAL"
                }
                """.formatted(CLIENT_UUID, COMPANY_UUID);

        given().contentType("application/json").body(body)
                .when().post("/economics/customers/pair")
                .then().statusCode(204);

        verify(service).pairManually(any());
    }

    @Test
    @TestSecurity(user = "reader", roles = {"invoices:read"})
    void post_pair_as_read_only_is_forbidden() {
        String body = """
                {"clientUuid":"%s","companyUuid":"%s","economicsCustomerNumber":42,"pairingSource":"MANUAL"}
                """.formatted(CLIENT_UUID, COMPANY_UUID);

        given().contentType("application/json").body(body)
                .when().post("/economics/customers/pair")
                .then().statusCode(403);
    }

    // --------------------------------------------------- DELETE /pair

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:write"})
    void delete_pair_delegates_to_service() {
        given().queryParam("clientUuid", CLIENT_UUID.toString())
                .queryParam("companyUuid", COMPANY_UUID.toString())
                .when().delete("/economics/customers/pair")
                .then().statusCode(204);

        verify(service).unpair(eq(CLIENT_UUID), eq(COMPANY_UUID));
    }

    // --------------------------------------------------- POST /pair/auto-run

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:write"})
    void auto_run_returns_result_dto() {
        when(service.autoRun(COMPANY_UUID)).thenReturn(new AutoRunResultDto(3, 2, 1, 4, List.of()));

        given().queryParam("companyUuid", COMPANY_UUID.toString())
                .when().post("/economics/customers/pair/auto-run")
                .then().statusCode(200)
                .body("paired",    equalTo(3))
                .body("unchanged", equalTo(2))
                .body("ambiguous", equalTo(1))
                .body("unmatched", equalTo(4));
    }

    // --------------------------------------------------- GET /search

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:read"})
    void search_returns_candidates() {
        when(service.searchEconomicsCustomers(eq(COMPANY_UUID), eq("acme"))).thenReturn(List.of(
                new PairingCandidateDto(101, "Acme A/S", "12345678", "NAME")));

        given().queryParam("companyUuid", COMPANY_UUID.toString())
                .queryParam("q", "acme")
                .when().get("/economics/customers/search")
                .then().statusCode(200)
                .body("$", hasSize(1))
                .body("[0].customerNumber", equalTo(101))
                .body("[0].matchReason", equalTo("NAME"));
    }

    // --------------------------------------------------- POST /pair/{c}/{co}/create

    @Test
    @TestSecurity(user = "admin", roles = {"invoices:write"})
    void create_and_pair_returns_pairing_row() {
        when(service.createAndPair(eq(CLIENT_UUID), eq(COMPANY_UUID))).thenReturn(
                new PairingRowDto(CLIENT_UUID.toString(), "Acme A/S", "12345678",
                        "CLIENT", "PAIRED", PairingSource.CREATED, 555, List.of()));

        given().when().post("/economics/customers/pair/"
                        + CLIENT_UUID + "/" + COMPANY_UUID + "/create")
                .then().statusCode(201)
                .body("economicsCustomerNumber", equalTo(555))
                .body("pairingSource", equalTo("CREATED"));
    }
}
