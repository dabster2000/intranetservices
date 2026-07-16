package dk.trustworks.intranet.aggregates.practices.services;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueGlVoucherResolver.DocumentKind;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueGlVoucherResolver.Identifier;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueGlVoucherResolver.IdentifierKind;
import static dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueGlVoucherResolver.Outcome;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PracticeRevenueGlVoucherResolverTest {

    @Test void phantomEntryNumberResolvesTheSeedRowAndExpandsToTheWholeVoucherGroup() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 0, 10, "-60"),
                gl("co", 2025, 500, 0, 11, "-40"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows);

        assertEquals(Outcome.USABLE, resolution.outcome());
        assertEquals("co:2025:BOOKED:500", resolution.voucherGroupKey());
        // The single seed row expands to the FULL voucher group before summing, not by entry number.
        assertEquals(List.of(10L, 11L),
                PracticeRevenueGlVoucherResolver.voucherGroup(resolution.voucherGroupKey(), byGroup(rows))
                        .stream().map(PracticeRevenueMaterializationService.StoredGl::entryNumber).toList());
    }

    @Test void phantomEntryNumberWithNoMatchingRowIsMissingSoTheProvisionalPathIsAllowed() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 0, 11, "-100"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows);

        assertEquals(Outcome.MISSING, resolution.outcome());
        assertNull(resolution.voucherGroupKey());
    }

    @Test void phantomEntryNumberEqualToAVoucherNumberMustNotAccidentallyMatch() {
        // Row voucher number 42, entry number 99. The PHANTOM's entry number 42 numerically
        // equals the voucher number but no row carries entry number 42, so it must NOT match.
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 42, 0, 99, "-100"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 42)), rows);

        assertEquals(Outcome.MISSING, resolution.outcome());
    }

    @Test void phantomEntryNumberInTwoVoucherGroupsIsAmbiguous() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 0, 10, "-100"),
                gl("co", 2025, 600, 0, 10, "-100"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows);

        assertEquals(Outcome.AMBIGUOUS, resolution.outcome());
    }

    @Test void phantomEntryNumberIsBoundToTheTrustworksFiscalYear() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 0, 10, "-100"),
                gl("co", 2024, 700, 0, 10, "-100"));

        assertEquals("co:2025:BOOKED:500",
                PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2025,
                        List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows)
                        .voucherGroupKey());
        assertEquals(Outcome.MISSING,
                PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM, "co", 2023,
                        List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows)
                        .outcome());
    }

    @Test void ordinaryBookedReferenceAndVoucherNumbersMatchTheirExactStoredFields() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 7000, 1, "-100"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.ORDINARY, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_BOOKED_NUMBER, 7000),
                        new Identifier(IdentifierKind.REFERENCE_NUMBER, 7000),
                        new Identifier(IdentifierKind.ECONOMICS_VOUCHER_NUMBER, 500)), rows);

        assertEquals(Outcome.USABLE, resolution.outcome());
        assertEquals("co:2025:BOOKED:500", resolution.voucherGroupKey());
    }

    @Test void ordinaryConflictingIdentifiersOnDifferentGroupsAreAmbiguous() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("co", 2025, 500, 7000, 1, "-100"),
                gl("co", 2025, 600, 8000, 2, "-50"));

        var resolution = PracticeRevenueGlVoucherResolver.resolve(DocumentKind.ORDINARY, "co", 2025,
                List.of(new Identifier(IdentifierKind.ECONOMICS_BOOKED_NUMBER, 7000),
                        new Identifier(IdentifierKind.ECONOMICS_VOUCHER_NUMBER, 600)), rows);

        assertEquals(Outcome.AMBIGUOUS, resolution.outcome());
    }

    @Test void ordinaryWithNoPopulatedIdentifierIsMissing() {
        assertEquals(Outcome.MISSING, PracticeRevenueGlVoucherResolver.resolve(DocumentKind.ORDINARY,
                "co", 2025, List.of(), List.of(gl("co", 2025, 500, 7000, 1, "-100"))).outcome());
    }

    @Test void companyMismatchNeverMatches() {
        List<PracticeRevenueMaterializationService.StoredGl> rows = List.of(
                gl("other", 2025, 500, 0, 10, "-100"));

        assertEquals(Outcome.MISSING, PracticeRevenueGlVoucherResolver.resolve(DocumentKind.PHANTOM,
                "co", 2025, List.of(new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 10)), rows)
                .outcome());
    }

    @Test void identifierValueMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> new Identifier(IdentifierKind.ECONOMICS_ENTRY_NUMBER, 0));
    }

    private static PracticeRevenueMaterializationService.StoredGl gl(
            String company, int fiscal, long voucher, long invoice, long entry, String amount) {
        return new PracticeRevenueMaterializationService.StoredGl(company, fiscal, voucher, 0L, amount,
                "ident-" + voucher + "-" + entry, invoice, entry);
    }

    private static Map<String, List<PracticeRevenueMaterializationService.StoredGl>> byGroup(
            List<PracticeRevenueMaterializationService.StoredGl> rows) {
        return rows.stream().collect(Collectors.groupingBy(
                PracticeRevenueMaterializationService.StoredGl::groupKey, LinkedHashMap::new,
                Collectors.toList()));
    }
}
