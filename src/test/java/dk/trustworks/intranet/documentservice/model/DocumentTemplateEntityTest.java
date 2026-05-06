package dk.trustworks.intranet.documentservice.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code sharepointFolder} field added in V321 is present on
 * {@link DocumentTemplateEntity} and mapped to the {@code sharepoint_folder}
 * column. Uses reflection so the test runs without booting Quarkus — the
 * local dev env lacks cvtool.* config, matching the established pattern in
 * {@code ContractBillingFieldsTest}.
 */
class DocumentTemplateEntityTest {

    @Test
    void sharepointFolder_is_mapped_to_sharepoint_folder_column() throws Exception {
        Field f = DocumentTemplateEntity.class.getDeclaredField("sharepointFolder");
        assertEquals(String.class, f.getType(),
                "Field sharepointFolder should be String");
        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "Field sharepointFolder must be annotated with @Column");
        assertEquals("sharepoint_folder", column.name(),
                "Field sharepointFolder should map to column sharepoint_folder");
        assertEquals(500, column.length(),
                "Column should be VARCHAR(500) to match V321 migration");
    }
}
