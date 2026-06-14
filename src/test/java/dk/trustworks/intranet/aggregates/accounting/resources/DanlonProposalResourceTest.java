package dk.trustworks.intranet.aggregates.accounting.resources;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonAssignmentService;
import dk.trustworks.intranet.aggregates.users.danlon.DanlonEventType;
import dk.trustworks.intranet.domain.user.entity.DanlonAssignmentProposal;
import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class DanlonProposalResourceTest {

    @Inject DanlonAssignmentService service;

    private final List<String> users = new ArrayList<>();
    private String newUser() { String u = UUID.randomUUID().toString(); users.add(u); return u; }

    @AfterEach
    void cleanup() {
        for (String u : users) QuarkusTransaction.requiringNew().run(() -> {
            DanlonAssignmentProposal.delete("useruuid", u);
            UserDanlonHistory.delete("useruuid", u);
        });
        users.clear();
    }

    @Test
    void unauthenticatedIsForbidden() {
        given().when().get("/company/test-co/danlon/proposals?month=2026-02")
                .then().statusCode(anyOf(is(401), is(403)));
    }

    @Test
    @TestSecurity(user = "hr", roles = {"salaries:read", "salaries:write"})
    void approveMintsRowAndReturns200() {
        String user = newUser();
        String company = "test-co-" + (System.nanoTime() % 100000);
        service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT, company);
        String pid = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, company, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT).getUuid());

        given().header("X-Requested-By", "hr")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"confirmedNumber\":\"T55555\"}")
        .when()
                .post("/company/" + company + "/danlon/proposals/" + pid + "/approve")
        .then()
                .statusCode(200)
                .body("danlon", is("T55555"));

        Long rows = QuarkusTransaction.requiringNew().call(() ->
                UserDanlonHistory.count("useruuid = ?1 AND danlon = ?2", user, "T55555"));
        org.junit.jupiter.api.Assertions.assertEquals(1L, rows);
    }

    @Test
    @TestSecurity(user = "hr", roles = {"salaries:write"})
    void approveMissingProposalReturns409() {
        given().header("X-Requested-By", "hr")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"confirmedNumber\":\"T1\"}")
        .when()
                .post("/company/x/danlon/proposals/does-not-exist/approve")
        .then()
                .statusCode(409)
                .body("error", containsString("not found"));
    }

    @Test
    @TestSecurity(user = "hr", roles = {"salaries:read"})
    void integrityReturns200WithExpectedShape() {
        given().header("X-Requested-By", "hr")
        .when()
                .get("/company/x/danlon/integrity")
        .then()
                .statusCode(200)
                .body("$", hasKey("duplicates"))
                .body("$", hasKey("missingIdActives"))
                .body("$", hasKey("nonConforming"));
    }

    @Test
    @TestSecurity(user = "hr", roles = {"salaries:write"})
    void approveViaWrongCompanyPathReturns403() {  // cross-company BOLA guard (security review F2)
        String user = newUser();
        String companyA = "co-a-" + (System.nanoTime() % 100000);
        service.proposeIfNeeded(user, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT, companyA);
        String pid = QuarkusTransaction.requiringNew().call(() ->
                DanlonAssignmentProposal.findPendingForSlot(user, companyA, LocalDate.of(2026, 2, 1), DanlonEventType.FIRST_EMPLOYMENT).getUuid());

        given().header("X-Requested-By", "hr")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"confirmedNumber\":\"T77777\"}")
        .when()
                .post("/company/co-b-different/danlon/proposals/" + pid + "/approve")
        .then()
                .statusCode(403);

        // Nothing minted for the cross-company attempt.
        Long rows = QuarkusTransaction.requiringNew().call(() ->
                UserDanlonHistory.count("useruuid = ?1 AND danlon = ?2", user, "T77777"));
        org.junit.jupiter.api.Assertions.assertEquals(0L, rows);
    }

    @Test
    @TestSecurity(user = "hr", roles = {"salaries:write"})
    void approveWithoutRequestedByReturns400() {  // no ghost approvals (security review F4)
        // No X-Requested-By header → no identified acting user → 400 before any mint.
        given()
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"confirmedNumber\":\"T1\"}")
        .when()
                .post("/company/x/danlon/proposals/whatever/approve")
        .then()
                .statusCode(400);
    }
}
