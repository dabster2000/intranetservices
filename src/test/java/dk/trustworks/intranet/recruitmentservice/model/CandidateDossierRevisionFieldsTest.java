package dk.trustworks.intranet.recruitmentservice.model;

import jakarta.persistence.Column;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the two fields added in V321 are present on
 * {@link CandidateDossierRevision} with the expected JPA mappings.
 * Uses reflection so the test runs without booting Quarkus —
 * matches the {@code ContractBillingFieldsTest} pattern.
 */
class CandidateDossierRevisionFieldsTest {

    @Test
    void generatedPdfsSnapshot_is_immutable_json_column() throws Exception {
        Field f = CandidateDossierRevision.class.getDeclaredField("generatedPdfsSnapshot");
        assertEquals(String.class, f.getType());

        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "generatedPdfsSnapshot must be annotated with @Column");
        assertEquals("generated_pdfs_snapshot", column.name());
        assertEquals("JSON", column.columnDefinition());
        assertFalse(column.updatable(),
                "generatedPdfsSnapshot must match the other snapshot columns: updatable=false (immutable)");

        JdbcTypeCode jdbcType = f.getAnnotation(JdbcTypeCode.class);
        assertNotNull(jdbcType, "generatedPdfsSnapshot must use @JdbcTypeCode for JSON storage");
        assertEquals(SqlTypes.JSON, jdbcType.value());
    }

    @Test
    void s3RetentionUntil_is_mutable_datetime_column() throws Exception {
        Field f = CandidateDossierRevision.class.getDeclaredField("s3RetentionUntil");
        assertEquals(LocalDateTime.class, f.getType());

        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "s3RetentionUntil must be annotated with @Column");
        assertEquals("s3_retention_until", column.name());
        assertTrue(column.updatable(),
                "s3RetentionUntil must be mutable — it is stamped post-persist by the promote flow");
    }
}
