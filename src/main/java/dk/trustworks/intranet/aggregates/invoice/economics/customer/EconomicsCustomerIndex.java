package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Immutable per-agreement lookup of e-conomic customers by CVR and normalised name.
 * Built from a single paged read of {@code GET /Customers}; cached per agreement with
 * a TTL — see {@link EconomicsCustomerIndexCache}.
 *
 * <p>Name normalisation: lower-cased, trimmed, whitespace-collapsed, with Danish legal
 * suffixes ({@code A/S}, {@code ApS}, {@code I/S}, {@code K/S}) stripped from the end.
 * This lets us match "Devoteam" to "Devoteam A/S" when the client's name is missing the
 * corporate form suffix.
 *
 * SPEC-INV-001 §3.3.1.
 */
public final class EconomicsCustomerIndex {

    /** Danish legal form suffixes, matched case-insensitively at the end of a name. */
    private static final List<String> LEGAL_SUFFIXES = List.of("a/s", "aps", "i/s", "k/s");

    private final Map<String, List<Integer>> byCvr;
    private final Map<String, List<Integer>> byName;
    private final Map<Integer, EconomicsCustomerDto> byNumber;
    private final List<EconomicsCustomerDto> all;

    public EconomicsCustomerIndex(List<EconomicsCustomerDto> customers) {
        this.byCvr    = groupByCvr(customers);
        this.byName   = groupNormalised(customers);
        this.byNumber = mapByNumber(customers);
        this.all      = List.copyOf(customers);
    }

    private static Map<Integer, EconomicsCustomerDto> mapByNumber(List<EconomicsCustomerDto> src) {
        Map<Integer, EconomicsCustomerDto> acc = new HashMap<>();
        for (EconomicsCustomerDto c : src) {
            if (c.getCustomerNumber() != null) acc.put(c.getCustomerNumber(), c);
        }
        return acc;
    }

    /** Unordered view of every e-conomic customer loaded into the index. */
    public List<EconomicsCustomerDto> allCustomers() {
        return all;
    }

    /** Looks up a customer by its e-conomic customer number. */
    public Optional<EconomicsCustomerDto> getByCustomerNumber(int customerNumber) {
        return Optional.ofNullable(byNumber.get(customerNumber));
    }

    private static Map<String, List<Integer>> groupByCvr(List<EconomicsCustomerDto> src) {
        Map<String, List<Integer>> acc = new HashMap<>();
        for (EconomicsCustomerDto c : src) {
            String k = c.getCvrNo();
            if (k == null || k.isBlank()) continue;
            acc.computeIfAbsent(k.trim(), x -> new ArrayList<>()).add(c.getCustomerNumber());
        }
        return acc;
    }

    private static Map<String, List<Integer>> groupNormalised(List<EconomicsCustomerDto> src) {
        Map<String, List<Integer>> acc = new HashMap<>();
        for (EconomicsCustomerDto c : src) {
            String n = normalise(c.getName());
            if (n == null) continue;
            acc.computeIfAbsent(n, x -> new ArrayList<>()).add(c.getCustomerNumber());
        }
        return acc;
    }

    /**
     * Lower-cases, trims, collapses whitespace, and strips Danish legal-form suffixes.
     * Returns {@code null} for null/blank input.
     */
    public static String normalise(String name) {
        if (name == null) return null;
        String s = name.trim();
        if (s.isEmpty()) return null;
        s = s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (String suffix : LEGAL_SUFFIXES) {
            if (s.endsWith(" " + suffix)) {
                s = s.substring(0, s.length() - suffix.length() - 1).trim();
                break;
            }
            if (s.equals(suffix)) {
                // Pathological: name is ONLY a suffix. Leave as-is so it remains matchable.
                break;
            }
        }
        return s.isEmpty() ? null : s;
    }

    public Optional<Integer> findByCvr(String cvr) {
        if (cvr == null) return Optional.empty();
        List<Integer> hits = byCvr.getOrDefault(cvr.trim(), List.of());
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    public boolean isAmbiguousCvr(String cvr) {
        if (cvr == null) return false;
        return byCvr.getOrDefault(cvr.trim(), List.of()).size() > 1;
    }

    public List<Integer> allCvrMatches(String cvr) {
        if (cvr == null) return List.of();
        return List.copyOf(byCvr.getOrDefault(cvr.trim(), List.of()));
    }

    public Optional<Integer> findByName(String name) {
        String n = normalise(name);
        if (n == null) return Optional.empty();
        List<Integer> hits = byName.getOrDefault(n, List.of());
        return hits.size() == 1 ? Optional.of(hits.get(0)) : Optional.empty();
    }

    public boolean isAmbiguousName(String name) {
        String n = normalise(name);
        if (n == null) return false;
        return byName.getOrDefault(n, List.of()).size() > 1;
    }

    public List<Integer> allNameMatches(String name) {
        String n = normalise(name);
        if (n == null) return List.of();
        return List.copyOf(byName.getOrDefault(n, List.of()));
    }
}
