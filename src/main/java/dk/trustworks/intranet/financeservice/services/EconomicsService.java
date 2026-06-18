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
import dk.trustworks.intranet.financeservice.remote.EconomicsPagingAPI;
import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
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
