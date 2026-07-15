package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeOperatingCostResponseDTO;
import dk.trustworks.intranet.financeservice.model.enums.CostSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.ServiceUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PracticeCostSnapshotProviderIntegrityTest {
    @Test
    void readsOnlyTheExactlyCertifiedImmutableGeneration() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        CxoPracticeOperatingCostService reader = mock(CxoPracticeOperatingCostService.class);
        PracticeOperatingCostResponseDTO response = mock(PracticeOperatingCostResponseDTO.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(pointer()));
        when(reader.readPublishedCanonicalSnapshot(any(), anyString(), any(), any())).thenReturn(response);

        PracticeCostSnapshotProvider provider = new PracticeCostSnapshotProvider();
        provider.em = em;
        provider.snapshotReader = reader;

        var snapshot = provider.getSnapshot(CostSource.BOOKED);
        assertTrue(snapshot.servingEnabled());
        verify(reader).readPublishedCanonicalSnapshot(
                CostSource.BOOKED, "basis", Instant.parse("2026-01-02T00:00:00Z"),
                LocalDate.parse("2021-01-01"));
    }

    @Test
    void failsClosedWhenAnyGenerationFingerprintDiffers() {
        EntityManager em = mock(EntityManager.class);
        Query query = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setHint(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        Object[] invalid = pointer();
        invalid[25] = "different";
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(invalid));

        PracticeCostSnapshotProvider provider = new PracticeCostSnapshotProvider();
        provider.em = em;
        provider.snapshotReader = mock(CxoPracticeOperatingCostService.class);
        assertThrows(ServiceUnavailableException.class, () -> provider.getSnapshot(CostSource.BOOKED));
    }

    private static Object[] pointer() {
        Object[] row = new Object[26];
        row[0] = true;
        row[1] = "READY";
        row[2] = null;
        row[3] = Timestamp.valueOf("2026-01-02 00:00:00");
        row[4] = Timestamp.valueOf("2026-01-02 00:00:01");
        row[5] = "basis";
        row[6] = BigInteger.ONE;
        row[7] = "request-vector";
        row[8] = fingerprint("basis-source", "capacity-source", "manifest-source", "candidate");
        row[9] = BigInteger.valueOf(2);
        row[10] = BigInteger.ONE;
        row[11] = BigInteger.valueOf(2);
        row[12] = "READY";
        row[13] = Date.valueOf("2021-01-01");
        row[14] = "basis-source";
        row[15] = "capacity-source";
        row[16] = "manifest-source";
        row[17] = BigInteger.valueOf(2);
        row[18] = BigInteger.ONE;
        row[19] = BigInteger.valueOf(2);
        row[20] = BigInteger.ONE;
        row[21] = BigInteger.ONE;
        row[22] = BigInteger.ONE;
        row[23] = "candidate";
        row[24] = "candidate";
        row[25] = "candidate";
        return row;
    }

    private static String fingerprint(Object... values) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
