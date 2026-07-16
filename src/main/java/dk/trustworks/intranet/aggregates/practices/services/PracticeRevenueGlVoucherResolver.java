package dk.trustworks.intranet.aggregates.practices.services;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves a recognized document to its unique Booked voucher-group control by exact identifier
 * namespace (Section 6.2). Each identifier is compared against the exact stored finance-details field
 * it represents; there is deliberately no boolean "which field" switch that could compare an entry
 * number against a voucher number.
 *
 * <p>An imported PHANTOM is located by its seed row's {@code economics_entry_number = entrynumber};
 * once the seed row resolves the group is expanded to every qualifying row sharing the exact
 * {@code (companyuuid, financial_year_start_year, BOOKED, vouchernumber)} key, never by entry number.
 * An ordinary invoice or credit note resolves through its booked/reference/voucher numbers and all
 * populated candidates must agree on one group.</p>
 */
final class PracticeRevenueGlVoucherResolver {

    private PracticeRevenueGlVoucherResolver() {
    }

    /** The exact stored finance-details field an identifier is matched against. No cross-namespace reuse. */
    enum IdentifierKind {
        ECONOMICS_BOOKED_NUMBER,
        REFERENCE_NUMBER,
        ECONOMICS_VOUCHER_NUMBER,
        ECONOMICS_ENTRY_NUMBER;

        long field(PracticeRevenueMaterializationService.StoredGl row) {
            return switch (this) {
                case ECONOMICS_BOOKED_NUMBER, REFERENCE_NUMBER -> row.invoiceNumber();
                case ECONOMICS_VOUCHER_NUMBER -> row.voucherNumber();
                case ECONOMICS_ENTRY_NUMBER -> row.entryNumber();
            };
        }
    }

    /** Ordinary invoice/credit identifiers must all agree; a PHANTOM seed's zero match is provisional. */
    enum DocumentKind { ORDINARY, PHANTOM }

    enum Outcome { MISSING, USABLE, AMBIGUOUS }

    record Identifier(IdentifierKind kind, long value) {
        Identifier {
            Objects.requireNonNull(kind, "kind");
            if (value <= 0) throw new IllegalArgumentException("identifier value must be positive");
        }
    }

    record Resolution(Outcome outcome, String voucherGroupKey) {
        static Resolution missing() {
            return new Resolution(Outcome.MISSING, null);
        }

        static Resolution ambiguous() {
            return new Resolution(Outcome.AMBIGUOUS, null);
        }

        static Resolution usable(String voucherGroupKey) {
            return new Resolution(Outcome.USABLE, Objects.requireNonNull(voucherGroupKey));
        }
    }

    static Resolution resolve(DocumentKind kind, String companyUuid, int fiscalYearStart,
                              List<Identifier> identifiers,
                              List<PracticeRevenueMaterializationService.StoredGl> rows) {
        if (identifiers.isEmpty()) return Resolution.missing();
        List<Set<String>> perIdentifier = identifiers.stream()
                .map(identifier -> resolveGroups(companyUuid, fiscalYearStart, identifier, rows))
                .toList();
        return switch (kind) {
            case PHANTOM -> resolveSeed(perIdentifier);
            case ORDINARY -> resolveAllAgree(perIdentifier);
        };
    }

    static List<PracticeRevenueMaterializationService.StoredGl> voucherGroup(
            String voucherGroupKey,
            Map<String, List<PracticeRevenueMaterializationService.StoredGl>> byGroup) {
        return byGroup.getOrDefault(voucherGroupKey, List.of());
    }

    private static Resolution resolveSeed(List<Set<String>> perIdentifier) {
        Set<String> distinct = perIdentifier.stream().flatMap(Set::stream)
                .collect(Collectors.toSet());
        if (distinct.isEmpty()) return Resolution.missing();
        if (distinct.size() != 1 || perIdentifier.stream().anyMatch(groups -> groups.size() != 1)) {
            return Resolution.ambiguous();
        }
        return Resolution.usable(distinct.iterator().next());
    }

    private static Resolution resolveAllAgree(List<Set<String>> perIdentifier) {
        if (perIdentifier.stream().anyMatch(groups -> groups.size() != 1)) return Resolution.ambiguous();
        String key = perIdentifier.getFirst().iterator().next();
        if (perIdentifier.stream().anyMatch(groups -> !groups.contains(key))) return Resolution.ambiguous();
        return Resolution.usable(key);
    }

    private static Set<String> resolveGroups(String companyUuid, int fiscalYearStart, Identifier identifier,
                                             List<PracticeRevenueMaterializationService.StoredGl> rows) {
        return rows.stream()
                .filter(row -> Objects.equals(companyUuid, row.companyUuid()))
                .filter(row -> fiscalYearStart == row.financialYearStart())
                .filter(row -> identifier.value() == identifier.kind().field(row))
                .map(PracticeRevenueMaterializationService.StoredGl::groupKey)
                .collect(Collectors.toSet());
    }
}
