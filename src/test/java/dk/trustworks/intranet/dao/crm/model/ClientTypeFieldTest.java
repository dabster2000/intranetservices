package dk.trustworks.intranet.dao.crm.model;

import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the three new Client fields added in SPEC-INV-001 §5.1 are present
 * on the Client entity and mapped to the expected V286 columns. Uses reflection
 * so the test runs without booting Quarkus / loading runtime secrets.
 */
class ClientTypeFieldTest {

    @Test
    void type_field_exists_and_is_mapped_to_type_column_as_string_enum() throws Exception {
        Field f = Client.class.getDeclaredField("type");
        assertEquals(ClientType.class, f.getType(),
                "Field type should be ClientType");

        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "Field type must be annotated with @Column");
        assertEquals("type", column.name(),
                "Field type should map to column type");
        assertFalse(column.nullable(),
                "type column must be NOT NULL (V286 DEFAULT 'CLIENT')");

        Enumerated enumerated = f.getAnnotation(Enumerated.class);
        assertNotNull(enumerated, "Field type must be annotated with @Enumerated");
        assertEquals(EnumType.STRING, enumerated.value(),
                "type must be stored as STRING (matches V286 VARCHAR(10))");
    }

    @Test
    void defaultBillingAttention_field_exists_and_is_mapped_to_default_billing_attention_column() throws Exception {
        assertFieldMapping("defaultBillingAttention", "default_billing_attention", String.class);
    }

    @Test
    void defaultBillingEmail_field_exists_and_is_mapped_to_default_billing_email_column() throws Exception {
        assertFieldMapping("defaultBillingEmail", "default_billing_email", String.class);
    }

    @Test
    void no_arg_constructor_defaults_type_to_CLIENT() {
        Client c = new Client();
        assertEquals(ClientType.CLIENT, c.getType(),
                "no-arg constructor must default type to CLIENT for backward compat");
    }

    @Test
    void two_arg_constructor_defaults_type_to_CLIENT() {
        Client c = new Client("contact", "Test Client");
        assertEquals(ClientType.CLIENT, c.getType(),
                "(contactname, name) constructor must default type to CLIENT for backward compat");
    }

    private static void assertFieldMapping(String fieldName, String columnName, Class<?> expectedType) throws Exception {
        Field f = Client.class.getDeclaredField(fieldName);
        assertEquals(expectedType, f.getType(),
                "Field " + fieldName + " should be " + expectedType.getSimpleName());
        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "Field " + fieldName + " must be annotated with @Column");
        assertEquals(columnName, column.name(),
                "Field " + fieldName + " should map to column " + columnName);
    }
}
