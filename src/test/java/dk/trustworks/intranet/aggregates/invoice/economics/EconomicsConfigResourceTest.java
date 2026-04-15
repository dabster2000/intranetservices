package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * REST contract tests for EconomicsConfigResource.
 *
 * NOTE: Requires Quarkus startup with runtime secrets (cvtool.*) available.
 * In environments without those secrets (e.g. local dev without env vars),
 * the @QuarkusTest infrastructure cannot boot. The tests still document the
 * intended contract and run against any correctly-configured CI/dev env.
 */
@QuarkusTest
@TestSecurity(user = "admin", roles = {"invoices:write", "invoices:read"})
class EconomicsConfigResourceTest {

    @Test
    void create_list_update_delete_payment_terms_mapping() {
        // Create
        String uuid =
        given().contentType("application/json")
               .body("""
                     {
                       "paymentTermsType": "NET",
                       "paymentDays": 30,
                       "economicsPaymentTermsNumber": 5,
                       "economicsPaymentTermsName": "Netto 30 dage"
                     }
                     """)
        .when().post("/invoices/economics/payment-terms")
        .then().statusCode(201)
               .body("uuid", notNullValue())
               .extract().path("uuid");

        // List
        given().when().get("/invoices/economics/payment-terms")
               .then().statusCode(200)
               .body("size()", greaterThanOrEqualTo(1));

        // Update
        given().contentType("application/json")
               .body("""
                     {
                       "paymentTermsType": "NET",
                       "paymentDays": 30,
                       "economicsPaymentTermsNumber": 7,
                       "economicsPaymentTermsName": "Netto 30 dage (renamed)"
                     }
                     """)
        .when().put("/invoices/economics/payment-terms/" + uuid)
        .then().statusCode(200)
               .body("economicsPaymentTermsNumber", org.hamcrest.Matchers.equalTo(7));

        // Delete
        given().when().delete("/invoices/economics/payment-terms/" + uuid)
               .then().statusCode(204);
    }

    @Test
    void rejects_payment_terms_with_days_for_due_date_type() {
        given().contentType("application/json")
               .body("""
                     {
                       "paymentTermsType": "DUE_DATE",
                       "paymentDays": 14,
                       "economicsPaymentTermsNumber": 9
                     }
                     """)
        .when().post("/invoices/economics/payment-terms")
        .then().statusCode(400);
    }

    @Test
    void rejects_net_without_payment_days() {
        given().contentType("application/json")
               .body("""
                     {
                       "paymentTermsType": "NET",
                       "economicsPaymentTermsNumber": 5
                     }
                     """)
        .when().post("/invoices/economics/payment-terms")
        .then().statusCode(400);
    }

    @Test
    void create_and_list_vat_zone_mapping() {
        String uuid =
        given().contentType("application/json")
               .body("""
                     {
                       "currency": "EUR",
                       "economicsVatZoneNumber": 2,
                       "economicsVatZoneName": "EU"
                     }
                     """)
        .when().post("/invoices/economics/vat-zones")
        .then().statusCode(201).extract().path("uuid");

        given().when().get("/invoices/economics/vat-zones")
               .then().statusCode(200)
               .body("size()", greaterThanOrEqualTo(1));

        given().when().delete("/invoices/economics/vat-zones/" + uuid)
               .then().statusCode(204);
    }
}
