package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.remote.DynamicHeaderFilter;
import dk.trustworks.intranet.financeservice.remote.EconomicsPagingAPI;
import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
import dk.trustworks.intranet.model.Company;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.*;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static dk.trustworks.intranet.financeservice.model.IntegrationKey.getIntegrationKeyValue;
import static dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup.*;

@JBossLog
@ApplicationScoped
public class EconomicsService {

    @Inject
    TransactionManager tm;

    @SneakyThrows
    public Map<Range<Integer>, List<Collection>> getAllEntries(Company company, String date) {
        Map<Range<Integer>, List<Collection>> collectionResultMap = new HashMap<>();
        collectionResultMap.put(OMSAETNING_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(PRODUKTION_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(LOENNINGER_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(PERSONALE_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(VARIABEL_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(LOKALE_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(SALG_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(ADMINISTRATION_ACCOUNTS.getRange(), new ArrayList<>());

        IntegrationKey.IntegrationKeyValue result = getIntegrationKeyValue(company);

        List<FinanceDetails> financeDetails = new ArrayList<>();

        EconomicsInvoice economicsInvoice = getFirstPage(date, URI.create(result.url()), result.appSecretToken(), result.agreementGrantToken());
        String url = "";
        do {
            try {
                assert economicsInvoice != null;
                for (Collection collection : economicsInvoice.getCollection()) {
                    collectionResultMap.keySet().forEach(integerRange -> {
                        if(integerRange.contains(collection.getAccount().getAccountNumber())) collectionResultMap.get(integerRange).add(collection);
                    });
                    financeDetails.add(new FinanceDetails(company, collection.getEntryNumber(), collection.getAccount().getAccountNumber(), collection.getInvoiceNumber(), collection.getAmount(), LocalDate.parse(collection.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd")).withDayOfMonth(1), collection.getText()));
                }
                url = economicsInvoice.getPagination().getNextPage();
                if(url!=null) {
                    economicsInvoice = getNextPage(URI.create(url), result.appSecretToken(), result.agreementGrantToken());
                }
            } catch (Exception e) {
                System.out.println("url = " + url);
                System.out.println("result = " + result);
                System.out.println("economicsInvoice = " + economicsInvoice.pagination.getNextPage());
                throw new RuntimeException(e);
            }
        } while (url != null);


        try {
            tm.begin();
            FinanceDetails.persist(financeDetails);
            tm.commit();
        } catch (NotSupportedException | HeuristicRollbackException | SystemException | HeuristicMixedException |
                 RollbackException e) {
            throw new RuntimeException(e);
        }
        return collectionResultMap;
    }

    @Transactional
    public void persistExpenses(Map<Range<Integer>, List<Collection>> allEntries) {
        Finance.persist(getExpenseMap(ExcelFinanceType.LØNNINGER, allEntries.get(LOENNINGER_ACCOUNTS.getRange())).values());
        Finance.persist(getExpenseMap(ExcelFinanceType.ADMINISTRATION, allEntries.get(ADMINISTRATION_ACCOUNTS.getRange())).values());
        Finance.persist(getExpenseMap(ExcelFinanceType.LOKALE, allEntries.get(LOKALE_ACCOUNTS.getRange())).values());
        Finance.persist(getExpenseMap(ExcelFinanceType.PRODUKTION, allEntries.get(PRODUKTION_ACCOUNTS.getRange())).values());
        Finance.persist(getExpenseMap(ExcelFinanceType.SALG, allEntries.get(SALG_ACCOUNTS.getRange())).values());
        Finance.persist(getExpenseMap(ExcelFinanceType.PERSONALE, allEntries.get(PERSONALE_ACCOUNTS.getRange())).values());
    }

    private Map<LocalDate, Finance> getExpenseMap(ExcelFinanceType excelType, List<Collection> collectionList) {
        Map<LocalDate, Finance> map = new HashMap<>();
        collectionList.forEach(collection -> {
            LocalDate period = LocalDate.parse(collection.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd")).withDayOfMonth(1);
            if(!map.containsKey(period)) map.put(period, new Finance(period, excelType, 0.0));
            map.get(period).setAmount(map.get(period).getAmount() + collection.getAmount());
        });
        return map;
    }

    public EconomicsInvoice getFirstPage(String date, URI apiUri, String appSecretToken, String agreementGrantToken) throws JsonProcessingException {
        EconomicsInvoice economicsInvoice;
        EconomicsPagingAPI remoteApi = RestClientBuilder.newBuilder()
                .baseUri(apiUri)
                .register(new DynamicHeaderFilter(appSecretToken, agreementGrantToken))
                .build(EconomicsPagingAPI.class);
        ObjectMapper objectMapper = new ObjectMapper();
        economicsInvoice = objectMapper.readValue(remoteApi.getEntries(date, 1000, 0).readEntity(String.class), EconomicsInvoice.class);

        return economicsInvoice;
    }

    public EconomicsInvoice getNextPage(URI apiUri, String appSecretToken, String agreementGrantToken) throws JsonProcessingException {
        EconomicsInvoice economicsInvoice;
            EconomicsPagingAPI remoteApi = RestClientBuilder.newBuilder()
                    .baseUri(apiUri)
                    .register(new DynamicHeaderFilter(appSecretToken, agreementGrantToken))
                    .build(EconomicsPagingAPI.class);
        ObjectMapper objectMapper = new ObjectMapper();
        economicsInvoice = objectMapper.readValue(remoteApi.getNextPage().readEntity(String.class), EconomicsInvoice.class);

        return economicsInvoice;
    }

    @Transactional
    public void clean() {
        FinanceDetails.deleteAll();
        Finance.deleteAll();
    }
}
