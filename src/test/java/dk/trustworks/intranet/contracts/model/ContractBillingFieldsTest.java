package dk.trustworks.intranet.contracts.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the five new Contract billing fields added in SPEC-INV-001 §5.5
 * are present on the Contract entity and mapped to the expected V286 columns.
 * Uses reflection so the test runs without booting Quarkus / loading runtime
 * secrets (the local dev env lacks cvtool.* config, so @QuarkusTest-bootstrapped
 * metamodel checks don't run — matches the Phase A/C test pattern).
 */
class ContractBillingFieldsTest {

    @Test
    void billingClientUuid_is_mapped_to_billing_client_uuid() throws Exception {
        assertFieldMapping("billingClientUuid", "billing_client_uuid", String.class);
    }

    @Test
    void billingAttention_is_mapped_to_billing_attention() throws Exception {
        assertFieldMapping("billingAttention", "billing_attention", String.class);
    }

    @Test
    void billingEmail_is_mapped_to_billing_email() throws Exception {
        assertFieldMapping("billingEmail", "billing_email", String.class);
    }

    @Test
    void billingRef_is_mapped_to_billing_ref() throws Exception {
        assertFieldMapping("billingRef", "billing_ref", String.class);
    }

    @Test
    void paymentTermsUuid_is_mapped_to_payment_terms_uuid() throws Exception {
        assertFieldMapping("paymentTermsUuid", "payment_terms_uuid", String.class);
    }

    @Test
    void clientdatauuid_field_preserved_for_backward_compatibility() throws Exception {
        Field f = Contract.class.getDeclaredField("clientdatauuid");
        assertNotNull(f, "clientdatauuid must remain until Phase J drops clientdata");
        assertEquals(String.class, f.getType());
    }

    private static void assertFieldMapping(String fieldName, String columnName, Class<?> expectedType) throws Exception {
        Field f = Contract.class.getDeclaredField(fieldName);
        assertEquals(expectedType, f.getType(),
                "Field " + fieldName + " should be " + expectedType.getSimpleName());
        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "Field " + fieldName + " must be annotated with @Column");
        assertEquals(columnName, column.name(),
                "Field " + fieldName + " should map to column " + columnName);
    }
}
