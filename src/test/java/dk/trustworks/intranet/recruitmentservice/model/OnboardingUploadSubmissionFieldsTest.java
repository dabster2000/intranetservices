package dk.trustworks.intranet.recruitmentservice.model;

import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code s3RetentionUntil} field added in V328 is present on
 * {@link OnboardingUploadSubmission} and mapped to the
 * {@code s3_retention_until} column. Uses reflection so the test runs
 * without booting Quarkus — mirrors the
 * {@link CandidateDossierAppendixFieldsTest} pattern.
 */
class OnboardingUploadSubmissionFieldsTest {

    @Test
    void s3RetentionUntil_is_mutable_datetime_column() throws Exception {
        Field f = OnboardingUploadSubmission.class.getDeclaredField("s3RetentionUntil");
        assertEquals(LocalDateTime.class, f.getType());

        Column column = f.getAnnotation(Column.class);
        assertNotNull(column, "s3RetentionUntil must be annotated with @Column");
        assertEquals("s3_retention_until", column.name());
        assertTrue(column.updatable(),
                "s3RetentionUntil must be mutable — it is stamped post-copy by the promote flow");
    }

    @Test
    void findS3SubmissionsByCandidate_existsWithExpectedSignature() throws Exception {
        Method m = OnboardingUploadSubmission.class.getMethod(
                "findS3SubmissionsByCandidate", String.class);
        assertEquals(List.class, m.getReturnType(),
                "findS3SubmissionsByCandidate should return List<OnboardingUploadSubmission>");
        assertTrue(Modifier.isStatic(m.getModifiers()),
                "findS3SubmissionsByCandidate must be a static finder (Panache convention)");
        assertTrue(Modifier.isPublic(m.getModifiers()),
                "findS3SubmissionsByCandidate must be public — called from SharePointEmployeeFolderService");
    }
}
