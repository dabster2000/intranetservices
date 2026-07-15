package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeContributionResponseDTO;
import dk.trustworks.intranet.aggregates.practices.dto.cxo.PracticeRevenueSegmentDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContributionContractSafetyTest {

    @Test
    void publicDtosExposeOnlyAggregateFields() {
        Set<String> response = componentNames(PracticeContributionResponseDTO.class);
        assertTrue(response.containsAll(Set.of("practices", "revenueOnlySegments", "sourceWatermarkVersions")));
        assertNoSensitiveIdentity(response);
        assertNoSensitiveIdentity(componentNames(PracticeRevenueSegmentDTO.class));
        assertNoSensitiveIdentity(componentNames(PracticeRevenueSegmentDTO.Metrics.class));
    }

    @Test
    void unavailableErrorsAreClosedToThreeSafeCodes() {
        assertEquals(ContributionUnavailableException.PUBLICATION_DISABLED,
                new ContributionUnavailableException(
                        ContributionUnavailableException.PUBLICATION_DISABLED).getMessage());
        assertEquals(ContributionUnavailableException.PUBLICATION_UNAVAILABLE,
                new ContributionUnavailableException(
                        ContributionUnavailableException.PUBLICATION_UNAVAILABLE).getMessage());
        assertEquals(ContributionUnavailableException.QUERY_TIMEOUT,
                new ContributionUnavailableException(
                        ContributionUnavailableException.QUERY_TIMEOUT).getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> new ContributionUnavailableException("upstream response body"));
    }

    @Test
    void backendQueriesAreBoundAndTimedRatherThanCallerControlled() {
        assertTrue(CxoPracticeContributionService.SNAPSHOT_SQL.contains("control_id = 1"));
        assertTrue(CxoPracticeContributionService.LATEST_REQUEST_SQL.contains(":requestId"));
        assertEquals(10_000, CxoPracticeContributionService.QUERY_TIMEOUT_MS);
        assertFalse(CxoPracticeContributionService.SNAPSHOT_SQL.contains("consultant_uuid"));
    }

    private static Set<String> componentNames(Class<?> type) {
        assertTrue(type.isRecord());
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }

    private static void assertNoSensitiveIdentity(Set<String> names) {
        assertFalse(names.contains("consultantUuid"));
        assertFalse(names.contains("userUuid"));
        assertFalse(names.contains("salaryDetails"));
        assertFalse(names.contains("invoiceDescription"));
    }
}
