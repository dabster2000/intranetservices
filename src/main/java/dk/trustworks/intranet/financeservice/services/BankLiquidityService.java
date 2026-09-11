package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsJournalsAPI;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.financeservice.model.BankFlowMonthly;
import dk.trustworks.intranet.financeservice.model.BankLedgerEntry;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.enums.FlowKind;
import dk.trustworks.intranet.financeservice.remote.EconomicsBookedEntriesAPI;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Imports bank-account cash flows for all three companies from e-conomic into
 * {@code fact_bank_flow_monthly} (monthly sums) and {@code fact_bank_ledger_entry}
 * (per-entry lines, classified), and serves the aggregated series to the
 * Growth &amp; Scenarios endpoints.
 *
 * <p>Three e-conomic sources per company (same client patterns as
 * {@link EconomicsService}):</p>
 * <ul>
 *   <li><b>Bank booked entries</b> — NEW Booked Entries API, all-time, filtered
 *       to the company's bank G/L accounts. Flows use {@code amountInBaseCurrency}
 *       and EXCLUDE opening entries ({@code type} 7, fiscal-year balance
 *       restatements). Verified 2026-08-29: cumulative flows equal the
 *       authoritative account balances to the øre for all three companies.</li>
 *   <li><b>Debtor booked entries</b> — the customer receivables G/L account
 *       (A/S 8610, subsidiaries 5600). Every invoice posting (type 1) and every
 *       customer payment (type 2) carries the customer invoice number, so the
 *       signed sum per invoice number is that invoice's open remainder at any
 *       as-of date, and the payment date per invoice is exact.</li>
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

    /**
     * Customer receivables (debtor) G/L account per company UUID — verified
     * 2026-09-11 against live entries: every sales invoice posts here with its
     * customer invoice number, and every matched customer payment reverses it.
     */
    static final Map<String, Integer> DEBTOR_ACCOUNTS = Map.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691", 8610,
            "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3", 5600,
            "e4b0a2a4-0963-4153-b0a2-a409637153a2", 5600);

    /** Opening entry — restates the balance at fiscal-year start; not a movement. */
    static final int ENTRY_TYPE_OPENING = 7;
    static final int ENTRY_TYPE_INVOICE = 1;
    /** Sales invoices booked through e-conomic's invoicing module post with this type. */
    static final int ENTRY_TYPE_CUSTOMER_INVOICE = 10;
    static final int ENTRY_TYPE_CUSTOMER_PAYMENT = 2;
    static final int ENTRY_TYPE_SUPPLIER_PAYMENT = 4;

    /** Case-insensitive text fragments identifying dividend-related bank movements. */
    static final List<String> DIVIDEND_TEXT_PATTERNS = List.of("udbytte", "udlod", "dividend");

    // Voucher-text patterns, checked in this order (lower-cased text). Measured
    // on the live A/S, Technology and Cyber Security bank ledgers 2026-09-11.
    private static final Pattern P_CORPORATE_TAX = Pattern.compile("acontoskat|aconto ?skat|aconto rate|restskat|selskabsskat|told og skat");
    private static final Pattern P_PAYROLL_TAX = Pattern.compile("a-skat|am-bidrag|am bidrag|a skat|skattekonto");
    private static final Pattern P_VAT = Pattern.compile("moms");
    private static final Pattern P_ATP = Pattern.compile("\\batp\\b");
    private static final Pattern P_VACATION = Pattern.compile("feriep|feriekonto|\\bferie");
    private static final Pattern P_INSURANCE = Pattern.compile("sundhed|forsikring");
    private static final Pattern P_PENSION = Pattern.compile("pension|danica|\\bpfa\\b|velliv");
    private static final Pattern P_SALARY = Pattern.compile("l(ø|oe)n\\b|l(ø|oe)nninger|danl(ø|oe)n|salary|payroll");
    private static final Pattern P_RENT = Pattern.compile("husleje");
    private static final Pattern P_CARD = Pattern.compile("pleo|mastercard");
    private static final Pattern P_PUBLIC_REFUND = Pattern.compile("udbetaling danmark|barsel|sygedagpenge|refusion");
    private static final Pattern P_INTEREST = Pattern.compile("\\brente|gebyr");
    private static final Pattern P_INTERCOMPANY = Pattern.compile("trustworks|\\btw tech|\\btw cyber|\\btw a/s|mellemregn|fak\\.? ?ref");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int INSERT_CHUNK = 200;

    @ConfigProperty(name = "economics.booked-entries-api.url",
            defaultValue = "https://apis.e-conomic.com/bookedentriesapi/v4.0.0")
    URI bookedEntriesApiUri;

    @ConfigProperty(name = "quarkus.rest-client.economics-journals-api.url",
            defaultValue = "https://apis.e-conomic.com/journalsapi/v13.0.1")
    URI journalsApiUri;

    @Inject
    EntityManager em;

    // ========================================================================
    // Import
    // ========================================================================

    /**
     * Runs the import when the tables are empty or older than {@code maxAgeHours}.
     * An empty ledger table with a fresh monthly table (first boot after the
     * ledger table was introduced) also triggers a run.
     */
    public void importIfStale(int maxAgeHours) {
        LocalDateTime newest = newestMaterializedAt();
        if (newest != null && newest.isAfter(LocalDateTime.now().minusHours(maxAgeHours))
                && BankLedgerEntry.count() > 0) {
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
        Integer debtorAccount = DEBTOR_ACCOUNTS.get(company.getUuid());
        List<BookedItem> debtor = debtorAccount != null
                ? fetchBookedEntries(keys, Set.of(debtorAccount))
                : List.of();
        Set<Integer> internalInvoiceNumbers = queryInternalInvoiceNumbers(company.getUuid());

        Map<String, double[]> byMonth = aggregateMonthly(booked, drafts, accounts);
        List<BankLedgerEntry> entries = buildLedgerEntries(
                company.getUuid(), booked, drafts, debtor, accounts, internalInvoiceNumbers);

        // Reconciliation: cumulative booked flows must equal the authoritative
        // account balances (they did, to the øre, when this import was built).
        double cumulativeBooked = byMonth.values().stream().mapToDouble(v -> v[0]).sum();
        Double authoritative = fetchAuthoritativeBalance(keys, accounts);
        if (authoritative != null && Math.abs(cumulativeBooked - authoritative) > 1.0) {
            log.errorf("BankLiquidity reconciliation drift for company %s: cumulative booked flows %.2f vs e-conomic balance %.2f",
                    company.getUuid(), cumulativeBooked, authoritative);
        }

        persistCompany(company.getUuid(), byMonth, entries);
        log.infof("BankLiquidity imported %d months and %d ledger lines for company %s (bank booked=%d, debtor booked=%d, drafts=%d)",
                byMonth.size(), entries.size(), company.getUuid(), booked.size(), debtor.size(), drafts.size());
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
     * Builds the per-entry ledger rows for one company: classified bank lines
     * (booked, opening entries dropped), classified draft bank legs, and the
     * debtor ledger lines keyed by customer invoice number.
     */
    static List<BankLedgerEntry> buildLedgerEntries(
            String companyuuid,
            List<BookedItem> bankBooked,
            List<JournalEntryResponse.Entry> drafts,
            List<BookedItem> debtorBooked,
            Set<Integer> bankAccounts,
            Set<Integer> internalInvoiceNumbers) {

        LocalDateTime now = LocalDateTime.now();
        List<BankLedgerEntry> result = new ArrayList<>(bankBooked.size() + drafts.size() + debtorBooked.size());
        for (BookedItem item : bankBooked) {
            if (item.type != null && item.type == ENTRY_TYPE_OPENING) continue;
            FlowKind kind = classify(item, internalInvoiceNumbers);
            result.add(new BankLedgerEntry(
                    companyuuid + "|" + BankLedgerEntry.LEDGER_BANK + "|B|" + item.entryNumber,
                    companyuuid, BankLedgerEntry.LEDGER_BANK,
                    EconomicsService.parseEconomicsDate(item.date),
                    round2(item.amountInBaseCurrency), item.type, false, kind.name(),
                    item.customerInvoiceNumber, null, truncate(item.text), now));
        }
        for (JournalEntryResponse.Entry entry : drafts) {
            Double flow = draftBankFlow(entry, bankAccounts);
            if (flow == null) continue;
            Integer invoiceNumber = parseInvoiceNumber(entry.customerInvoiceNumber);
            FlowKind kind = classifyDraft(entry, invoiceNumber, internalInvoiceNumbers);
            result.add(new BankLedgerEntry(
                    companyuuid + "|" + BankLedgerEntry.LEDGER_BANK + "|D|" + entry.journalNumber + "-" + entry.entryNumber,
                    companyuuid, BankLedgerEntry.LEDGER_BANK,
                    EconomicsService.parseEconomicsDate(entry.date),
                    round2(flow), null, true, kind.name(),
                    invoiceNumber, null, truncate(entry.text), now));
        }
        for (BookedItem item : debtorBooked) {
            if (item.type != null && item.type == ENTRY_TYPE_OPENING) continue;
            FlowKind kind = item.type == null ? FlowKind.DEBTOR_OTHER
                    : item.type == ENTRY_TYPE_INVOICE || item.type == ENTRY_TYPE_CUSTOMER_INVOICE ? FlowKind.DEBTOR_INVOICE
                    : item.type == ENTRY_TYPE_CUSTOMER_PAYMENT ? FlowKind.DEBTOR_PAYMENT
                    : FlowKind.DEBTOR_OTHER;
            result.add(new BankLedgerEntry(
                    companyuuid + "|" + BankLedgerEntry.LEDGER_DEBTOR + "|B|" + item.entryNumber,
                    companyuuid, BankLedgerEntry.LEDGER_DEBTOR,
                    EconomicsService.parseEconomicsDate(item.date),
                    round2(item.amountInBaseCurrency), item.type, false, kind.name(),
                    item.customerInvoiceNumber, item.remainder != null ? round2(item.remainder) : null,
                    truncate(item.text), now));
        }
        return result;
    }

    /**
     * Classifies one booked bank line. The e-conomic entry type decides customer
     * receipts and supplier payments; finance vouchers (salary, tax, VAT,
     * transfers) are classified from the bookkeeper's text.
     */
    static FlowKind classify(BookedItem item, Set<Integer> internalInvoiceNumbers) {
        String text = item.text == null ? "" : item.text.toLowerCase(Locale.ROOT);
        if (isDividendText(item.text)) return FlowKind.DIVIDEND;
        if (item.type != null && item.type == ENTRY_TYPE_CUSTOMER_PAYMENT) {
            return item.customerInvoiceNumber != null && internalInvoiceNumbers.contains(item.customerInvoiceNumber)
                    ? FlowKind.INTERCOMPANY : FlowKind.CUSTOMER_RECEIPT;
        }
        if (item.type != null && item.type == ENTRY_TYPE_SUPPLIER_PAYMENT) {
            return P_INTERCOMPANY.matcher(text).find() ? FlowKind.INTERCOMPANY : FlowKind.SUPPLIER_PAYMENT;
        }
        return classifyText(text);
    }

    /** Draft legs: an invoice number or supplier number identifies the kind; else the bank's text. */
    static FlowKind classifyDraft(JournalEntryResponse.Entry entry, Integer invoiceNumber, Set<Integer> internalInvoiceNumbers) {
        String text = entry.text == null ? "" : entry.text.toLowerCase(Locale.ROOT);
        if (isDividendText(entry.text)) return FlowKind.DIVIDEND;
        if (invoiceNumber != null) {
            return internalInvoiceNumbers.contains(invoiceNumber) ? FlowKind.INTERCOMPANY : FlowKind.CUSTOMER_RECEIPT;
        }
        if (entry.supplierInvoiceNumber != null && !entry.supplierInvoiceNumber.isBlank()) {
            return P_INTERCOMPANY.matcher(text).find() ? FlowKind.INTERCOMPANY : FlowKind.SUPPLIER_PAYMENT;
        }
        FlowKind byText = classifyText(text);
        return byText == FlowKind.OTHER ? FlowKind.DRAFT : byText;
    }

    /** Text-only classification of a finance voucher (lower-cased text). */
    static FlowKind classifyText(String text) {
        if (P_CORPORATE_TAX.matcher(text).find()) return FlowKind.CORPORATE_TAX;
        if (P_PAYROLL_TAX.matcher(text).find()) return FlowKind.PAYROLL_TAX;
        if (P_VAT.matcher(text).find()) return FlowKind.VAT;
        if (P_ATP.matcher(text).find()) return FlowKind.ATP;
        if (P_VACATION.matcher(text).find()) return FlowKind.VACATION_PAY;
        if (P_INSURANCE.matcher(text).find()) return FlowKind.OTHER;
        if (P_PENSION.matcher(text).find()) return FlowKind.PENSION;
        if (P_INTEREST.matcher(text).find()) return FlowKind.INTEREST;
        if (P_SALARY.matcher(text).find()) return FlowKind.SALARY;
        if (P_RENT.matcher(text).find()) return FlowKind.RENT;
        if (P_CARD.matcher(text).find()) return FlowKind.CARD_TOPUP;
        if (P_PUBLIC_REFUND.matcher(text).find()) return FlowKind.PUBLIC_REFUND;
        if (P_INTERCOMPANY.matcher(text).find()) return FlowKind.INTERCOMPANY;
        return FlowKind.OTHER;
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

    static Integer parseInvoiceNumber(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String truncate(String text) {
        if (text == null) return null;
        String trimmed = text.strip();
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(0, 255);
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

    /** Invoice numbers of the company's intercompany invoices — receipts on them are not customer cash. */
    @SuppressWarnings("unchecked")
    Set<Integer> queryInternalInvoiceNumbers(String companyuuid) {
        Query query = em.createNativeQuery(
                "SELECT invoicenumber FROM invoices WHERE companyuuid = :company " +
                        "AND type IN ('INTERNAL', 'INTERNAL_SERVICE') AND invoicenumber IS NOT NULL");
        query.setParameter("company", companyuuid);
        Set<Integer> result = new HashSet<>();
        for (Object row : query.getResultList()) {
            result.add(((Number) row).intValue());
        }
        return result;
    }

    /**
     * Replaces the company's monthly sums and ledger lines atomically — a failed
     * import leaves the previous night's data in place.
     */
    @Transactional
    void persistCompany(String companyuuid, Map<String, double[]> byMonth, List<BankLedgerEntry> entries) {
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
        BankLedgerEntry.delete("companyuuid", companyuuid);
        insertLedgerEntries(entries);
    }

    /** Chunked multi-row inserts — ~15k rows per company would be far too slow one statement at a time. */
    private void insertLedgerEntries(List<BankLedgerEntry> entries) {
        for (int from = 0; from < entries.size(); from += INSERT_CHUNK) {
            List<BankLedgerEntry> chunk = entries.subList(from, Math.min(entries.size(), from + INSERT_CHUNK));
            StringBuilder sql = new StringBuilder(
                    "INSERT INTO fact_bank_ledger_entry (id, companyuuid, ledger, entry_date, amount_dkk, entry_type, " +
                            "is_draft, kind, customer_invoice_number, remainder_dkk, entry_text, materialized_at) VALUES ");
            for (int i = 0; i < chunk.size(); i++) {
                if (i > 0) sql.append(',');
                sql.append("(?,?,?,?,?,?,?,?,?,?,?,?)");
            }
            Query query = em.createNativeQuery(sql.toString());
            int p = 1;
            for (BankLedgerEntry e : chunk) {
                query.setParameter(p++, e.getId());
                query.setParameter(p++, e.getCompanyuuid());
                query.setParameter(p++, e.getLedger());
                query.setParameter(p++, e.getEntryDate());
                query.setParameter(p++, e.getAmountDkk());
                query.setParameter(p++, e.getEntryType());
                query.setParameter(p++, e.isDraft() ? 1 : 0);
                query.setParameter(p++, e.getKind());
                query.setParameter(p++, e.getCustomerInvoiceNumber());
                query.setParameter(p++, e.getRemainderDkk());
                query.setParameter(p++, e.getEntryText());
                query.setParameter(p++, e.getMaterializedAt());
            }
            query.executeUpdate();
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

    /**
     * One imported ledger line, as consumed by the cash forecast. {@code amount}
     * is signed DKK: a bank movement for the BANK ledger, a receivable movement
     * (+ invoice, − payment) for the DEBTOR ledger. {@code remainder} is
     * e-conomic's open amount on a debtor line at import time (null elsewhere);
     * {@code text} is the bookkeeper's text (multi-invoice payments list their
     * invoice numbers there, VAT settlements name their period).
     */
    public record LedgerLine(
            String companyuuid,
            LocalDate date,
            FlowKind kind,
            double amount,
            Integer customerInvoiceNumber,
            Integer entryType,
            boolean draft,
            Double remainder,
            String text) {}

    /** Every line of one ledger ({@code BANK} or {@code DEBTOR}) across all companies, date-ordered. */
    public List<LedgerLine> ledgerLines(String ledger) {
        List<BankLedgerEntry> rows = BankLedgerEntry.list("ledger = ?1 ORDER BY entryDate, id", ledger);
        List<LedgerLine> result = new ArrayList<>(rows.size());
        for (BankLedgerEntry row : rows) {
            FlowKind kind;
            try {
                kind = FlowKind.valueOf(row.getKind());
            } catch (IllegalArgumentException e) {
                kind = FlowKind.OTHER;
            }
            result.add(new LedgerLine(row.getCompanyuuid(), row.getEntryDate(), kind, row.getAmountDkk(),
                    row.getCustomerInvoiceNumber(), row.getEntryType(), row.isDraft(),
                    row.getRemainderDkk(), row.getEntryText()));
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
        public Long entryNumber;
        public Integer accountNumber;
        public double amount;
        public double amountInBaseCurrency;
        public String currencyCode;
        public String date;
        public String text;
        public Integer type;
        public Integer voucherNumber;
        public Integer customerInvoiceNumber;
        public String supplierInvoiceNumber;
        public Double remainder;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class AccountBalance {
        public Double balance;
    }
}
