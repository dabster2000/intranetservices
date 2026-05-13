package dk.trustworks.intranet.aggregates.finance.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.finance.dto.DryRunOutcome;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.expenseservice.remote.EconomicsAPI;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
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

import jakarta.ws.rs.core.Response;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports revenue from e-conomic into {@code invoices} as
 * {@code type='PHANTOM'} rows with {@code economics_entry_number IS NOT NULL}.
 *
 * <p>This is the core of PR 2 of the external-invoice-import slice (see
 * {@code docs/superpowers/specs/2026-05-13-external-invoice-import-design.md}
 * and {@code .claude/tmp/external-invoice-import/pr2-locked-decisions.md}).
 * It pulls {@code manualDebtorInvoice} and selected {@code financeVoucher}
 * (account 2180) entries from e-conomic, aggregates them per voucher, runs
 * them through a 4-layer dedup contract, and inserts the survivors as
 * PHANTOM invoices with one synthesized {@link InvoiceItemOrigin#BASE}
 * invoiceitems row each (Option A — sumNoTax via hours×rate).
 *
 * <h2>4-layer dedup contract</h2>
 * <ol>
 *   <li>{@link DedupLayer#LAYER_1_ACCOUNT_HARDSKIP}: tenant-specific account
 *       deny-list (1040 for TECH/CYBER; 2101 for A/S; 2102/2103 VMS — Magnit
 *       broker bucket).</li>
 *   <li>{@link DedupLayer#LAYER_2_VOUCHER_COLLISION}: voucher number already
 *       present on a manually-created/booked Trustworks invoice (matches the
 *       union of {@code economics_voucher_number}, {@code economics_booked_number},
 *       {@code economics_draft_number} for the same company).</li>
 *   <li>{@link DedupLayer#LAYER_3_ENTRY_COLLISION}: a prior PHANTOM import
 *       already wrote this exact e-conomic entry number for the same company —
 *       the V338 unique index {@code uniq_invoices_economic_entry} is the
 *       race-final enforcer; this pre-check is belt-and-suspenders.</li>
 *   <li>{@link DedupLayer#LAYER_4_TEXT_MATCH}: e-conomic entry text contains
 *       "Faktura NNNNN-MMMMM" matching an existing Trustworks
 *       {@code invoicenumber} (historical pattern from pre-V338 manual links).</li>
 * </ol>
 * Layers fail-fast — if layer N hits, layers N+1..4 are not queried for that
 * voucher.
 *
 * <h2>Native SQL only</h2>
 * Inserts go through {@link EntityManager#createNativeQuery} so neither
 * {@code Invoice} nor {@code InvoiceItem} require new {@code @Column} fields
 * in PR 2. {@code economics_entry_number} and
 * {@code economics_accounting_year} (V338) and
 * {@code economics_entry_refreshed_at} (V340) are touched only as raw column
 * names. PR 2 intentionally bypasses Invoice's constructors (which would set
 * status=DRAFT and generate a new UUID); this is documented tech-debt — a
 * future refinement can add a static factory method on Invoice that wraps
 * the same fields safely.
 *
 * <h2>Per-voucher REQUIRES_NEW transaction</h2>
 * Each insert lives in its own transaction (see
 * {@link #insertInvoiceAndItem}). A single voucher failure (constraint
 * violation, broken row data, transient lock) does NOT roll back the whole
 * batch — the failure is counted, logged, and the loop continues.
 *
 * <h2>Sentinel write on empty refreshes</h2>
 * If a refresh completes with zero net inserts (everything was already
 * present), the oldest imported row's {@code economics_entry_refreshed_at}
 * is bumped to {@code NOW()}. This keeps
 * {@code EconomicRevenueImportFreshnessCheck} green: the freshness check
 * reads {@code MAX(economics_entry_refreshed_at) ... WHERE economics_entry_number IS NOT NULL}
 * and would otherwise stay stuck at the last actual-insert time and
 * eventually flip DOWN purely because no new vouchers exist.
 *
 * <h2>DRY_RUN</h2>
 * Default {@code economics.import.dry-run=true} in PR 2. The refresh still
 * walks every layer, aggregates vouchers, computes the
 * {@link DryRunOutcome#perCompanyDkk()} / {@link DryRunOutcome#perAccountDkk()}
 * sums and logs them. Only the per-voucher
 * {@link #insertInvoiceAndItem} call is skipped — verified in unit tests via
 * {@code verify(em, never())}. PR 3 flips the flag off co-deployed with V341.
 */
@ApplicationScoped
@JBossLog
public class EconomicRevenueImportService {

    /** {@link Company#TRUSTWORKS_UUID} — kept here as a Set for filter convenience. */
    static final Set<String> AS_COMPANY_UUIDS =
            Set.of("d8894494-2fb4-4f72-9e05-e6032e6dd691");

    static final Set<String> TECH_CYBER_COMPANY_UUIDS =
            Set.of("44592d3b-2be5-4b29-bfaf-4fafc60b0fa3",   // TECH
                   "e4b0a2a4-0963-4153-b0a2-a409637153a2");  // CYBER

    /** Magnit VMS broker accounts — already in
     *  invoices via the PHANTOM workaround pre-V339. Hard-skipped on import. */
    static final Set<Integer> VMS_DENY_LIST = Set.of(2102, 2103);

    /** A/S "booked invoice" — already represented on Trustworks INVOICE rows. */
    static final int AS_BOOKED_INVOICE_ACCOUNT = 2101;

    /** TECH/CYBER intercompany revenue — would double-count INTERNAL flow. */
    static final int TECH_CYBER_INTERCOMPANY_REVENUE_ACCOUNT = 1040;

    /** Layer 4 regex: matches free-text "Faktura 12345-67890" links e-conomic
     *  bookkeepers added manually to vouchers before V338. */
    static final Pattern FAKTURA_TEXT_PATTERN =
            Pattern.compile("Faktura\\s+(\\d+)-(\\d+)");

    /** All 3 companies the importer iterates over. Order is deterministic
     *  (insertion order in {@link java.util.LinkedHashMap}-style) for stable
     *  log output. */
    static final List<String> ALL_COMPANY_UUIDS = List.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691",   // A/S
            "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3",   // TECH
            "e4b0a2a4-0963-4153-b0a2-a409637153a2"    // CYBER
    );

    @ConfigProperty(name = "economics.import.dry-run", defaultValue = "true")
    boolean dryRun;

    @Inject
    EntityManager em;

    /**
     * Walks the 3 companies × revenue accounts × accounting years in
     * {@code [from..to]}, runs the 4-layer dedup, and inserts surviving
     * vouchers as PHANTOM invoices. Always invoked with a 24-month lookback
     * by the batchlet.
     *
     * <p>The wrapping {@code @Transactional} covers reads only — the per-voucher
     * INSERTs live in {@link #insertInvoiceAndItem}'s nested REQUIRES_NEW
     * transaction so single-voucher failure cannot roll back the batch.
     */
    @Transactional
    public DryRunOutcome refresh(LocalDate from, LocalDate to) {
        log.infof("EconomicRevenueImportService.refresh begin: from=%s to=%s dryRun=%s",
                from, to, dryRun);

        Map<String, BigDecimal> perCompanyDkk = new LinkedHashMap<>();
        Map<Integer, BigDecimal> perAccountDkk = new HashMap<>();
        EnumMap<DedupLayer, Integer> skippedByLayer = new EnumMap<>(DedupLayer.class);
        for (DedupLayer dl : DedupLayer.values()) skippedByLayer.put(dl, 0);

        int totalIntendedInserts = 0;
        int totalActualInserts = 0;
        LocalDateTime refreshedAt = LocalDateTime.now();

        for (String companyUuid : ALL_COMPANY_UUIDS) {
            Company company = Company.findById(companyUuid);
            if (company == null) {
                log.warnf("EconomicRevenueImportService: company %s not found — skipping", companyUuid);
                continue;
            }

            // Step 1: Load integration keys for this company.
            IntegrationKey.IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);

            // Step 2-4: open API + fetch revenue accounts + filter by tenant rules.
            List<AccountInfo> accounts;
            try (EconomicsAPI api = buildEconomicsApi(keys)) {
                accounts = fetchRevenueAccounts(api, companyUuid);
            } catch (Exception ex) {
                log.errorf(ex, "EconomicRevenueImportService: failed to list accounts for company %s", companyUuid);
                continue;
            }
            log.infof("EconomicRevenueImportService: company=%s accountsAfterFilter=%d", companyUuid, accounts.size());

            // Step 5-6: page entries per account × accounting year.
            List<EntryDto> entries;
            try (EconomicsAPI api = buildEconomicsApi(keys)) {
                entries = fetchEntries(api, accounts, from, to);
            } catch (Exception ex) {
                log.errorf(ex, "EconomicRevenueImportService: failed to fetch entries for company %s", companyUuid);
                continue;
            }
            log.infof("EconomicRevenueImportService: company=%s entriesFetched=%d", companyUuid, entries.size());

            // Step 7: keep only manualDebtorInvoice and financeVoucher(account=2180).
            List<EntryDto> filtered = entries.stream()
                    .filter(this::isImportableEntry)
                    .toList();
            log.infof("EconomicRevenueImportService: company=%s entriesAfterTypeFilter=%d", companyUuid, filtered.size());

            // Step 8: aggregate by voucher.
            List<AggregatedVoucher> aggregated = aggregateByVoucher(companyUuid, filtered);
            log.infof("EconomicRevenueImportService: company=%s aggregatedVouchers=%d", companyUuid, aggregated.size());

            // Step 9: per-voucher dedup + insert.
            for (AggregatedVoucher v : aggregated) {
                Optional<DedupLayer> hit = findExistingByVoucherColumns(v);
                if (hit.isPresent()) {
                    skippedByLayer.merge(hit.get(), 1, Integer::sum);
                    continue;
                }
                hit = findExistingByEntryNumber(v);
                if (hit.isPresent()) {
                    skippedByLayer.merge(hit.get(), 1, Integer::sum);
                    continue;
                }
                hit = findExistingByFakturaText(v);
                if (hit.isPresent()) {
                    skippedByLayer.merge(hit.get(), 1, Integer::sum);
                    continue;
                }

                // Survivor: record per-company / per-account sums and either insert or count intent.
                BigDecimal absAmount = v.sumAmount().abs();
                perCompanyDkk.merge(v.companyUuid(), absAmount, BigDecimal::add);
                perAccountDkk.merge(v.account(), absAmount, BigDecimal::add);
                totalIntendedInserts++;

                if (dryRun) {
                    log.infof("EconomicRevenueImportService DRY_RUN intended insert: company=%s acc=%d voucher=%d entry=%d amount=%s",
                            v.companyUuid(), v.account(), v.voucherNumber(), v.minEntryNumber(), absAmount);
                    continue;
                }

                try {
                    insertInvoiceAndItem(v, refreshedAt);
                    totalActualInserts++;
                } catch (SQLIntegrityConstraintViolationException race) {
                    // V338's uniq_invoices_economic_entry caught a concurrent insert.
                    // This is the race-final dedup — treat exactly like a Layer 3 hit.
                    skippedByLayer.merge(DedupLayer.LAYER_3_ENTRY_COLLISION, 1, Integer::sum);
                    log.warnf("EconomicRevenueImportService: V338 unique-index race on company=%s entry=%d — counted as LAYER_3",
                            v.companyUuid(), v.minEntryNumber());
                } catch (RuntimeException ex) {
                    // Unwrap Hibernate / JPA wrappers around the SQL exception.
                    Throwable root = ex;
                    while (root != null && !(root instanceof SQLIntegrityConstraintViolationException)) {
                        root = root.getCause();
                    }
                    if (root != null) {
                        skippedByLayer.merge(DedupLayer.LAYER_3_ENTRY_COLLISION, 1, Integer::sum);
                        log.warnf("EconomicRevenueImportService: V338 unique-index race (wrapped) on company=%s entry=%d — counted as LAYER_3",
                                v.companyUuid(), v.minEntryNumber());
                    } else {
                        log.errorf(ex, "EconomicRevenueImportService: insert failed for company=%s entry=%d (non-constraint) — counted as failure, batch continues",
                                v.companyUuid(), v.minEntryNumber());
                    }
                }
            }

            // Step 10: per-company log summary.
            log.infof("EconomicRevenueImportService: company=%s summary intended=%d actual=%d sumDkk=%s skipped=%s",
                    companyUuid, totalIntendedInserts, totalActualInserts,
                    perCompanyDkk.getOrDefault(companyUuid, BigDecimal.ZERO),
                    skippedByLayer);
        }

        // Sentinel write: if zero net inserts, bump the oldest imported row's
        // refresh timestamp so the freshness check doesn't flip DOWN purely
        // because no new vouchers exist this cycle. Idempotent — picking the
        // ASC-ordered first row means subsequent empty refreshes keep
        // ratcheting the same row's timestamp forward.
        if (totalActualInserts == 0 && !dryRun) {
            int updated = em.createNativeQuery(
                            "UPDATE invoices SET economics_entry_refreshed_at = :now " +
                                    "WHERE uuid = (" +
                                    "  SELECT uuid FROM (" +
                                    "    SELECT uuid FROM invoices " +
                                    "    WHERE economics_entry_number IS NOT NULL " +
                                    "    ORDER BY economics_entry_refreshed_at ASC LIMIT 1" +
                                    "  ) AS oldest)")
                    .setParameter("now", refreshedAt)
                    .executeUpdate();
            log.infof("EconomicRevenueImportService: sentinel write — zero net inserts, advanced %d row's refresh timestamp to %s",
                    updated, refreshedAt);
        }

        DryRunOutcome outcome = new DryRunOutcome(
                totalIntendedInserts,
                totalActualInserts,
                Collections.unmodifiableMap(perCompanyDkk),
                Collections.unmodifiableMap(perAccountDkk),
                Collections.unmodifiableMap(skippedByLayer),
                dryRun);
        log.infof("EconomicRevenueImportService.refresh complete: %s", outcome);
        return outcome;
    }

    // ------------------------------------------------------------------------
    // e-conomic API helpers
    // ------------------------------------------------------------------------

    /**
     * Builds a per-tenant {@link EconomicsAPI} client. Mirrors
     * {@code EconomicsInvoiceService.getEconomicsAPI} verbatim — same auth
     * header filter, same base URI source. Never logs the
     * {@link IntegrationKey.IntegrationKeyValue} (it holds the secret + grant
     * tokens — leaking either would break the agreement).
     */
    EconomicsAPI buildEconomicsApi(IntegrationKey.IntegrationKeyValue keys) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(keys.url()))
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsAPI.class);
    }

    /**
     * Lists revenue accounts for the tenant and filters out the deny-list
     * (Layer 1). Returns {@code (accountNumber, accountName)} tuples — the
     * name is used as a fallback {@code clientname} when an entry lacks a
     * customer.
     */
    List<AccountInfo> fetchRevenueAccounts(EconomicsAPI api, String companyUuid) {
        List<AccountInfo> result = new ArrayList<>();
        // e-conomic does NOT have a "revenue" accountType — the valid values are
        // profitAndLoss, status, totalFrom, heading, headingStart, sumInterval,
        // sumAlpha. We fetch all profitAndLoss accounts; the downstream entry-type
        // filter (manualDebtorInvoice + financeVoucher@2180) and the Layer 1
        // account hard-skip do the actual revenue-vs-expense narrowing. The
        // Account JSON field for the human-readable label is "name", not
        // "accountName" — verified against live e-conomic 2026-05-13.
        try (Response r = api.getAccounts("accountType$eq:profitAndLoss", 1000)) {
            String body = r.readEntity(String.class);
            JsonNode root = readTree(body);
            for (JsonNode node : iterateCollection(root)) {
                int accountNumber = node.path("accountNumber").asInt();
                String accountName = node.path("name").asText("");
                if (shouldSkipAccount(companyUuid, accountNumber)) continue;
                result.add(new AccountInfo(accountNumber, accountName));
            }
        }
        return result;
    }

    /**
     * Iterates {@code [from.year .. to.year]} and pages e-conomic
     * {@code /accounting-years/{year}/entries} for each account. Uses the
     * date-range filter so e-conomic does the heavy lifting; the caller-side
     * type filter ({@link #isImportableEntry}) and dedup happen after.
     */
    List<EntryDto> fetchEntries(EconomicsAPI api, List<AccountInfo> accounts,
                                LocalDate from, LocalDate to) {
        List<EntryDto> entries = new ArrayList<>();
        for (AccountInfo acc : accounts) {
            for (int year = from.getYear(); year <= to.getYear(); year++) {
                // e-conomic fiscal year format example: "2024/2025" — encoded as "2024_6_2025" in URLs.
                String yearStr = year + "_6_" + (year + 1);
                String filter = "account.accountNumber$eq:" + acc.accountNumber()
                        + "$and$:date$gte$:" + from
                        + "$and$:date$lte$:" + to;
                try (Response r = api.getYearEntries(yearStr, filter, 1000)) {
                    String body = r.readEntity(String.class);
                    JsonNode root = readTree(body);
                    for (JsonNode node : iterateCollection(root)) {
                        EntryDto e = parseEntry(node, acc);
                        if (e != null) entries.add(e);
                    }
                } catch (Exception ex) {
                    log.warnf(ex, "EconomicRevenueImportService: fetchEntries error for account=%d year=%s — skipping",
                            acc.accountNumber(), yearStr);
                }
            }
        }
        return entries;
    }

    /**
     * Layer 1: account hard-skip per tenant rules.
     */
    boolean shouldSkipAccount(String companyUuid, int accountNumber) {
        if (VMS_DENY_LIST.contains(accountNumber)) return true;
        if (AS_COMPANY_UUIDS.contains(companyUuid)
                && accountNumber == AS_BOOKED_INVOICE_ACCOUNT) return true;
        return TECH_CYBER_COMPANY_UUIDS.contains(companyUuid)
                && accountNumber == TECH_CYBER_INTERCOMPANY_REVENUE_ACCOUNT;
    }

    /**
     * Step 7: keep only {@code manualDebtorInvoice} (the canonical bookkeeper
     * pattern for ad-hoc revenue) and {@code financeVoucher} entries on
     * account 2180 (Trustworks' historical "manual revenue" account where
     * the bookkeeper sometimes used a finance voucher instead of a debtor
     * invoice).
     */
    boolean isImportableEntry(EntryDto e) {
        if ("manualDebtorInvoice".equals(e.entryType())) return true;
        return "financeVoucher".equals(e.entryType()) && e.account() == 2180;
    }

    // ------------------------------------------------------------------------
    // Voucher aggregation
    // ------------------------------------------------------------------------

    /**
     * Step 8: collapse per-entry rows into per-voucher rows so reversals net
     * out before dedup. Aggregation key is
     * {@code (companyUuid, accountingYear, account, voucherNumber)}.
     *
     * <ul>
     *   <li>{@code sumAmount} = SUM(amountInBaseCurrency)
     *       — negative if reversed.</li>
     *   <li>{@code representativeText} = first entry's text — used for
     *       Layer 4 regex.</li>
     *   <li>{@code minEntryNumber} = MIN(entryNumber) — chosen because
     *       e-conomic's reversal-pair pattern (e.g., 4001 + reversal 4002)
     *       leaves the lower number as the original.</li>
     *   <li>{@code clientname} = first entry's customer.name OR the account
     *       name fallback when no customer is attached.</li>
     * </ul>
     */
    List<AggregatedVoucher> aggregateByVoucher(String companyUuid, List<EntryDto> entries) {
        Map<String, AggregatedVoucher> by = new LinkedHashMap<>();
        for (EntryDto e : entries) {
            String key = e.accountingYear() + "|" + e.account() + "|" + e.voucherNumber();
            AggregatedVoucher prev = by.get(key);
            if (prev == null) {
                by.put(key, new AggregatedVoucher(
                        companyUuid,
                        e.accountingYear(),
                        e.account(),
                        e.voucherNumber(),
                        e.amount(),
                        e.text(),
                        e.entryNumber(),
                        e.customerName() != null && !e.customerName().isBlank()
                                ? e.customerName()
                                : e.accountNameFallback(),
                        e.entryDate()));
            } else {
                by.put(key, new AggregatedVoucher(
                        prev.companyUuid(),
                        prev.accountingYear(),
                        prev.account(),
                        prev.voucherNumber(),
                        prev.sumAmount().add(e.amount()),
                        prev.representativeText(),       // first entry's text wins
                        Math.min(prev.minEntryNumber(), e.entryNumber()),
                        prev.clientname(),
                        prev.entryDate()));
            }
        }
        return new ArrayList<>(by.values());
    }

    // ------------------------------------------------------------------------
    // 4-layer dedup
    // ------------------------------------------------------------------------

    /**
     * Layer 2: voucher-column collision against any
     * {@code economics_voucher_number}, {@code economics_booked_number}, or
     * {@code economics_draft_number} on a Trustworks-created invoice for
     * the same company.
     */
    Optional<DedupLayer> findExistingByVoucherColumns(AggregatedVoucher v) {
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                        "SELECT 1 FROM invoices " +
                                "WHERE companyuuid = :company AND (" +
                                "  economics_voucher_number = :voucherNum " +
                                "  OR economics_booked_number = :voucherNum " +
                                "  OR economics_draft_number = :voucherNum) " +
                                "LIMIT 1")
                .setParameter("company", v.companyUuid())
                .setParameter("voucherNum", v.voucherNumber())
                .getResultList();
        return rs.isEmpty() ? Optional.empty() : Optional.of(DedupLayer.LAYER_2_VOUCHER_COLLISION);
    }

    /**
     * Layer 3 pre-check: same e-conomic entry already imported for this
     * company. The V338 unique index catches races; this query saves the
     * round-trip when the conflict is already committed.
     */
    Optional<DedupLayer> findExistingByEntryNumber(AggregatedVoucher v) {
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                        "SELECT 1 FROM invoices " +
                                "WHERE companyuuid = :company AND economics_entry_number = :entryNum " +
                                "LIMIT 1")
                .setParameter("company", v.companyUuid())
                .setParameter("entryNum", v.minEntryNumber())
                .getResultList();
        return rs.isEmpty() ? Optional.empty() : Optional.of(DedupLayer.LAYER_3_ENTRY_COLLISION);
    }

    /**
     * Layer 4: free-text "Faktura NNNNN-MMMMM" match against an existing
     * {@code invoicenumber} on a manually-created Trustworks invoice.
     * Pre-V338 bookkeepers used this convention to cross-reference the
     * Trustworks invoice from the e-conomic side; if we find the link we
     * know the import is a duplicate.
     *
     * <p>Defensive against {@link NumberFormatException}: the captured
     * groups can produce a string longer than {@code int}'s range. On
     * overflow we skip layer 4 and fall through to the insert path —
     * which is safe because layers 2/3 still gate against true
     * duplicates.
     */
    Optional<DedupLayer> findExistingByFakturaText(AggregatedVoucher v) {
        if (v.representativeText() == null) return Optional.empty();
        Matcher m = FAKTURA_TEXT_PATTERN.matcher(v.representativeText());
        if (!m.find()) return Optional.empty();
        int invoiceNum;
        try {
            invoiceNum = Integer.parseInt(m.group(1) + m.group(2));
        } catch (NumberFormatException ex) {
            log.warnf("EconomicRevenueImportService: Layer 4 parseInt overflow on text=%s — skipping layer",
                    v.representativeText());
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        List<Object> rs = em.createNativeQuery(
                        "SELECT 1 FROM invoices " +
                                "WHERE companyuuid = :company AND invoicenumber = :invoiceNum " +
                                "LIMIT 1")
                .setParameter("company", v.companyUuid())
                .setParameter("invoiceNum", invoiceNum)
                .getResultList();
        return rs.isEmpty() ? Optional.empty() : Optional.of(DedupLayer.LAYER_4_TEXT_MATCH);
    }

    // ------------------------------------------------------------------------
    // Insert path
    // ------------------------------------------------------------------------

    /**
     * Inserts one {@code invoices} row (type=PHANTOM, status=CREATED,
     * invoicenumber=0) and one synthesized {@code invoiceitems} row
     * (hours=1, rate=ABS(amount), origin=BASE) so
     * {@code Invoice.getSumNoTax()} returns ABS(amount) — Option A of
     * pr2-locked-decisions §"Decision 2".
     *
     * <p>{@link jakarta.transaction.Transactional.TxType#REQUIRES_NEW}: each
     * insert is its own transaction so a single failing voucher does not
     * roll back the whole batch. If V338's unique index fires
     * mid-flight, the wrapped {@link SQLIntegrityConstraintViolationException}
     * propagates to the caller which counts it as
     * {@link DedupLayer#LAYER_3_ENTRY_COLLISION}.
     *
     * <p>Package-private so the unit test can wire a Mockito spy around it
     * and capture the bind parameters.
     */
    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void insertInvoiceAndItem(AggregatedVoucher v, LocalDateTime refreshedAt)
            throws SQLIntegrityConstraintViolationException {
        String invoiceUuid = UUID.randomUUID().toString();
        String itemUuid = UUID.randomUUID().toString();
        LocalDate invoiceDate = v.entryDate() != null ? v.entryDate() : LocalDate.now();
        LocalDate dueDate = invoiceDate;          // no payment terms on auto-imports
        BigDecimal rate = v.sumAmount().abs().setScale(2, RoundingMode.HALF_UP);
        String description = "e-conomic entry " + v.minEntryNumber();

        try {
            Query q = em.createNativeQuery(
                    "INSERT INTO invoices (" +
                            "  uuid, type, status, invoicenumber, year, month, companyuuid, " +
                            "  clientname, currency, invoicedate, duedate, " +
                            "  economics_voucher_number, economics_entry_number, economics_accounting_year, " +
                            "  economics_entry_refreshed_at, " +
                            "  invoice_ref, vat, discount, " +
                            "  internal_invoice_skip" +
                            ") VALUES (" +
                            "  :uuid, 'PHANTOM', 'CREATED', 0, :year, :month, :companyUuid, " +
                            "  :clientname, 'DKK', :invoiceDate, :dueDate, " +
                            "  :voucherNumber, :entryNumber, :accountingYear, " +
                            "  :refreshedAt, " +
                            "  0, 0.0, 0.0, " +
                            "  false" +
                            ")");
            q.setParameter("uuid", invoiceUuid);
            q.setParameter("year", invoiceDate.getYear());
            q.setParameter("month", invoiceDate.getMonthValue());
            q.setParameter("companyUuid", v.companyUuid());
            q.setParameter("clientname", v.clientname() != null ? v.clientname() : "");
            q.setParameter("invoiceDate", invoiceDate);
            q.setParameter("dueDate", dueDate);
            q.setParameter("voucherNumber", v.voucherNumber());
            q.setParameter("entryNumber", v.minEntryNumber());
            q.setParameter("accountingYear", v.accountingYear());
            q.setParameter("refreshedAt", refreshedAt);
            q.executeUpdate();

            Query q2 = em.createNativeQuery(
                    "INSERT INTO invoiceitems (" +
                            "  uuid, invoiceuuid, itemname, description, rate, hours, position, " +
                            "  origin, consultantuuid" +
                            ") VALUES (" +
                            "  :itemUuid, :invoiceUuid, 'e-conomic auto-import', " +
                            "  :description, :rate, 1.0, 0, " +
                            "  'BASE', NULL" +
                            ")");
            q2.setParameter("itemUuid", itemUuid);
            q2.setParameter("invoiceUuid", invoiceUuid);
            q2.setParameter("description", description);
            q2.setParameter("rate", rate);
            q2.executeUpdate();

            log.infof("EconomicRevenueImportService: inserted PHANTOM invoice=%s + item=%s for company=%s entry=%d amount=%s",
                    invoiceUuid, itemUuid, v.companyUuid(), v.minEntryNumber(), rate);
        } catch (RuntimeException re) {
            // Unwrap Hibernate / JPA wrapper to surface the original constraint violation.
            Throwable t = re;
            while (t != null) {
                if (t instanceof SQLIntegrityConstraintViolationException sce) {
                    throw sce;
                }
                t = t.getCause();
            }
            throw re;
        }
    }

    // ------------------------------------------------------------------------
    // JSON helpers — used by tests for parity
    // ------------------------------------------------------------------------

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode readTree(String body) {
        if (body == null || body.isEmpty()) return MAPPER.createObjectNode();
        try {
            return MAPPER.readTree(body);
        } catch (Exception ex) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * e-conomic responses sometimes wrap the array under {@code collection},
     * sometimes under {@code items}, sometimes return a bare array. Handle
     * all three so the importer doesn't break on API surface drift.
     */
    private static Iterable<JsonNode> iterateCollection(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) return Collections.emptyList();
        if (root.isArray()) return () -> root.elements();
        if (root.has("collection") && root.get("collection").isArray()) {
            return () -> root.get("collection").elements();
        }
        if (root.has("items") && root.get("items").isArray()) {
            return () -> root.get("items").elements();
        }
        return Collections.emptyList();
    }

    private EntryDto parseEntry(JsonNode node, AccountInfo acc) {
        try {
            int entryNumber = node.path("entryNumber").asInt();
            int voucherNumber = node.path("voucherNumber").asInt();
            String entryType = node.path("entryType").asText("");
            String accountingYear = node.path("accountingYear").path("year").asText("");
            BigDecimal amount = node.has("amountInBaseCurrency")
                    ? new BigDecimal(node.get("amountInBaseCurrency").asText("0"))
                    : new BigDecimal(node.path("amount").asText("0"));
            String text = node.path("text").asText("");
            String customerName = node.path("customer").path("name").asText(null);
            // entry.account.accountNumber takes precedence over the discovery account
            int accountNumber = node.path("account").path("accountNumber").asInt(acc.accountNumber());
            LocalDate date = parseDate(node.path("date").asText(null));
            return new EntryDto(entryNumber, voucherNumber, entryType, accountingYear,
                    amount, text, accountNumber, customerName, acc.accountName(), date);
        } catch (Exception ex) {
            log.warnf("EconomicRevenueImportService: failed to parse entry node — skipping: %s", node);
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.substring(0, Math.min(10, s.length())));
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------
    // Inner types
    // ------------------------------------------------------------------------

    /** Dedup-layer enum — order matches fail-fast traversal in {@link #refresh}. */
    public enum DedupLayer {
        LAYER_1_ACCOUNT_HARDSKIP,
        LAYER_2_VOUCHER_COLLISION,
        LAYER_3_ENTRY_COLLISION,
        LAYER_4_TEXT_MATCH
    }

    /** Per-account row from {@code GET /accounts}. */
    record AccountInfo(int accountNumber, String accountName) {}

    /** Per-entry row from {@code GET /accounting-years/{year}/entries}. */
    record EntryDto(
            int entryNumber,
            int voucherNumber,
            String entryType,
            String accountingYear,
            BigDecimal amount,
            String text,
            int account,
            String customerName,
            String accountNameFallback,
            LocalDate entryDate) {}

    /** Aggregated voucher — one inserted invoice maps 1:1 to one of these. */
    public record AggregatedVoucher(
            String companyUuid,
            String accountingYear,
            int account,
            int voucherNumber,
            BigDecimal sumAmount,
            String representativeText,
            int minEntryNumber,
            String clientname,
            LocalDate entryDate) {}
}
