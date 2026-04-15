package dk.trustworks.intranet.aggregates.invoice.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the three new fields added in SPEC-INV-001 §5.6 are present on the
 * Invoice entity and mapped to the expected V286 columns. Uses reflection so
 * the test runs without booting Quarkus / loading runtime secrets.
 */
class InvoiceFieldRoundTripTest {

    @Test
    void billingClientUuid_field_exists_and_is_mapped_to_billing_client_uuid_column() throws Exception {
        assertFieldMapping("billingClientUuid", "billing_client_uuid", String.class);
    }

    @Test
    void economicsDraftNumber_field_exists_and_is_mapped_to_economics_draft_number_column() throws Exception {
        assertFieldMapping("economicsDraftNumber", "economics_draft_number", Integer.class);
    }

    @Test
    void economicsBookedNumber_field_exists_and_is_mapped_to_economics_booked_number_column() throws Exception {
        assertFieldMapping("economicsBookedNumber", "economics_booked_number", Integer.class);
    }

    private static void assertFieldMapping(String fieldName, String columnName, Class<?> expectedType) throws Exception {
        Field f = Invoice.class.getDeclaredField(fieldName);
        assertEquals(expectedType, f.getType(),
                "Field " + fieldName + " should be " + expectedType.getSimpleName());
        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "Field " + fieldName + " must be annotated with @Column");
        assertEquals(columnName, column.name(),
                "Field " + fieldName + " should map to column " + columnName);
    }
}
