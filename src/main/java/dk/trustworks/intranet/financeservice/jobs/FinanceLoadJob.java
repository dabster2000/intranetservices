package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.services.EconomicsService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;

import java.util.List;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class FinanceLoadJob {

    @Inject
    EconomicsService economicsService;

    //private final String[] periods = {"2016_6_2017", "2017_6_2018", "2018_6_2019", "2019_6_2020", "2020_6_2021", "2021_6_2022", "2022_6_2023", "2023_6_2024"};
    private final String[] periods = {"2021_6_2022", "2022_6_2023", "2023_6_2024"};

    //@Scheduled(every="1h")
    //@Scheduled(cron="0 0 21 * * ?") // disabled; replaced by JBeret job 'finance-load-economics' triggered via BatchScheduler
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void loadEconomicsData() {
        log.debug("ExpenseLoadJob.loadEconomicsData");
        log.debug("Cleaning old data...");
        List<Company> companies = Company.listAll();
        economicsService.clean();
        log.debug("Clean done!");
        for (Company company : companies) {
            int year = company.getCreated().getYear();
            for (int i = year; i <= DateUtils.getCurrentFiscalStartDate().getYear(); i++) {
                log.info("Load data from periode: "+(i+"_6_"+(i+1))+" for company "+company.getUuid());
                Map<Range<Integer>, List<Collection>> allEntries;
                try {
                    allEntries = economicsService.getAllEntries(company, (i + "_6_" + (i + 1)));
                    economicsService.persistExpenses(allEntries);
                    log.info("allEntries.size() = " + allEntries.size());
                    log.info("Entries for period " + (i + "_6_" + (i + 1)) + " persisted!");
                } catch (Exception e) {
                    log.error("Error loading data for company "+company.getUuid(), e);
                }
            }
        }


        for (String period : periods) {
            for(Company company : companies) {
                log.info("Load data from periode: "+period);
                Map<Range<Integer>, List<Collection>> allEntries;
                allEntries = economicsService.getAllEntries(company, period);
                economicsService.persistExpenses(allEntries);
                log.info("allEntries.size() = " + allEntries.size());
                log.info("Entries for period "+period+" persisted!");
            }
        }
    }
}
