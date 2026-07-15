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

    private static PracticeRevenueDependencyManifestProvider.Dependency dependency(
            String document, String month, String kind, String from, String to) {
        return new PracticeRevenueDependencyManifestProvider.Dependency(
                document, document + "-item", "INVOICE", date(month), kind,
                document + "-source", null, date(from), date(to), document + "-fingerprint");
    }
    private static LocalDate date(String value) { return LocalDate.parse(value); }
}
