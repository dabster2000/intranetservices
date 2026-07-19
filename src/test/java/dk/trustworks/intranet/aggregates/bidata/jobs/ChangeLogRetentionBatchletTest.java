package dk.trustworks.intranet.aggregates.bidata.jobs;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito-only unit tests — same pattern as {@code S3RetentionCleanupBatchletTest}.
 * The transactional wrapper is overridden via {@link Mockito#spy} so tests do
 * not need a Quarkus context.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChangeLogRetentionBatchletTest {

    @Mock
    EntityManager em;

    @Mock
    Query deleteQuery;

    private ChangeLogRetentionBatchlet batchlet;

    @BeforeEach
    void setUp() {
        ChangeLogRetentionBatchlet real = new ChangeLogRetentionBatchlet();
        real.em = em;
        real.retentionDays = 30;
        batchlet = Mockito.spy(real);
        // Bypass QuarkusTransaction.requiringNew() — not available in unit context.
        Mockito.doAnswer(inv -> batchlet.deleteOneChunk())
                .when(batchlet).deleteOneChunkInOwnTransaction();
    }

    @Test
    void run_singleShortChunk_returnsRowsDeletedAndStops() {
        when(em.createNativeQuery(anyString())).thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("days"), eq(30))).thenReturn(deleteQuery);
        when(deleteQuery.executeUpdate()).thenReturn(7);

        long deleted = batchlet.run();

        assertEquals(7L, deleted);
        verify(deleteQuery, times(1)).executeUpdate();
    }

    @Test
    void run_multipleFullChunks_loopsUntilShortChunk() {
        when(em.createNativeQuery(anyString())).thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("days"), eq(30))).thenReturn(deleteQuery);
        when(deleteQuery.executeUpdate())
                .thenReturn(ChangeLogRetentionBatchlet.DELETE_CHUNK_SIZE,
                            ChangeLogRetentionBatchlet.DELETE_CHUNK_SIZE,
                            42);

        long deleted = batchlet.run();

        assertEquals(2L * ChangeLogRetentionBatchlet.DELETE_CHUNK_SIZE + 42L, deleted);
        verify(deleteQuery, times(3)).executeUpdate();
    }

    @Test
    void run_zeroRows_returnsZeroAndExitsImmediately() {
        when(em.createNativeQuery(anyString())).thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("days"), eq(30))).thenReturn(deleteQuery);
        when(deleteQuery.executeUpdate()).thenReturn(0);

        long deleted = batchlet.run();

        assertEquals(0L, deleted);
        verify(deleteQuery, times(1)).executeUpdate();
    }

    @Test
    void run_customRetentionDays_isBoundToQueryParameter() {
        batchlet.retentionDays = 7;
        when(em.createNativeQuery(anyString())).thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("days"), eq(7))).thenReturn(deleteQuery);
        when(deleteQuery.executeUpdate()).thenReturn(0);

        batchlet.run();

        verify(deleteQuery).setParameter("days", 7);
        verify(deleteQuery, never()).setParameter("days", 30);
    }

    @Test
    void scheduledRun_swallowsExceptions_soSchedulerKeepsRunning() {
        when(em.createNativeQuery(anyString()))
                .thenThrow(new RuntimeException("simulated DB outage"));

        batchlet.scheduledRun();
    }

    @Test
    void scheduledRun_isAnnotatedWithDailyCron() throws NoSuchMethodException {
        Method m = ChangeLogRetentionBatchlet.class.getMethod("scheduledRun");
        io.quarkus.scheduler.Scheduled annotation =
                m.getAnnotation(io.quarkus.scheduler.Scheduled.class);
        assertNotNull(annotation, "scheduledRun must carry @Scheduled");
        assertEquals("0 15 2 * * ?", annotation.cron(),
                "Retention reaper should run daily at 02:15 UTC.");
    }
}
