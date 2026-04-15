package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based JPA mapping verification for PaymentTermsMapping.
 * Runs without booting Quarkus so it works in environments that lack
 * runtime secrets.
 */
class PaymentTermsMappingTest {

    @Test
    void is_a_jpa_entity_on_the_payment_terms_mapping_table() {
        Entity entityAnn = PaymentTermsMapping.class.getAnnotation(Entity.class);
        Table  tableAnn  = PaymentTermsMapping.class.getAnnotation(Table.class);
        assertNotNull(entityAnn);
        assertNotNull(tableAnn);
        assertEquals("payment_terms_mapping", tableAnn.name());
    }

    @Test
    void uuid_field_is_the_id() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("uuid");
        assertNotNull(f.getAnnotation(Id.class));
        assertEquals(String.class, f.getType());
    }

    @Test
    void payment_terms_type_is_mapped_as_a_string_enum() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("paymentTermsType");
        Column col = f.getAnnotation(Column.class);
        Enumerated enumAnn = f.getAnnotation(Enumerated.class);
        assertNotNull(col);
        assertNotNull(enumAnn);
        assertEquals("payment_terms_type", col.name());
        assertFalse(col.nullable());
        assertEquals(EnumType.STRING, enumAnn.value());
        assertEquals(PaymentTermsType.class, f.getType());
    }

    @Test
    void payment_days_is_nullable_integer() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("paymentDays");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("payment_days", col.name());
        assertEquals(Integer.class, f.getType());
    }

    @Test
    void company_is_a_many_to_one_fk() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("company");
        JoinColumn jc = f.getAnnotation(JoinColumn.class);
        assertNotNull(f.getAnnotation(ManyToOne.class));
        assertNotNull(jc);
        assertEquals("company_uuid", jc.name());
        assertEquals(Company.class, f.getType());
    }

    @Test
    void economics_payment_terms_number_is_non_null() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("economicsPaymentTermsNumber");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("economics_payment_terms_number", col.name());
        assertFalse(col.nullable());
        assertEquals(Integer.class, f.getType());
    }

    @Test
    void economics_payment_terms_name_is_nullable() throws Exception {
        Field f = PaymentTermsMapping.class.getDeclaredField("economicsPaymentTermsName");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("economics_payment_terms_name", col.name());
        assertEquals(String.class, f.getType());
    }
}
