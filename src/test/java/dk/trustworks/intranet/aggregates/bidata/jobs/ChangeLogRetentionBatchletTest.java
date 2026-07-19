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
import java.math.BigInteger;
import java.util.List;

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
    Query selectQuery;

    @Mock
    Query watermarkQuery;

    @Mock
    Query deleteQuery;

    private ChangeLogRetentionBatchlet batchlet;

    @BeforeEach
    void setUp() {
        ChangeLogRetentionBatchlet real = new ChangeLogRetentionBatchlet();
        real.em = em;
        real.retentionDays = 30;
        batchlet = Mockito.spy(real);
        when(em.createNativeQuery(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.startsWith("SELECT id")) return selectQuery;
            if (sql.contains("UPDATE practice_revenue_source_watermark")) return watermarkQuery;
            return deleteQuery;
        });
        when(selectQuery.setParameter(eq("days"), org.mockito.ArgumentMatchers.any())).thenReturn(selectQuery);
        when(selectQuery.setMaxResults(ChangeLogRetentionBatchlet.DELETE_CHUNK_SIZE)).thenReturn(selectQuery);
        when(watermarkQuery.setParameter(eq("highest"), org.mockito.ArgumentMatchers.any())).thenReturn(watermarkQuery);
        when(watermarkQuery.executeUpdate()).thenReturn(1);
        when(deleteQuery.setParameter(eq("highest"), org.mockito.ArgumentMatchers.any())).thenReturn(deleteQuery);
        when(deleteQuery.setParameter(eq("days"), org.mockito.ArgumentMatchers.any())).thenReturn(deleteQuery);
        // Bypass QuarkusTransaction.requiringNew() — not available in unit context.
        Mockito.doAnswer(inv -> batchlet.deleteOneChunk())
                .when(batchlet).deleteOneChunkInOwnTransaction();
    }

    @Test
    void run_singleShortChunk_returnsRowsDeletedAndStops() {
        when(selectQuery.getResultList()).thenReturn(List.of(3L, 9L, 14L));
        when(deleteQuery.executeUpdate()).thenReturn(7);

        long deleted = batchlet.run();

        assertEquals(7L, deleted);
        verify(deleteQuery, times(1)).executeUpdate();
    }

    @Test
    void run_multipleFullChunks_loopsUntilShortChunk() {
        when(selectQuery.getResultList()).thenReturn(List.of(100_000L), List.of(200_000L), List.of(200_042L));
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
        when(selectQuery.getResultList()).thenReturn(List.of());

        long deleted = batchlet.run();

        assertEquals(0L, deleted);
        verify(deleteQuery, never()).executeUpdate();
        verify(watermarkQuery, never()).executeUpdate();
    }

    @Test
    void run_customRetentionDays_isBoundToQueryParameter() {
        batchlet.retentionDays = 7;
        when(selectQuery.getResultList()).thenReturn(List.of());

        batchlet.run();

        verify(selectQuery).setParameter("days", 7);
        verify(selectQuery, never()).setParameter("days", 30);
    }

    @Test
    void deleteOneChunk_recordsHighestActualDeletedIdBeforeDelete_soNumericHolesAreIgnored() {
        when(selectQuery.getResultList()).thenReturn(List.of(7L, 11L, 42L));
        when(deleteQuery.executeUpdate()).thenReturn(3);

        assertEquals(3, batchlet.deleteOneChunk());

        var order = Mockito.inOrder(watermarkQuery, deleteQuery);
        order.verify(watermarkQuery).setParameter("highest", BigInteger.valueOf(42));
        order.verify(watermarkQuery).executeUpdate();
        order.verify(deleteQuery).setParameter("highest", BigInteger.valueOf(42));
        order.verify(deleteQuery).executeUpdate();
    }

    @Test
    void deleteOneChunk_missingWatermarkAbortsBeforeDelete() {
        when(selectQuery.getResultList()).thenReturn(List.of(5L));
        when(watermarkQuery.executeUpdate()).thenReturn(0);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> batchlet.deleteOneChunk());

        verify(deleteQuery, never()).executeUpdate();
    }

    @Test
    void scheduledRun_swallowsExceptions_soSchedulerKeepsRunning() {
        when(selectQuery.getResultList()).thenThrow(new RuntimeException("simulated DB outage"));

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
