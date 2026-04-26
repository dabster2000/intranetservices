package dk.trustworks.intranet.recruitmentservice.api;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CandidateResourceTest {

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write"})
    void createListPatchAndAddNote() {
        String body = """
            {"firstName":"Pat","lastName":"Doe","email":"pat@example.com","desiredPractice":"DEV"}""";
        String uuid = given().contentType("application/json").body(body)
                .when().post("/api/recruitment/candidates")
                .then().statusCode(201).extract().path("uuid");

        given().when().get("/api/recruitment/candidates/" + uuid)
                .then().statusCode(200).body("state", equalTo("NEW"));

        given().contentType("application/json")
                .body("{\"phone\":\"+45 12 34 56 78\"}")
                .when().patch("/api/recruitment/candidates/" + uuid)
                .then().statusCode(200).body("phone", equalTo("+45 12 34 56 78"));

        given().contentType("application/json")
                .body("{\"visibility\":\"SHARED\",\"body\":\"Strong DEV background\"}")
                .when().post("/api/recruitment/candidates/" + uuid + "/notes")
                .then().statusCode(201).body("uuid", notNullValue());
    }

    @Test
    @TestSecurity(user = "stranger", roles = {"users:read"})
    void readWithoutScopeReturns403() {
        given().when().get("/api/recruitment/candidates").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:write"})
    void create_withInvalidEmail_returns400() {
        given().header("X-Requested-By", "00000000-0000-0000-0000-000000000010")
                .contentType("application/json")
                .body("{\"firstName\":\"X\",\"email\":\"not-an-email\"}")
                .when().post("/api/recruitment/candidates")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "tam", roles = {"recruitment:read", "recruitment:write", "recruitment:admin"})
    void createWithTags_andPatchTags() {
        String body = "{\"firstName\":\"Alice\",\"tags\":[\"java\",\"aws\"]}";
        String uuid = given().contentType("application/json").body(body)
                .when().post("/api/recruitment/candidates")
                .then().statusCode(201)
                .body("tags", contains("java", "aws"))
                .extract().path("uuid");

        given().when().get("/api/recruitment/candidates/" + uuid)
                .then().statusCode(200)
                .body("tags", contains("java", "aws"));

        given().contentType("application/json").body("{\"tags\":[]}")
                .when().patch("/api/recruitment/candidates/" + uuid)
                .then().statusCode(200);

        given().when().get("/api/recruitment/candidates/" + uuid)
                .then().statusCode(200)
                .body("tags", hasSize(0));
    }
}
