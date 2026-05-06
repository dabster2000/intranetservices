package dk.trustworks.intranet.recruitmentservice.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Reflection-based safety net for {@link S3RetentionCleanupBatchlet}.
 * Verifies the @Scheduled cron expression and the field wiring; full
 * end-to-end reaper behaviour is exercised in CI integration tests where
 * the DB is available (Mockito cannot mock Panache static finders).
 */
class S3RetentionCleanupBatchletTest {

    @Test
    void cronExpression_isDailyAt0210() throws Exception {
        java.lang.reflect.Method m = S3RetentionCleanupBatchlet.class
                .getMethod("scheduledRun");
        io.quarkus.scheduler.Scheduled annotation = m.getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertNotNull(annotation, "scheduledRun must carry @Scheduled");
        assertEquals("0 10 2 * * ?", annotation.cron(),
                "Reaper should run daily at 02:10 (offset from the 02:00 cluster).");
    }

    @Test
    void hasInjectedDependencies() throws Exception {
        Field s3 = S3RetentionCleanupBatchlet.class.getDeclaredField("s3StorageService");
        assertEquals(RecruitmentS3StorageService.class, s3.getType());

        Field om = S3RetentionCleanupBatchlet.class.getDeclaredField("objectMapper");
        assertEquals(ObjectMapper.class, om.getType());
    }

    @Test
    void runMethod_isTransactional() throws Exception {
        java.lang.reflect.Method m = S3RetentionCleanupBatchlet.class
                .getMethod("run");
        jakarta.transaction.Transactional t = m.getAnnotation(jakarta.transaction.Transactional.class);
        assertNotNull(t, "run() must be @Transactional so partial failures roll back per-item");
    }
}
