package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.model.Company;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based JPA mapping verification for VatZoneMapping.
 */
class VatZoneMappingTest {

    @Test
    void is_a_jpa_entity_on_the_vat_zone_mapping_table() {
        Entity entityAnn = VatZoneMapping.class.getAnnotation(Entity.class);
        Table  tableAnn  = VatZoneMapping.class.getAnnotation(Table.class);
        assertNotNull(entityAnn);
        assertNotNull(tableAnn);
        assertEquals("vat_zone_mapping", tableAnn.name());
    }

    @Test
    void uuid_field_is_the_id() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("uuid");
        assertNotNull(f.getAnnotation(Id.class));
        assertEquals(String.class, f.getType());
    }

    @Test
    void currency_is_non_null() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("currency");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("currency", col.name());
        assertFalse(col.nullable());
    }

    @Test
    void company_is_a_many_to_one_fk() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("company");
        JoinColumn jc = f.getAnnotation(JoinColumn.class);
        assertNotNull(f.getAnnotation(ManyToOne.class));
        assertNotNull(jc);
        assertEquals("company_uuid", jc.name());
        assertEquals(Company.class, f.getType());
    }

    @Test
    void economics_vat_zone_number_is_non_null() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("economicsVatZoneNumber");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("economics_vat_zone_number", col.name());
        assertFalse(col.nullable());
        assertEquals(Integer.class, f.getType());
    }

    @Test
    void economics_vat_zone_name_is_nullable() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("economicsVatZoneName");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(col);
        assertEquals("economics_vat_zone_name", col.name());
    }

    @Test
    void vat_rate_percent_is_non_null_decimal_5_2() throws Exception {
        Field f = VatZoneMapping.class.getDeclaredField("vatRatePercent");
        Column col = f.getAnnotation(Column.class);
        assertNotNull(f.getAnnotation(NotNull.class));
        assertNotNull(col);
        assertEquals("vat_rate_percent", col.name());
        assertFalse(col.nullable());
        assertEquals(5, col.precision());
        assertEquals(2, col.scale());
        assertEquals(BigDecimal.class, f.getType());
    }
}
