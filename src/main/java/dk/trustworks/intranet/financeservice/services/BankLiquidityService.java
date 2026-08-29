package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsJournalsAPI;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.financeservice.model.BankFlowMonthly;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.remote.EconomicsBookedEntriesAPI;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Imports bank-account cash flows for all three companies from e-conomic into
 * {@code fact_bank_flow_monthly}, and serves the aggregated (group-level)
 * monthly flow series to the Growth &amp; Scenarios endpoints.
 *
 * <p>Two e-conomic sources per company (same client patterns as
 * {@link EconomicsService}):</p>
 * <ul>
 *   <li><b>Booked entries</b> — NEW Booked Entries API, all-time, filtered to
 *       the company's bank G/L accounts. Flows use {@code amountInBaseCurrency}
 *       and EXCLUDE opening entries ({@code type} 7, fiscal-year balance
 *       restatements). Verified 2026-08-29: cumulative flows equal the
 *       authoritative account balances to the øre for all three companies.</li>
 *   <li><b>Smart Bank draft legs</b> — NEW Journals API {@code /draft-entries}:
 *       unbooked feed lines whose account or contra-account is a bank account.
 *       They close the 3–4 week booking lag to roughly yesterday, and are
 *       disjoint from booked entries (a booked line leaves the draft journal),
 *       so nightly full reloads supersede drafts with no id-matching.</li>
 * </ul>
 *
 * <p>Bank accounts only, per CFO decision — Mastercard and petty-cash accounts
 * are excluded. The legacy Danske Bank accounts carry A/S history back to
 * Jul 2015 across the 2023 bank switch.</p>
 */
@JBossLog
@ApplicationScoped
public class BankLiquidityService {

    /**
     * Bank G/L accounts per company UUID. Deliberately code-level config: the
     * chart of accounts changes only on a bank switch, and each addition must
     * be a reviewed decision because it changes reported liquidity.
     */
    static final Map<String, Set<Integer>> BANK_ACCOUNTS = Map.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691", Set.of(8720, 8722, 8733, 8735), // Trustworks A/S (Nykredit + legacy Danske)
            "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3", Set.of(5820),                    // Trustworks Technology ApS
            "e4b0a2a4-0963-4153-b0a2-a409637153a2", Set.of(5820));                   // Trustworks Cyber Security ApS

    /** Opening entry — restates the balance at fiscal-year start; not a movement. */
    static final int ENTRY_TYPE_OPENING = 7;

    /** Case-insensitive text fragments identifying dividend-related bank movements. */
    static final List<String> DIVIDEND_TEXT_PATTERNS = List.of("udbytte", "udlod", "dividend");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ConfigProperty(name = "economics.booked-entries-api.url",
            defaultValue = "https://apis.e-conomic.com/bookedentriesapi/v4.0.0")
    URI bookedEntriesApiUri;

    @ConfigProperty(name = "quarkus.rest-client.economics-journals-api.url",
            defaultValue = "https://apis.e-conomic.com/journalsapi/v13.0.1")
    URI journalsApiUri;

    // ========================================================================
    // Import
    // ========================================================================

    /** Runs the import when the table is empty or older than {@code maxAgeHours}. */
    public void importIfStale(int maxAgeHours) {
        LocalDateTime newest = newestMaterializedAt();
        if (newest != null && newest.isAfter(LocalDateTime.now().minusHours(maxAgeHours))) {
            return;
        }
        importAll();
    }

    /** Full rebuild for every company with a bank-account mapping. */
    public void importAll() {
        List<Company> companies = Company.listAll();
        for (Company company : companies) {
            Set<Integer> accounts = BANK_ACCOUNTS.get(company.getUuid());
            if (accounts == null) {
                log.debugf("BankLiquidity: no bank-account mapping for company %s — skipped", company.getUuid());
                continue;
            }
            try {
                importCompany(company, accounts);
            } catch (Exception e) {
                // Per-company isolation: one agreement failing must not lose the others.
                log.errorf(e, "BankLiquidity import failed for company %s", company.getUuid());
            }
        }
    }

    void importCompany(Company company, Set<Integer> accounts) throws Exception {
        IntegrationKey.IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);

        List<BookedItem> booked = fetchBookedEntries(keys, accounts);
        List<JournalEntryResponse.Entry> drafts = fetchDraftEntries(keys);

        Map<String, double[]> byMonth = aggregateMonthly(booked, drafts, accounts);

        // Reconciliation: cumulative booked flows must equal the authoritative
        // account balances (they did, to the øre, when this import was built).
        double cumulativeBooked = byMonth.values().stream().mapToDouble(v -> v[0]).sum();
        Double authoritative = fetchAuthoritativeBalance(keys, accounts);
        if (authoritative != null && Math.abs(cumulativeBooked - authoritative) > 1.0) {
            log.errorf("BankLiquidity reconciliation drift for company %s: cumulative booked flows %.2f vs e-conomic balance %.2f",
                    company.getUuid(), cumulativeBooked, authoritative);
        }

        persistCompanyMonths(company.getUuid(), byMonth);
        log.infof("BankLiquidity imported %d months for company %s (booked entries=%d, draft bank legs counted in flows)",
                byMonth.size(), company.getUuid(), booked.size());
    }

    // ========================================================================
    // Pure aggregation logic (DB-free testable)
    // ========================================================================

    /**
     * Aggregates booked entries + draft bank legs into per-month
     * {@code [bookedFlow, draftFlow, dividendFlow]} (DKK, signed).
     */
    static Map<String, double[]> aggregateMonthly(
            List<BookedItem> booked,
            List<JournalEntryResponse.Entry> drafts,
            Set<Integer> bankAccounts) {

        Map<String, double[]> byMonth = new TreeMap<>();
        for (BookedItem item : booked) {
            if (item.type != null && item.type == ENTRY_TYPE_OPENING) continue;
            double flow = item.amountInBaseCurrency;
            double[] acc = byMonth.computeIfAbsent(monthKeyOf(item.date), k -> new double[3]);
            acc[0] += flow;
            if (isDividendText(item.text)) acc[2] += flow;
        }
        for (JournalEntryResponse.Entry entry : drafts) {
            Double flow = draftBankFlow(entry, bankAccounts);
            if (flow == null) continue;
            double[] acc = byMonth.computeIfAbsent(monthKeyOf(entry.date), k -> new double[3]);
            acc[1] += flow;
        }
        return byMonth;
    }

    /**
     * The bank leg of a Smart Bank draft line, in base currency DKK — or null
     * when the line doesn't touch one of the given bank accounts. A draft debits
     * {@code accountNumber} and credits {@code contraAccountNumber}, so the bank
     * movement is {@code +amount} when the bank is the account and
     * {@code -amount} when it is the contra account.
     */
    static Double draftBankFlow(JournalEntryResponse.Entry entry, Set<Integer> bankAccounts) {
        int account = entry.resolvedAccountNumber();
        Integer contra = entry.contraAccountNumber;
        boolean bankIsAccount = bankAccounts.contains(account);
        boolean bankIsContra = contra != null && bankAccounts.contains(contra);
        if (!bankIsAccount && !bankIsContra) return null;
        double base = EconomicsService.draftAmountInBaseCurrency(entry);
        return bankIsAccount ? base : -base;
    }

    static boolean isDividendText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        return DIVIDEND_TEXT_PATTERNS.stream().anyMatch(lower::contains);
    }

    /** YYYYMM of an e-conomic ISO date(-time) string. */
    static String monthKeyOf(String date) {
        LocalDate d = EconomicsService.parseEconomicsDate(date);
        return String.format("%04d%02d", d.getYear(), d.getMonthValue());
    }

    /** Mongo-style filter matching any of the given accounts, e.g. {@code (a$eq:1$or:a$eq:2)}. */
    static String accountFilter(Set<Integer> accounts) {
        List<Integer> sorted = accounts.stream().sorted().toList();
        String joined = String.join("$or:", sorted.stream().map(a -> "accountNumber$eq:" + a).toList());
        return sorted.size() > 1 ? "(" + joined + ")" : joined;
    }

    // ========================================================================
    // e-conomic fetches
    // ========================================================================

    private List<BookedItem> fetchBookedEntries(
            IntegrationKey.IntegrationKeyValue keys, Set<Integer> accounts) throws Exception {
        List<BookedItem> result = new ArrayList<>();
        try (EconomicsBookedEntriesAPI api = RestClientBuilder.newBuilder()
                .baseUri(bookedEntriesApiUri)
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsBookedEntriesAPI.class)) {
            String cursor = null;
            do {
                String json = api.getBookedEntries(accountFilter(accounts), cursor, 1000)
                        .readEntity(String.class);
                BookedEntriesPage page = MAPPER.readValue(json, BookedEntriesPage.class);
                if (page.items != null) result.addAll(page.items);
                cursor = page.cursor;
            } while (cursor != null && !cursor.isBlank());
        }
        return result;
    }

    private List<JournalEntryResponse.Entry> fetchDraftEntries(
            IntegrationKey.IntegrationKeyValue keys) throws Exception {
        // Drafts live weeks, not years — a 12-month window is generous.
        String filter = "date$gte:" + LocalDate.now().minusMonths(12);
        List<JournalEntryResponse.Entry> result = new ArrayList<>();
        try (EconomicsJournalsAPI api = RestClientBuilder.newBuilder()
                .baseUri(journalsApiUri)
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsJournalsAPI.class)) {
            String cursor = null;
            do {
                JournalEntryResponse response = api.getDraftEntries(filter, cursor, 1000);
                if (response != null && response.entries() != null) result.addAll(response.entries());
                cursor = response != null ? response.cursor : null;
            } while (cursor != null && !cursor.isBlank());
        }
        return result;
    }

    /** Sum of authoritative {@code balance} across the company's bank accounts (classic API). */
    private Double fetchAuthoritativeBalance(
            IntegrationKey.IntegrationKeyValue keys, Set<Integer> accounts) {
        if (keys.url() == null || keys.url().isBlank()) return null;
        double total = 0;
        try (EconomicsBookedEntriesAPI api = RestClientBuilder.newBuilder()
                .baseUri(URI.create(keys.url()))
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsBookedEntriesAPI.class)) {
            for (int account : accounts) {
                String json = api.getAccount(account).readEntity(String.class);
                AccountBalance parsed = MAPPER.readValue(json, AccountBalance.class);
                total += parsed.balance != null ? parsed.balance : 0.0;
            }
            return total;
        } catch (Exception e) {
            log.warnf(e, "BankLiquidity could not fetch authoritative balances — reconciliation skipped");
            return null;
        }
    }

    // ========================================================================
    // Persistence & reads
    // ========================================================================

    @Transactional
    void persistCompanyMonths(String companyuuid, Map<String, double[]> byMonth) {
        BankFlowMonthly.delete("companyuuid", companyuuid);
        LocalDateTime now = LocalDateTime.now();
        for (Map.Entry<String, double[]> e : byMonth.entrySet()) {
            double[] v = e.getValue();
            new BankFlowMonthly(
                    BankFlowMonthly.idOf(companyuuid, e.getKey()),
                    companyuuid,
                    e.getKey(),
                    round2(v[0]),
                    round2(v[1]),
                    round2(v[2]),
                    now).persist();
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    LocalDateTime newestMaterializedAt() {
        return BankFlowMonthly.find("ORDER BY materializedAt DESC")
                .<BankFlowMonthly>firstResultOptional()
                .map(BankFlowMonthly::getMaterializedAt)
                .orElse(null);
    }

    /**
     * One aggregated (all companies) month of bank flow. {@code totalFlow}
     * includes unbooked Smart Bank drafts; {@code bookedFlow} is the booked
     * subset — the gap between their cumulative sums is money that has left
     * (or reached) the bank but not yet the bookkeeping.
     */
    public record GroupFlowMonth(String monthKey, double totalFlow, double bookedFlow, double dividendFlow) {}

    /**
     * Group-level monthly flows (booked + draft, all companies summed),
     * chronologically ordered. Empty until the first import has run.
     */
    public List<GroupFlowMonth> groupMonthlyFlows() {
        List<BankFlowMonthly> rows = BankFlowMonthly.listAll();
        Map<String, double[]> byMonth = new TreeMap<>();
        for (BankFlowMonthly row : rows) {
            double[] acc = byMonth.computeIfAbsent(row.getMonthKey(), k -> new double[3]);
            acc[0] += row.getBookedFlowDkk() + row.getDraftFlowDkk();
            acc[1] += row.getBookedFlowDkk();
            acc[2] += row.getDividendFlowDkk();
        }
        List<GroupFlowMonth> result = new ArrayList<>(byMonth.size());
        for (Map.Entry<String, double[]> e : byMonth.entrySet()) {
            result.add(new GroupFlowMonth(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]));
        }
        return result;
    }

    // ========================================================================
    // Wire shapes (Booked Entries API)
    // ========================================================================

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BookedEntriesPage {
        public List<BookedItem> items;
        public String cursor;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class BookedItem {
        public Integer accountNumber;
        public double amount;
        public double amountInBaseCurrency;
        public String currencyCode;
        public String date;
        public String text;
        public Integer type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AccountBalance {
        public Double balance;
    }
}
