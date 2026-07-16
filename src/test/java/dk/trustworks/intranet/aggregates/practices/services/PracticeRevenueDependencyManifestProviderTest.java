package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PracticeRevenueDependencyManifestProviderTest {
    private final PracticeRevenueDependencyManifestProvider provider =
            new PracticeRevenueDependencyManifestProvider();

    @Test
    void unionIncludesOneHopCreditSourceBeforeRecognitionWindowAndIsDeterministic() {
        var creditSource = dependency("credit", "2026-06-01", "CREDIT_SOURCE_DOCUMENT",
                "2020-01-15", "2020-01-31");
        var direct = dependency("ordinary", "2026-07-01", "DIRECT_DELIVERY",
                "2026-05-10", "2026-07-15");
        var a = provider.fromDependencies(List.of(direct, creditSource),
                date("2026-01-01"), date("2026-12-31"));
        var b = provider.fromDependencies(List.of(creditSource, direct),
                date("2026-01-01"), date("2026-12-31"));

        assertEquals(date("2020-01-15"), a.coverageStart());
        assertEquals(date("2026-12-31"), a.coverageEnd());
        assertEquals(a.fingerprint(), b.fingerprint());
    }

    @Test
    void rejectsMissingOrExpandedConsumedEvidence() {
        var one = dependency("one", "2026-01-01", "DIRECT_DELIVERY",
                "2026-01-01", "2026-01-31");
        var manifest = provider.fromDependencies(List.of(one),
                date("2026-01-01"), date("2026-12-31"));
        var expanded = dependency("one", "2026-01-01", "DIRECT_DELIVERY",
                "2025-12-31", "2026-01-31");
        assertThrows(PracticeRevenueDependencyManifestProvider.BasisCoverageMissException.class,
                () -> provider.assertCovered(manifest, List.of(expanded)));
    }

    @Test
    void registeredWorkDeliveryEarlierThanInvoiceDateExpandsCoverageStart() {
        // An ordinary item whose registered-work delivery predates its invoice recognition month must pull
        // the basis coverage back to the delivery date, not stop at the recognition month.
        var billing = dependency("inv", "2026-07-01", "DIRECT_BILLING_PERIOD",
                "2026-07-01", "2026-07-01");
        var delivery = dependency("inv", "2026-07-01", "REGISTERED_WORK_DELIVERY",
                "2026-03-05", "2026-03-05");
        var manifest = provider.fromDependencies(List.of(billing, delivery),
                date("2026-07-01"), date("2026-07-31"));
        assertEquals(date("2026-03-05"), manifest.coverageStart());
        assertEquals(date("2026-07-31"), manifest.coverageEnd());
    }

    @Test
    void forwardCapacityHorizonIsNeverPulledInByEarlierDependencies() {
        var delivery = dependency("inv", "2026-01-01", "REGISTERED_WORK_DELIVERY",
                "2025-11-10", "2025-11-10");
        var manifest = provider.fromDependencies(List.of(delivery),
                date("2026-01-01"), date("2026-12-31"));
        assertEquals(date("2025-11-10"), manifest.coverageStart());
        assertEquals(date("2026-12-31"), manifest.coverageEnd());
    }

    @Test
    void assertCoveredAcceptsAnIdenticalConsumedDependencySet() {
        var one = dependency("inv", "2026-01-01", "DIRECT_BILLING_PERIOD",
                "2026-01-05", "2026-01-05");
        var two = dependency("credit", "2026-02-01", "CREDIT_SOURCE_DOCUMENT",
                "2021-06-01", "2021-06-30");
        var manifest = provider.fromDependencies(List.of(one, two),
                date("2026-01-01"), date("2026-12-31"));
        // Same set, different order -> covered (no exception).
        provider.assertCovered(manifest, List.of(two, one));
    }

    @Test
    void manifestFingerprintChangesWhenANewDependencyIsIntroduced() {
        var base = dependency("inv", "2026-01-01", "DIRECT_BILLING_PERIOD",
                "2026-01-05", "2026-01-05");
        var expanded = dependency("inv", "2026-01-01", "REGISTERED_WORK_DELIVERY",
                "2025-12-30", "2025-12-30");
        var before = provider.fromDependencies(List.of(base), date("2026-01-01"), date("2026-12-31"));
        var after = provider.fromDependencies(List.of(base, expanded),
                date("2026-01-01"), date("2026-12-31"));
        assertThrows(PracticeRevenueDependencyManifestProvider.BasisCoverageMissException.class,
                () -> provider.assertCovered(before, List.of(base, expanded)));
        org.junit.jupiter.api.Assertions.assertNotEquals(before.fingerprint(), after.fingerprint());
    }

    @Test
    void serviceMonthDeliveryBeforeRecognitionWindowExpandsCoverageAndChangesFingerprint() {
        // An arrears invoice recognized in 2026-07 but serviced in 2023-02 consumes delivery dates in its
        // service month; the manifest must cover [service-month-start, service-month-end], not just the
        // recognition month, otherwise the revenue build misses forever.
        var billing = dependency("arrears", "2026-07-01", "DIRECT_BILLING_PERIOD",
                "2026-07-01", "2026-07-01");
        var serviceMonth = dependency("arrears", "2026-07-01", "SERVICE_MONTH_DELIVERY",
                "2023-02-01", "2023-02-28");
        var withoutServiceMonth = provider.fromDependencies(List.of(billing),
                date("2026-07-01"), date("2026-07-31"));
        var withServiceMonth = provider.fromDependencies(List.of(billing, serviceMonth),
                date("2026-07-01"), date("2026-07-31"));

        assertEquals(date("2023-02-01"), withServiceMonth.coverageStart());
        org.junit.jupiter.api.Assertions.assertNotEquals(
                withoutServiceMonth.fingerprint(), withServiceMonth.fingerprint());
        assertThrows(PracticeRevenueDependencyManifestProvider.BasisCoverageMissException.class,
                () -> provider.assertCovered(withoutServiceMonth, List.of(billing, serviceMonth)));
    }

    @Test
    void documentScanEmitsServiceMonthDeliveryForBothTheDocumentAndItsOneHopCreditSource() {
        String sql = PracticeRevenueDependencyManifestProvider.DOCUMENT_SCAN_SQL;
        assertEquals(2, countOccurrences(sql, "'SERVICE_MONTH_DELIVERY'"),
                "one branch for the document, one for its one-hop credit source");
        // Bounds come from the invoice service month (year/month), guarded to valid values, ending at LAST_DAY.
        assertEquals(true, sql.contains("CONCAT(i.year, '-', LPAD(i.month, 2, '0'), '-01')"));
        assertEquals(true, sql.contains("CONCAT(src.year, '-', LPAD(src.month, 2, '0'), '-01')"));
        assertEquals(true, sql.contains("LAST_DAY(STR_TO_DATE(CONCAT(i.year"));
        assertEquals(true, sql.contains("i.year BETWEEN 2000 AND 2100 AND i.month BETWEEN 1 AND 12"));
        assertEquals(true, sql.contains("src.year BETWEEN 2000 AND 2100 AND src.month BETWEEN 1 AND 12"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    private static PracticeRevenueDependencyManifestProvider.Dependency dependency(
            String document, String month, String kind, String from, String to) {
        return new PracticeRevenueDependencyManifestProvider.Dependency(
                document, document + "-item", "INVOICE", date(month), kind,
                document + "-source", null, date(from), date(to), document + "-fingerprint");
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }

    /**
     * Real scan rows carry NULL recognized_item_uuid (zero-item documents) and NULL source_item_uuid
     * on most rows. The row mapper must fingerprint them null-safely — List.of would NPE and crash
     * the whole cost/basis build (observed on the first staging build against production-shaped data).
     */
    @Test
    void scanMapsRowsWithNullItemAndSourceUuidsWithoutCrashing() {
        var provider = new PracticeRevenueDependencyManifestProvider();
        var query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        var em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(query);
        org.mockito.Mockito.when(query.setParameter(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(query);
        org.mockito.Mockito.when(query.getResultList()).thenReturn(java.util.List.<Object[]>of(
                // zero-item document: recognized_item_uuid and source_item_uuid both NULL
                new Object[]{"doc-1", null, "INVOICE", LocalDate.parse("2026-02-01"),
                        "DIRECT_BILLING_PERIOD", "doc-1", null,
                        LocalDate.parse("2026-02-10"), LocalDate.parse("2026-02-10")},
                // ordinary item row with NULL source_item_uuid
                new Object[]{"doc-2", "item-2", "CREDIT_NOTE", LocalDate.parse("2026-03-01"),
                        "CREDIT_SOURCE_DOCUMENT", "src-2", null,
                        LocalDate.parse("2025-11-05"), LocalDate.parse("2025-11-05")}));
        provider.em = em;

        var manifest = provider.scan(java.time.YearMonth.of(2026, 2), java.time.YearMonth.of(2026, 3));

        assertEquals(2, manifest.dependencies().size());
        assertEquals(LocalDate.parse("2025-11-05"), manifest.coverageStart());
        assertEquals(64, manifest.dependencies().getFirst().sourceFingerprint().length());
    }

    /** A NULL date from the scan must fail closed as INVALID_DEPENDENCY_BOUNDS, never crash parsing. */
    @Test
    void scanRowWithNullRequiredDateFailsClosedNotWithAParseCrash() {
        var provider = new PracticeRevenueDependencyManifestProvider();
        var query = org.mockito.Mockito.mock(jakarta.persistence.Query.class);
        var em = org.mockito.Mockito.mock(jakarta.persistence.EntityManager.class);
        org.mockito.Mockito.when(em.createNativeQuery(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(query);
        org.mockito.Mockito.when(query.setParameter(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any())).thenReturn(query);
        org.mockito.Mockito.when(query.getResultList()).thenReturn(java.util.List.<Object[]>of(
                new Object[]{"doc-1", null, "INVOICE", LocalDate.parse("2026-02-01"),
                        "DIRECT_BILLING_PERIOD", "doc-1", null, null, null}));
        provider.em = em;

        assertThrows(PracticeRevenueDependencyManifestProvider.BasisCoverageMissException.class,
                () -> provider.scan(java.time.YearMonth.of(2026, 2), java.time.YearMonth.of(2026, 2)));
    }
}
