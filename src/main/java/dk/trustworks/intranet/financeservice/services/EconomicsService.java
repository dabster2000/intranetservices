package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.remote.EconomicsJournalsAPI;
import dk.trustworks.intranet.expenseservice.remote.JournalEntryResponse;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import dk.trustworks.intranet.financeservice.remote.EconomicsJournalEntriesAPI;
import dk.trustworks.intranet.financeservice.remote.EconomicsPagingAPI;
import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
import dk.trustworks.intranet.financeservice.remote.dto.economics.JournalEntriesResponse;
import dk.trustworks.intranet.model.Company;
import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;
import static dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup.*;

@JBossLog
@ApplicationScoped
public class EconomicsService {

    @ConfigProperty(name = "quarkus.rest-client.economics-journals-api.url", defaultValue = "https://apis.e-conomic.com/journalsapi/v13.0.1")
    URI journalsApiUri;

    /**
     * Intercompany debtor cost accounts. Unbooked intercompany supplier-invoice drafts in the
     * "Kreditor Intern" daybook post their cost leg to one of these (via contraAccount); they
     * are the scope of the draft-supplier-invoice sync. Same set the EBITDA chart isolates as
     * the internal-cost slice of DIRECT_COSTS.
     */
    private static final Set<Integer> INTERCOMPANY_COST_ACCOUNTS = Set.of(3050, 3055, 3070, 3075, 1350);

    public record FinanceEntry(LocalDate period, int accountNumber, double amountInBaseCurrency, PostingStatus postingStatus) {}

    @SneakyThrows
    public Map<PostingStatus, Map<Range<Integer>, List<FinanceEntry>>> getAllEntries(Company company, String date) {
        Map<PostingStatus, Map<Range<Integer>, List<FinanceEntry>>> collectionResultMap = new EnumMap<>(PostingStatus.class);
        collectionResultMap.put(PostingStatus.BOOKED, emptyEntryMap());
        collectionResultMap.put(PostingStatus.DRAFT, emptyEntryMap());

        IntegrationKey.IntegrationKeyValue result = getIntegrationKeyValue(company);

        List<FinanceDetails> financeDetails = new ArrayList<>();

        EconomicsInvoice economicsInvoice = getFirstPage(date, URI.create(result.url()), result.appSecretToken(), result.agreementGrantToken());
        String url = "";
        do {
            try {
                assert economicsInvoice != null;
                for (Collection collection : economicsInvoice.getCollection()) {
                    int accountNumber = collection.getAccount().getAccountNumber();
                    LocalDate period = parseEconomicsDate(collection.getDate()).withDayOfMonth(1);
                    addToEntryMap(collectionResultMap.get(PostingStatus.BOOKED), new FinanceEntry(
                            period, accountNumber, collection.getAmountInBaseCurrency(), PostingStatus.BOOKED));
                    financeDetails.add(new FinanceDetails(
                            company,
                            collection.getEntryNumber(),
                            accountNumber,
                            collection.getInvoiceNumber(),
                            collection.getAmountInBaseCurrency(),
                            collection.getRemainderInBaseCurrency(),
                            period,
                            collection.getText(),
                            PostingStatus.BOOKED,
                            0,
                            collection.getVoucherNumber(),
                            null,
                            null,
                            collection.getCurrency(),
                            null));
                }
                url = economicsInvoice.getPagination().getNextPage();
                if(url!=null) {
                    economicsInvoice = getNextPage(URI.create(url), result.appSecretToken(), result.agreementGrantToken());
                }
            } catch (Exception e) {
                log.errorf(e, "Booked e-conomic entries could not be loaded for company %s period %s nextPage=%s",
                        company.getUuid(), date, economicsInvoice != null && economicsInvoice.pagination != null
                                ? economicsInvoice.pagination.getNextPage()
                                : url);
                throw new RuntimeException(e);
            }
        } while (url != null);

        try {
            loadDraftEntries(company, date, result, collectionResultMap.get(PostingStatus.DRAFT), financeDetails);
        } catch (Exception e) {
            log.warnf(e, "Draft e-conomic entries could not be loaded for company %s period %s; continuing with booked entries", company.getUuid(), date);
        }

        try {
            loadDraftSupplierInvoices(company, date, result, collectionResultMap.get(PostingStatus.DRAFT), financeDetails);
        } catch (Exception e) {
            log.warnf(e, "Draft intercompany supplier invoices could not be loaded for company %s period %s; continuing", company.getUuid(), date);
        }

        // In-batch dedup before persist. The e-conomic pagination API has been
        // observed to return duplicate entries across page boundaries (root
        // cause of the 2026-04-24 cascade: ConstraintViolationException on
        // V303's uq_fd_logical_key poisoned the Hibernate session, then all
        // three tenants failed with AssertionFailure / null identifier and
        // their data was missing until the next 21:00 UTC run recovered).
        // Deduping by the source-aware logical key keeps the persist idempotent
        // against pagination quirks. "Last write wins" matches the INSERT
        // IGNORE semantics the unique key enforces at the DB.
        Map<String, FinanceDetails> deduped = new LinkedHashMap<>();
        for (FinanceDetails fd : financeDetails) {
            String logicalKey = fd.getCompany().getUuid()
                    + "|" + fd.getPostingstatus()
                    + "|" + fd.getJournalnumber()
                    + "|" + fd.getVouchernumber()
                    + "|" + fd.getEntrynumber()
                    + "|" + fd.getAccountnumber()
                    + "|" + fd.getAmount()
                    + "|" + fd.getExpensedate();
            deduped.put(logicalKey, fd);
        }
        if (deduped.size() != financeDetails.size()) {
            log.warnf("In-batch dedup: %d → %d FinanceDetails for company %s period %s (%d duplicate(s) from e-conomic pagination)",
                    financeDetails.size(), deduped.size(), company.getUuid(), date,
                    financeDetails.size() - deduped.size());
        }

        // Persist this batch in its OWN new transaction so a uq_fd_logical_key
        // collision (1062) rolls back ONLY this batch and Quarkus tears down its
        // transaction-scoped persistence context. The previous manual
        // tm.begin()/tm.commit() left the failed FinanceDetails (null identifier) in
        // the long-lived request session, so one collision AssertionFailure-cascaded
        // (HHH000099) across every remaining tenant (2026-04-24, 2026-06-16). The
        // collision propagates to the caller, which skips the duplicate tenant.
        final List<FinanceDetails> entriesToPersist = new ArrayList<>(deduped.values());
        QuarkusTransaction.requiringNew().run(() -> FinanceDetails.persist(entriesToPersist));
        return collectionResultMap;
    }

    @Transactional
    public void persistExpenses(Map<PostingStatus, Map<Range<Integer>, List<FinanceEntry>>> allEntries) {
        for (Map.Entry<PostingStatus, Map<Range<Integer>, List<FinanceEntry>>> statusEntry : allEntries.entrySet()) {
            PostingStatus postingStatus = statusEntry.getKey();
            Map<Range<Integer>, List<FinanceEntry>> entries = statusEntry.getValue();
            Finance.persist(getExpenseMap(ExcelFinanceType.LØNNINGER, entries.get(LOENNINGER_ACCOUNTS.getRange()), postingStatus).values());
            Finance.persist(getExpenseMap(ExcelFinanceType.ADMINISTRATION, entries.get(ADMINISTRATION_ACCOUNTS.getRange()), postingStatus).values());
            Finance.persist(getExpenseMap(ExcelFinanceType.LOKALE, entries.get(LOKALE_ACCOUNTS.getRange()), postingStatus).values());
            Finance.persist(getExpenseMap(ExcelFinanceType.PRODUKTION, entries.get(PRODUKTION_ACCOUNTS.getRange()), postingStatus).values());
            Finance.persist(getExpenseMap(ExcelFinanceType.SALG, entries.get(SALG_ACCOUNTS.getRange()), postingStatus).values());
            Finance.persist(getExpenseMap(ExcelFinanceType.PERSONALE, entries.get(PERSONALE_ACCOUNTS.getRange()), postingStatus).values());
        }
    }

    private Map<LocalDate, Finance> getExpenseMap(ExcelFinanceType excelType, List<FinanceEntry> collectionList, PostingStatus postingStatus) {
        Map<LocalDate, Finance> map = new HashMap<>();
        if (collectionList == null) return map;
        collectionList.forEach(entry -> {
            LocalDate period = entry.period().withDayOfMonth(1);
            if(!map.containsKey(period)) map.put(period, new Finance(period, excelType, 0.0, postingStatus));
            map.get(period).setAmount(map.get(period).getAmount() + entry.amountInBaseCurrency());
        });
        return map;
    }

    private Map<Range<Integer>, List<FinanceEntry>> emptyEntryMap() {
        Map<Range<Integer>, List<FinanceEntry>> result = new HashMap<>();
        result.put(OMSAETNING_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(PRODUKTION_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(LOENNINGER_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(PERSONALE_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(VARIABEL_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(LOKALE_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(SALG_ACCOUNTS.getRange(), new ArrayList<>());
        result.put(ADMINISTRATION_ACCOUNTS.getRange(), new ArrayList<>());
        return result;
    }

    private void addToEntryMap(Map<Range<Integer>, List<FinanceEntry>> entryMap, FinanceEntry entry) {
        entryMap.keySet().forEach(integerRange -> {
            if(integerRange.contains(entry.accountNumber())) entryMap.get(integerRange).add(entry);
        });
    }

    private void loadDraftEntries(
            Company company,
            String date,
            IntegrationKey.IntegrationKeyValue integrationKey,
            Map<Range<Integer>, List<FinanceEntry>> draftEntryMap,
            List<FinanceDetails> financeDetails) throws Exception {

        LocalDate fiscalStart = fiscalStartFromEconomicsPeriod(date);
        LocalDate fiscalEndExclusive = fiscalStart.plusYears(1);
        Set<Integer> mappedAccountNumbers = mappedAccountNumbers(company);
        String filter = "date$gte:" + fiscalStart + "$and:date$lt:" + fiscalEndExclusive;

        try (EconomicsJournalsAPI remoteApi = RestClientBuilder.newBuilder()
                .baseUri(journalsApiUri)
                .register(new EconomicsDynamicHeaderFilter(integrationKey.appSecretToken(), integrationKey.agreementGrantToken()))
                .build(EconomicsJournalsAPI.class)) {
            String cursor = null;
            do {
                JournalEntryResponse response = remoteApi.getDraftEntries(filter, cursor, 1000);
                List<JournalEntryResponse.Entry> entries = response != null && response.entries() != null
                        ? response.entries()
                        : List.of();
                for (JournalEntryResponse.Entry entry : entries) {
                    int accountNumber = entry.resolvedAccountNumber();
                    if (accountNumber == 0 || !mappedAccountNumbers.contains(accountNumber)) continue;

                    LocalDate period = parseEconomicsDate(entry.date).withDayOfMonth(1);
                    double baseAmount = draftAmountInBaseCurrency(entry);
                    addToEntryMap(draftEntryMap, new FinanceEntry(period, accountNumber, baseAmount, PostingStatus.DRAFT));
                    financeDetails.add(new FinanceDetails(
                            company,
                            entry.entryNumber,
                            accountNumber,
                            0,
                            baseAmount,
                            0.0,
                            period,
                            entry.text,
                            PostingStatus.DRAFT,
                            entry.journalNumber,
                            entry.voucherNumber,
                            entry.objectVersion,
                            entry.entryTypeNumber,
                            entry.currency,
                            entry.exchangeRate));
                }
                cursor = response != null ? response.cursor : null;
            } while (cursor != null && !cursor.isBlank());
        }
    }

    /**
     * Sync UNBOOKED intercompany supplier-invoice drafts into {@code finance_details} as
     * {@code DRAFT} rows, so the Booked+Draft cost source sees intercompany cost that exists in
     * e-conomic but is not yet booked. Source: the company's "Kreditor Intern" daybook
     * ({@code internal-journal-number}) via the classic REST {@code /journals/{n}/entries}
     * endpoint, filtered to {@code entryType='supplierInvoice'} on an intercompany cost account.
     *
     * <p><strong>Net cost, GL sign.</strong> e-conomic's supplier-invoice {@code amount} is gross
     * (VAT via {@code contraVatAccount}) and signed in creditor convention (a normal invoice is
     * negative, a credit note positive). We negate and strip VAT
     * ({@code net = -gross / (1 + vatRate)}) to match the booked 3050/3055 GL rows — verified to
     * the øre against the issuer invoice net (invoice 70368: gross −165,687.50 / 1.25 = −132,550
     * → stored +132,550).
     *
     * <p><strong>Supersede-on-booking is automatic.</strong> Draft entries
     * ({@code /journals/{n}/entries}) and booked entries ({@code /accounting-years/.../entries},
     * loaded above) are DISJOINT sources — a booked invoice leaves the draft journal. The nightly
     * {@code clean()} + reload therefore makes the booked row replace the draft with no id-matching.
     * Entries are scoped to the fiscal year of {@code date} so the per-year
     * {@link dk.trustworks.intranet.financeservice.jobs.FinanceLoadJob} loop never double-loads the
     * same draft across years.
     */
    private void loadDraftSupplierInvoices(
            Company company,
            String date,
            IntegrationKey.IntegrationKeyValue integrationKey,
            Map<Range<Integer>, List<FinanceEntry>> draftEntryMap,
            List<FinanceDetails> financeDetails) throws Exception {

        int journalNumber = integrationKey.internalJournalNumber();
        if (journalNumber <= 0) return;

        LocalDate fiscalStart = fiscalStartFromEconomicsPeriod(date);
        LocalDate fiscalEndExclusive = fiscalStart.plusYears(1);
        ObjectMapper objectMapper = new ObjectMapper();

        try (EconomicsJournalEntriesAPI remoteApi = RestClientBuilder.newBuilder()
                .baseUri(URI.create(integrationKey.url()))
                .register(new EconomicsDynamicHeaderFilter(integrationKey.appSecretToken(), integrationKey.agreementGrantToken()))
                .build(EconomicsJournalEntriesAPI.class)) {

            int skipPages = 0;
            boolean morePages = true;
            int fetchedTotal = 0;
            int addedTotal = 0;
            int lastStatus = -1;
            String firstBodySample = "";
            while (morePages) {
                jakarta.ws.rs.core.Response httpResp = remoteApi.getJournalEntries(journalNumber, skipPages, 1000);
                lastStatus = httpResp.getStatus();
                String json = httpResp.readEntity(String.class);
                if (skipPages == 0) {
                    firstBodySample = (json == null) ? "null" : json.substring(0, Math.min(300, json.length()));
                }
                JournalEntriesResponse response = objectMapper.readValue(json, JournalEntriesResponse.class);
                List<JournalEntriesResponse.Entry> entries = response != null && response.collection != null
                        ? response.collection
                        : List.of();
                fetchedTotal += entries.size();

                for (JournalEntriesResponse.Entry entry : entries) {
                    if (!"supplierInvoice".equalsIgnoreCase(entry.entryType)) continue;
                    int accountNumber = entry.contraAccountNumber();
                    if (!INTERCOMPANY_COST_ACCOUNTS.contains(accountNumber)) continue;

                    LocalDate entryDate = parseEconomicsDate(entry.date);
                    if (entryDate.isBefore(fiscalStart) || !entryDate.isBefore(fiscalEndExclusive)) continue;

                    double net = netCostFromDraftGross(entry.grossBaseAmount(), entry.vatRatePercentage());
                    LocalDate period = entryDate.withDayOfMonth(1);
                    int invoiceNumber = parseSupplierInvoiceNumber(entry.supplierInvoiceNumber);
                    String currency = entry.currency != null ? entry.currency.code : null;

                    addToEntryMap(draftEntryMap, new FinanceEntry(period, accountNumber, net, PostingStatus.DRAFT));
                    financeDetails.add(new FinanceDetails(
                            company,
                            entry.journalEntryNumber != null ? entry.journalEntryNumber : 0,
                            accountNumber,
                            invoiceNumber,
                            net,
                            0.0,
                            period,
                            entry.text,
                            PostingStatus.DRAFT,
                            journalNumber,
                            entry.voucherNumber(),
                            null,
                            null,
                            currency,
                            entry.exchangeRate));
                    addedTotal++;
                }

                String nextPage = response != null && response.pagination != null ? response.pagination.nextPage : null;
                morePages = nextPage != null && !nextPage.isBlank() && !entries.isEmpty();
                skipPages++;
            }
            log.infof("D3 draft supplier-invoice sync: company=%s journal=%d period=%s httpStatus=%d fetched=%d added=%d",
                    company.getUuid(), journalNumber, date, lastStatus, fetchedTotal, addedTotal);
            if (fetchedTotal == 0) {
                log.warnf("D3 fetched 0 journal entries (company=%s journal=%d httpStatus=%d) body[:300]=%s",
                        company.getUuid(), journalNumber, lastStatus, firstBodySample);
            }
        }
    }

    /**
     * Net GL cost (debit-positive) for an intercompany supplier-invoice draft. e-conomic's draft
     * {@code amount} is gross (incl. VAT) and signed in creditor convention (a normal invoice is
     * negative, a credit note positive); negate it and strip VAT to match the booked 3050/3055 GL
     * rows. Verified to the øre against the issuer invoice net (invoice 70368: gross −165,687.50,
     * VAT 25% → +132,550.00; credit note 70363: gross +334,753.58 → −267,802.86).
     */
    static double netCostFromDraftGross(double grossSigned, double vatRatePercentage) {
        return -grossSigned / (1.0 + vatRatePercentage / 100.0);
    }

    /** Parse an e-conomic supplierInvoiceNumber (e.g. "70-368") to the issuer invoice number 70368. */
    static int parseSupplierInvoiceNumber(String raw) {
        if (raw == null) return 0;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Set<Integer> mappedAccountNumbers(Company company) {
        Set<Integer> result = new HashSet<>();
        List<AccountingAccount> accounts = AccountingAccount.find("company = ?1", company).list();
        for (AccountingAccount account : accounts) {
            result.add(account.getAccountCode());
        }
        return result;
    }

    static LocalDate fiscalStartFromEconomicsPeriod(String date) {
        String[] parts = date.split("_");
        if (parts.length >= 1) {
            return LocalDate.of(Integer.parseInt(parts[0]), 7, 1);
        }
        throw new IllegalArgumentException("Unsupported e-conomic accounting period: " + date);
    }

    static LocalDate parseEconomicsDate(String value) {
        if (value == null || value.length() < 10) {
            throw new IllegalArgumentException("Missing e-conomic entry date");
        }
        try {
            return LocalDate.parse(value.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
    }

    static double draftAmountInBaseCurrency(JournalEntryResponse.Entry entry) {
        if (entry.currency == null || entry.currency.isBlank() || "DKK".equalsIgnoreCase(entry.currency) || entry.exchangeRate == null) {
            return entry.amount;
        }
        return entry.amount * entry.exchangeRate / 100.0;
    }

    public EconomicsInvoice getFirstPage(String date, URI apiUri, String appSecretToken, String agreementGrantToken) throws JsonProcessingException {
        EconomicsInvoice economicsInvoice;
        try (EconomicsPagingAPI remoteApi = RestClientBuilder.newBuilder()
                .baseUri(apiUri)
                .register(new EconomicsDynamicHeaderFilter(appSecretToken, agreementGrantToken))
                .build(EconomicsPagingAPI.class)) {
            ObjectMapper objectMapper = new ObjectMapper();
            economicsInvoice = objectMapper.readValue(remoteApi.getEntries(date, 1000, 0).readEntity(String.class), EconomicsInvoice.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return economicsInvoice;
    }

    public EconomicsInvoice getNextPage(URI apiUri, String appSecretToken, String agreementGrantToken) throws JsonProcessingException {
        EconomicsInvoice economicsInvoice;
        try(EconomicsPagingAPI remoteApi = RestClientBuilder.newBuilder()
                .baseUri(apiUri)
                .register(new EconomicsDynamicHeaderFilter(appSecretToken, agreementGrantToken))
                .build(EconomicsPagingAPI.class)) {
            ObjectMapper objectMapper = new ObjectMapper();
            economicsInvoice = objectMapper.readValue(remoteApi.getNextPage().readEntity(String.class), EconomicsInvoice.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return economicsInvoice;
    }

    @Transactional
    public void clean() {
        FinanceDetails.deleteAll();
        Finance.deleteAll();
    }
}
