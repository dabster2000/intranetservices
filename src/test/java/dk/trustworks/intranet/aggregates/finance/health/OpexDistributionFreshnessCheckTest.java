package dk.trustworks.intranet.aggregates.finance.health;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpexDistributionFreshnessCheckTest {

    @Mock EntityManager em;
    @Mock Query query;

    OpexDistributionFreshnessCheck check;

    @BeforeEach
    void setUp() {
        check = new OpexDistributionFreshnessCheck();
        check.em = em;
        check.maxStalenessHours = 30;
    }

    @Test
    void emptyTable_returnsDown() {
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(new Object[]{null, 0L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, result.getStatus());
        assertTrue(result.getData().isPresent(),
                "expected health-check data to be present");
        assertEquals(0L, result.getData().get().get("rows"));
    }

    @Test
    void freshRows_returnsUp() {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(oneHourAgo), 400L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.UP, result.getStatus());
    }

    @Test
    void staleRows_returnsDown() {
        LocalDateTime longAgo = LocalDateTime.now().minusHours(48);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.getSingleResult()).thenReturn(
                new Object[]{Timestamp.valueOf(longAgo), 400L});

        HealthCheckResponse result = check.call();

        assertEquals(HealthCheckResponse.Status.DOWN, result.getStatus());
    }
}
