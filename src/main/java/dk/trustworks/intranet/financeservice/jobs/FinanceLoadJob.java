package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup;
import dk.trustworks.intranet.financeservice.remote.dto.economics.Collection;
import dk.trustworks.intranet.financeservice.services.EconomicsService;
import dk.trustworks.intranet.model.Company;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;

import java.util.List;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class FinanceLoadJob {

    @Inject
    EconomicsService economicsService;

    @Inject
    InvoiceService invoiceService;

    //private final String[] periods = {"2016_6_2017", "2017_6_2018", "2018_6_2019", "2019_6_2020", "2020_6_2021", "2021_6_2022", "2022_6_2023", "2023_6_2024"};
    private final String[] periods = {"2023_6_2024"};


    @Scheduled(every="5m")
    @Scheduled(cron="0 0 21 * * ?")
    void loadEconomicsData() {
        log.debug("ExpenseLoadJob.loadEconomicsData");
        log.debug("Cleaning old data...");
        List<Company> companies = Company.listAll();
        economicsService.clean();
        log.debug("Clean done!");
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

    @Scheduled(every="5m")
    //@Scheduled(cron = "0 0 22 * * ?")
    public void synchronizeInvoices() {
        log.info("ExpenseLoadJob.synchronizeInvoices");
        List<FinanceDetails> expenseList = FinanceDetails.find("accountnumber >= ?1 and accountnumber <= ?2", EconomicAccountGroup.OMSAETNING_ACCOUNTS.getRange().getMinimum(), EconomicAccountGroup.OMSAETNING_ACCOUNTS.getRange().getMaximum()).list();//EconomicAccountGroup.OMSAETNING_ACCOUNTS);
        log.info("Found "+expenseList.size()+" financedetail objects");

        List<Invoice> invoiceList = invoiceService.findAll();
        log.info("Found "+invoiceList.size()+" invoices");

        expenseList.forEach(expenseDetails -> {
            invoiceList.stream().filter(invoice -> invoice.invoicenumber == expenseDetails.getInvoicenumber())
                    .findFirst()
                    .ifPresent(invoice -> {
                        invoice.setBookingdate(expenseDetails.getExpensedate());
                        invoice.setReferencenumber(expenseDetails.getInvoicenumber());
                        invoiceService.updateInvoiceReference(invoice.getUuid(), new InvoiceReference(expenseDetails.getExpensedate(), expenseDetails.getInvoicenumber()));
                    });
        });
    }

    void onStart(@Observes StartupEvent ev) {
        log.info("The ExpenseLoadJob is starting...");
        //loadEconomicsData();
        //synchronizeInvoices();
    }
}
