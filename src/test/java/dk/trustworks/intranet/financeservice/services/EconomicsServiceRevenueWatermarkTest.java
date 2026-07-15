package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import dk.trustworks.intranet.model.Company;
import org.apache.commons.lang3.Range;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EconomicsServiceRevenueWatermarkTest {

    @Test
    void recoveryImportUsesTheStrictEvidencePathWithoutOpeningAnOrdinaryImport() {
        RecordingEconomicsService service = new RecordingEconomicsService();

        service.getAllEntriesForRecovery(null, "2025_6_2026");

        assertEquals(List.of(true), service.strictModes);
    }

    @Test
    void ordinaryImportRetainsTheCompatibilityPathAndIsBlockedBeforeWorkByARecoveryOwner() {
        RecordingEconomicsService compatibility = new RecordingEconomicsService();
        compatibility.getAllEntries(null, "2025_6_2026");
        assertEquals(List.of(false), compatibility.strictModes);

        PracticeRevenueDirtyMarker marker = mock(PracticeRevenueDirtyMarker.class);
        when(marker.beginImport(PracticeRevenueDirtyMarker.Source.FINANCE_GL))
                .thenThrow(new IllegalStateException("SOURCE_IMPORT_ALREADY_RUNNING"));
        EconomicsService owned = new EconomicsService();
        owned.practiceRevenueDirtyMarker = marker;
        AtomicBoolean invoked = new AtomicBoolean();

        assertThrows(IllegalStateException.class,
                () -> owned.withFinanceGlWatermark(() -> {
                    invoked.set(true);
                    return emptyEntries();
                }));

        assertTrue(!invoked.get());
    }

    @Test
    void financeImportPublishesItsExactAffectedMonthBounds(){
        PracticeRevenueDirtyMarker marker=mock(PracticeRevenueDirtyMarker.class);
        when(marker.beginImport(PracticeRevenueDirtyMarker.Source.FINANCE_GL)).thenReturn("owner");
        EconomicsService service=new EconomicsService();
        service.practiceRevenueDirtyMarker=marker;
        Map<PostingStatus,Map<Range<Integer>,List<EconomicsService.FinanceEntry>>> result=
                new EnumMap<>(PostingStatus.class);
        Map<Range<Integer>,List<EconomicsService.FinanceEntry>> accounts=new LinkedHashMap<>();
        accounts.put(Range.between(1000,1999),List.of(
                new EconomicsService.FinanceEntry(LocalDate.parse("2026-01-01"),1000,1,PostingStatus.BOOKED),
                new EconomicsService.FinanceEntry(LocalDate.parse("2026-03-01"),1000,2,PostingStatus.BOOKED)));
        result.put(PostingStatus.BOOKED,accounts);

        service.withFinanceGlWatermark(()->result);

        verify(marker).completeImport(PracticeRevenueDirtyMarker.Source.FINANCE_GL,"owner",
                YearMonth.parse("2026-01"),YearMonth.parse("2026-03"));
    }

    @Test
    void financeImportFailureKeepsThePriorCompletedVersionAndMarksTheOwnerFailed(){
        PracticeRevenueDirtyMarker marker=mock(PracticeRevenueDirtyMarker.class);
        when(marker.beginImport(PracticeRevenueDirtyMarker.Source.FINANCE_GL)).thenReturn("owner");
        EconomicsService service=new EconomicsService();
        service.practiceRevenueDirtyMarker=marker;

        assertThrows(IllegalStateException.class,()->service.withFinanceGlWatermark(
                ()->{throw new IllegalStateException("fetch failed");}));

        verify(marker).failImport(PracticeRevenueDirtyMarker.Source.FINANCE_GL,"owner");
    }

    @Test
    void strictSupplementalFailureUsesOnlyASafeCodeAndScopedIdentity() {
        Company company = new Company();
        company.setUuid("company-a");
        IllegalStateException failure = EconomicsService.strictImportFailure(
                "FINANCE_GL_DRAFT_ENTRIES_FAILED", company, "2025_6_2026",
                new IllegalArgumentException("upstream secret detail"));

        assertEquals("FINANCE_GL_DRAFT_ENTRIES_FAILED:company-a:2025_6_2026", failure.getMessage());
    }

    @Test
    void destructiveCleanIsBlockedByAnyRunningOwnerAndRecoveryCleanRequiresTheExactToken() {
        CleanRecordingEconomicsService ordinary = new CleanRecordingEconomicsService();
        ordinary.runningOwners = 1;
        assertThrows(IllegalStateException.class, ordinary::clean);
        assertTrue(!ordinary.deleted);

        CleanRecordingEconomicsService recovery = new CleanRecordingEconomicsService();
        recovery.expectedToken = "recovery-owner";
        recovery.cleanForRecovery("recovery-owner");
        assertTrue(recovery.deleted);

        CleanRecordingEconomicsService wrongOwner = new CleanRecordingEconomicsService();
        wrongOwner.expectedToken = "recovery-owner";
        assertThrows(IllegalStateException.class,
                () -> wrongOwner.cleanForRecovery("different-owner"));
        assertTrue(!wrongOwner.deleted);
    }

    private static Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> emptyEntries() {
        Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> result =
                new EnumMap<>(PostingStatus.class);
        result.put(PostingStatus.BOOKED, Map.of());
        result.put(PostingStatus.DRAFT, Map.of());
        return result;
    }

    private static final class RecordingEconomicsService extends EconomicsService {
        private final List<Boolean> strictModes = new java.util.ArrayList<>();

        @Override
        Map<PostingStatus, Map<Range<Integer>, List<FinanceEntry>>> getAllEntriesCaptured(
                dk.trustworks.intranet.model.Company company, String date,
                boolean strictSupplementalSources) {
            strictModes.add(strictSupplementalSources);
            return emptyEntries();
        }
    }

    private static final class CleanRecordingEconomicsService extends EconomicsService {
        private long runningOwners;
        private String expectedToken;
        private boolean deleted;

        @Override
        long financeGlOwnerCount(String recoveryToken) {
            if (recoveryToken == null) return runningOwners;
            return recoveryToken.equals(expectedToken) ? 1 : 0;
        }

        @Override
        void deleteFinanceData() {
            deleted = true;
        }
    }
}
