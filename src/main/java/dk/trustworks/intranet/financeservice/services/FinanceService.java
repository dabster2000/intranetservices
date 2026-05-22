package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.financeservice.model.Finance;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup;
import dk.trustworks.intranet.financeservice.model.enums.ExcelFinanceType;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import io.quarkus.cache.CacheResult;

import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class FinanceService {

    @CacheResult(cacheName = "financedetails-cache")
    public List<FinanceDetails> listAll() {
        return FinanceDetails.find("postingstatus = ?1", PostingStatus.BOOKED).list();
    }

    @CacheResult(cacheName = "financedetails-cache")
    public List<FinanceDetails> listAll(String accountGroup) {
        EconomicAccountGroup economicAccountGroup = EconomicAccountGroup.valueOf(accountGroup.toUpperCase());
        return FinanceDetails.find("accountnumber > ?1 AND accountnumber < ?2 AND postingstatus = ?3", economicAccountGroup.getRange().getMinimum(), economicAccountGroup.getRange().getMaximum(), PostingStatus.BOOKED).list();
    }

    @CacheResult(cacheName = "financedetails-cache")
    public List<Finance> findByAccountAndPeriod(String expenseType, LocalDate from, LocalDate to) {
        return Finance.find("expensetype like ?1 AND period > ?2 AND period < ?3 AND postingstatus = ?4", ExcelFinanceType.valueOf(expenseType), from, to, PostingStatus.BOOKED).list();
    }

    @CacheResult(cacheName = "financedetails-cache")
    public List<Finance> findByMonth(LocalDate month) {
        return Finance.find("period = ?1 AND postingstatus = ?2", month, PostingStatus.BOOKED).list();
    }

    public List<FinanceDetails> findByExpenseMonthAndAccountnumber(LocalDate month, String accountNumberString) {
        List<Integer> accountNumbers = Arrays.stream(accountNumberString.split(",")).mapToInt(Integer::parseInt).boxed().toList();
        return FinanceDetails.stream("expensedate = ?1 AND postingstatus = ?2", month, PostingStatus.BOOKED).map(p -> (FinanceDetails) p).filter(e -> accountNumbers.contains(e.getAccountnumber())).collect(Collectors.toList());
    }

}
