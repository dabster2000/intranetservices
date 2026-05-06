package dk.trustworks.intranet.recruitmentservice.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the {@code s3RetentionUntil} field added in V321 is present on
 * {@link CandidateDossierAppendix} and mapped to the {@code s3_retention_until}
 * column. Uses reflection so the test runs without booting Quarkus —
 * matches the {@code ContractBillingFieldsTest} pattern.
 */
class CandidateDossierAppendixFieldsTest {

    @Test
    void s3RetentionUntil_is_mutable_datetime_column() throws Exception {
        Field f = CandidateDossierAppendix.class.getDeclaredField("s3RetentionUntil");
        assertEquals(LocalDateTime.class, f.getType());

        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "s3RetentionUntil must be annotated with @Column");
        assertEquals("s3_retention_until", column.name());
        assertTrue(column.updatable(),
                "s3RetentionUntil must be mutable — it is stamped post-persist by the promote flow");
    }
}
