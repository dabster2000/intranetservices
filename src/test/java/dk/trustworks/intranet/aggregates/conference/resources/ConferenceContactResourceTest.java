package dk.trustworks.intranet.aggregates.conference.resources;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
class ConferenceContactResourceTest {

    @Test
    void contactFormIsAcceptedAnonymously() {
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("name", "Ada")
            .formParam("email", "ada@example.dk")
            .formParam("company", "Trustworks")
            .formParam("phone", "+45 11 22 33 44")
            .formParam("consent", "on")
        .when()
            .post("/knowledge/conferences/{uuid}/contact", "test-conf-uuid")
        .then()
            .statusCode(org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.is(200), org.hamcrest.Matchers.is(204)));
    }
}
