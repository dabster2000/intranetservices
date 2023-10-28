package dk.trustworks.intranet.financeservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.remote.EconomicsAPI;
import dk.trustworks.intranet.financeservice.remote.EconomicsPagingAPI;
import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.remote.dto.economics.EconomicsInvoice;
import lombok.SneakyThrows;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.*;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup.*;

@JBossLog
@ApplicationScoped
public class EconomicsService {

    @Inject
    @RestClient
    EconomicsAPI economicsAPI;

    @Inject
    TransactionManager tm;

    public static final int[] PERSONALE = {3505, 3560, 3570, 3575, 3580, 3583, 3585, 3586, 3589, 3594};

    @SneakyThrows
    public Map<Range<Integer>, List<Collection>> getAllEntries(String date) {
        Map<Range<Integer>, List<Collection>> collectionResultMap = new HashMap<>();
        collectionResultMap.put(OMSAETNING_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(PRODUKTION_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(LOENNINGER_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(PERSONALE_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(VARIABEL_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(LOKALE_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(SALG_ACCOUNTS.getRange(), new ArrayList<>());
        collectionResultMap.put(ADMINISTRATION_ACCOUNTS.getRange(), new ArrayList<>());


        int page = 0;
        List<FinanceDetails> financeDetails = new ArrayList<>();

        ObjectMapper objectMapper = new ObjectMapper();
        EconomicsInvoice economicsInvoice = objectMapper.readValue(economicsAPI.getEntries(date, 1000, page).readEntity(String.class), EconomicsInvoice.class);

        String url;
        do {
            try {
                assert economicsInvoice != null;
                for (Collection collection : economicsInvoice.getCollection()) {
                    int accountNumber = collection.getAccount().getAccountNumber();
                    if(Arrays.binarySearch(PERSONALE, accountNumber) > -1) {
                        collection.getAccount().setAccountNumber(accountNumber-LOENNINGER_ACCOUNTS.getRange().getMinimum()+PERSONALE_ACCOUNTS.getRange().getMinimum());
                    }
                    collectionResultMap.keySet().forEach(integerRange -> {
                        if(integerRange.contains(collection.getAccount().getAccountNumber())) collectionResultMap.get(integerRange).add(collection);
                    });
                    financeDetails.add(new FinanceDetails(collection.getEntryNumber(), collection.getAccount().getAccountNumber(), collection.getInvoiceNumber(), collection.getAmount(), LocalDate.parse(collection.getDate(), DateTimeFormatter.ofPattern("yyyy-MM-dd")).withDayOfMonth(1), collection.getText()));
                }
                url = economicsInvoice.getPagination().getNextPage();
                if(url!=null) {
                    economicsInvoice = doWorkAgainstApi(URI.create(url));
                }
            } catch (Exception e) {
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

    public EconomicsInvoice doWorkAgainstApi(URI apiUri) throws JsonProcessingException {
        EconomicsInvoice economicsInvoice;
            EconomicsPagingAPI remoteApi = RestClientBuilder.newBuilder()
                    .baseUri(apiUri)
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
