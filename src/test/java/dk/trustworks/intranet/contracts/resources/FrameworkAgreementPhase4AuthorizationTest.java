package dk.trustworks.intranet.contracts.resources;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/** Scope checks for the new agreement-contract and contract-parameter endpoints. */
@QuarkusTest
class FrameworkAgreementPhase4AuthorizationTest {

    @Test
    @TestSecurity(user = "outsider", roles = {"users:read"})
    void readEndpointsRequireContractsRead() {
        given().when().get("/api/contract-types/ZZUNKNOWN/contracts").then().statusCode(403);
        given().when().get("/contracts/unknown/contracttypeitems").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "reader", roles = {"contracts:read"})
    void mutationEndpointsRequireContractsWrite() {
        given().contentType("application/json").body("{\"key\":\"x\",\"value\":\"1\"}")
                .when().post("/contracts/unknown/contracttypeitems").then().statusCode(403);
        given().contentType("application/json").body("{\"id\":1,\"key\":\"x\",\"value\":\"1\"}")
                .when().put("/contracts/unknown/contracttypeitems").then().statusCode(403);
        given().when().delete("/contracts/unknown/contracttypeitems/1").then().statusCode(403);
    }
}
