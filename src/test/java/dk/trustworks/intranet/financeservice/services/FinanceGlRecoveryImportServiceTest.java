package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import dk.trustworks.intranet.model.Company;
import org.apache.commons.lang3.Range;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinanceGlRecoveryImportServiceTest {

    private static final LocalDate FROM = LocalDate.parse("2021-07-01");
    private static final LocalDate TO = LocalDate.parse("2026-06-30");
    private static final String TOKEN = "1b59dc41-d35a-4e9d-968d-47a13d6b734f";

    @Test
    void rebuildsEveryCompanyFiscalPeriodAndExactHistoricalCreditSourcePeriod() {
        Company alpha = company("alpha");
        Company beta = company("beta");
        EconomicsService economics = mock(EconomicsService.class);
        when(economics.getAllEntriesForRecovery(any(), anyString())).thenReturn(emptyEntries());
        TestService service = new TestService(
                List.of(beta, alpha),
                List.of(
                        new FinanceGlRecoveryImportService.DocumentDependency(
                                "current", "alpha", LocalDate.parse("2025-02-15")),
                        new FinanceGlRecoveryImportService.DocumentDependency(
                                "historical-source", "alpha", LocalDate.parse("2020-08-20"))));
        service.economicsService = economics;

        FinanceGlRecoveryImportService.RecoverySummary summary = service.rebuild(FROM, TO, TOKEN);

        assertEquals(2, summary.companyCount());
        assertEquals(11, summary.fiscalPeriodCount());
        assertEquals(2, summary.documentDependencyCount());
        verify(economics).cleanForRecovery(TOKEN);
        verify(economics, times(11)).persistExpenses(any());
        ArgumentCaptor<String> periods = ArgumentCaptor.forClass(String.class);
        verify(economics, times(11)).getAllEntriesForRecovery(any(), periods.capture());
        assertTrue(periods.getAllValues().contains("2020_6_2021"));
        assertTrue(periods.getAllValues().contains("2025_6_2026"));
        assertTrue(service.ownerChecks.stream().allMatch(TOKEN::equals));
    }

    @Test
    void neverSwallowsAPerCompanyStrictImportFailure() {
        Company alpha = company("alpha");
        Company beta = company("beta");
        EconomicsService economics = mock(EconomicsService.class);
        when(economics.getAllEntriesForRecovery(any(), anyString())).thenReturn(emptyEntries());
        when(economics.getAllEntriesForRecovery(beta, "2023_6_2024"))
                .thenThrow(new IllegalStateException("FINANCE_GL_DRAFT_SUPPLIER_FAILED"));
        TestService service = new TestService(List.of(alpha, beta), List.of());
        service.economicsService = economics;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rebuild(FROM, TO, TOKEN));

        assertEquals("FINANCE_GL_DRAFT_SUPPLIER_FAILED", failure.getMessage());
        verify(economics).cleanForRecovery(TOKEN);
        verify(economics).getAllEntriesForRecovery(beta, "2023_6_2024");
        verify(economics, never()).getAllEntries(beta, "2023_6_2024");
    }

    @Test
    void rejectsAnythingOtherThanExactlySixtyCompleteMonthsBeforeMutation() {
        EconomicsService economics = mock(EconomicsService.class);
        TestService service = new TestService(List.of(company("alpha")), List.of());
        service.economicsService = economics;

        assertThrows(IllegalArgumentException.class, () -> service.rebuild(
                LocalDate.parse("2021-07-02"), TO, TOKEN));
        assertThrows(IllegalArgumentException.class, () -> service.rebuild(
                FROM, LocalDate.parse("2026-05-31"), TOKEN));

        assertTrue(service.ownerChecks.isEmpty());
        verify(economics, never()).cleanForRecovery(anyString());
    }

    @Test
    void incompleteOrUnknownDocumentDependencyStopsBeforeDestructiveClean() {
        EconomicsService economics = mock(EconomicsService.class);
        TestService service = new TestService(
                List.of(company("alpha")),
                List.of(new FinanceGlRecoveryImportService.DocumentDependency(
                        "source", "unknown-company", LocalDate.parse("2020-08-20"))));
        service.economicsService = economics;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rebuild(FROM, TO, TOKEN));

        assertEquals("FINANCE_GL_DOCUMENT_COMPANY_UNKNOWN", failure.getMessage());
        verify(economics, never()).cleanForRecovery(anyString());
    }

    @Test
    void rejectsControlRowsOutsideTheClaimedFiscalPeriod() {
        EconomicsService economics = mock(EconomicsService.class);
        when(economics.getAllEntriesForRecovery(any(), anyString())).thenReturn(entries(
                new EconomicsService.FinanceEntry(
                        LocalDate.parse("2019-01-01"), 1000, 1.0, PostingStatus.BOOKED)));
        TestService service = new TestService(List.of(company("alpha")), List.of());
        service.economicsService = economics;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rebuild(FROM, TO, TOKEN));

        assertEquals("FINANCE_GL_CONTROL_PERIOD_OUT_OF_SCOPE", failure.getMessage());
        verify(economics, never()).persistExpenses(any());
    }

    @Test
    void ownershipLossDuringFetchStopsBeforeAggregatePersistenceAndCannotReportSuccess() {
        EconomicsService economics = mock(EconomicsService.class);
        when(economics.getAllEntriesForRecovery(any(), anyString())).thenReturn(emptyEntries());
        TestService service = new TestService(List.of(company("alpha")), List.of());
        service.economicsService = economics;
        service.failOwnerCheckAt = 4;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.rebuild(FROM, TO, TOKEN));

        assertEquals("FINANCE_GL_RECOVERY_OWNER_CHANGED", failure.getMessage());
        verify(economics).cleanForRecovery(TOKEN);
        verify(economics).getAllEntriesForRecovery(any(), anyString());
        verify(economics, never()).persistExpenses(any());
    }

    private static Company company(String uuid) {
        Company company = new Company();
        company.setUuid(uuid);
        company.setCreated(LocalDateTime.parse("2019-01-01T00:00:00"));
        return company;
    }

    private static Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> emptyEntries() {
        return entries();
    }

    private static Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> entries(
            EconomicsService.FinanceEntry... booked) {
        Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> result =
                new EnumMap<>(PostingStatus.class);
        result.put(PostingStatus.BOOKED, Map.of(Range.between(1000, 1999), List.of(booked)));
        result.put(PostingStatus.DRAFT, Map.of());
        return result;
    }

    private static final class TestService extends FinanceGlRecoveryImportService {
        private final List<Company> companies;
        private final List<DocumentDependency> dependencies;
        private final List<String> ownerChecks = new ArrayList<>();
        private int failOwnerCheckAt = Integer.MAX_VALUE;

        private TestService(List<Company> companies, List<DocumentDependency> dependencies) {
            this.companies = companies;
            this.dependencies = dependencies;
        }

        @Override
        List<Company> loadCompanies() {
            return companies;
        }

        @Override
        List<DocumentDependency> loadDocumentDependencies(LocalDate fromInclusive, LocalDate toInclusive) {
            return dependencies;
        }

        @Override
        void assertRecoveryOwner(String recoveryToken) {
            ownerChecks.add(recoveryToken);
            if (ownerChecks.size() == failOwnerCheckAt) {
                throw new IllegalStateException("FINANCE_GL_RECOVERY_OWNER_CHANGED");
            }
        }
    }
}
