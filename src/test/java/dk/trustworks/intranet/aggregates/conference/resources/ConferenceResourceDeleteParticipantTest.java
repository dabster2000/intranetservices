package dk.trustworks.intranet.aggregates.conference.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class ConferenceResourceDeleteParticipantTest {

    @Test
    @TestSecurity(user = "admin", roles = {"conference:write"})
    void deleteAllowedForConferenceWrite() {
        given()
            .header("X-Requested-By", "admin")
        .when()
            .delete("/knowledge/conferences/{c}/participants/{p}", "conf-x", "pid-x")
        .then()
            .statusCode(anyOf(is(200), is(204)));
    }

    @Test
    @TestSecurity(user = "reader", roles = {"conference:read"})
    void deleteForbiddenWithoutWriteScope() {
        given()
            .header("X-Requested-By", "reader")
        .when()
            .delete("/knowledge/conferences/{c}/participants/{p}", "conf-x", "pid-x")
        .then()
            .statusCode(403);
    }
}
