package dk.trustworks.intranet.financeservice.jobs;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.financeservice.model.enums.EconomicAccountGroup;
import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import dk.trustworks.intranet.financeservice.services.EconomicsService;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.intranet.utils.DateUtils.getFiscalYearName;
import static dk.trustworks.intranet.utils.DateUtils.toEconomicsUrlYear;

@JBossLog
@ApplicationScoped
public class FinanceLoadJob {

    // Per-tenant Slack alert rate limit. Mirrors OpexDistributionRefreshBatchlet's
    // 6h cadence so the same tenant + period failure doesn't spam the channel
    // across the multiple FY iterations within a single nightly run.
    static final Duration ALERT_REPEAT_INTERVAL = Duration.ofHours(6);

    @Inject
    EconomicsService economicsService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "slack.opsAlertChannel", defaultValue = "C0B2VQ2CFU1")
    String opsAlertChannel;

    // companyUuid → last-sent Instant. Bounded by # of companies (3), no need to evict.
    final ConcurrentHashMap<String, Instant> lastAlertByCompany = new ConcurrentHashMap<>();

    //private final String[] periods = {"2016_6_2017", "2017_6_2018", "2018_6_2019", "2019_6_2020", "2020_6_2021", "2021_6_2022", "2022_6_2023", "2023_6_2024"};
    private final String[] periods = {"2021_6_2022", "2022_6_2023", "2023_6_2024", "2024_6_2025", "2025_6_2026"};

    //@Scheduled(every="1h")
    //@Scheduled(cron="0 0 21 * * ?") // disabled; replaced by JBeret job 'finance-load-economics' triggered via BatchScheduler
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    void loadEconomicsData() {
        log.info("ExpenseLoadJob.loadEconomicsData");
        log.info("Cleaning old data...");
        List<Company> companies = Company.listAll();
        economicsService.clean();
        log.debug("Clean done!");
        for (Company company : companies) {
            int year = Math.max(company.getCreated().getYear(), DateUtils.getCurrentFiscalStartDate().getYear()-2);
            for (int i = year; i <= DateUtils.getCurrentFiscalStartDate().getYear(); i++) {
                String economicsUrlYear = toEconomicsUrlYear(getFiscalYearName(LocalDate.of(i, 6, 1), company.getUuid()));
                log.info("Load data from periode: "+economicsUrlYear+" for company "+company.getUuid());
                Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> allEntries;
                try {
                    // Order matters: getAllEntries persists finance_details (the
                    // uq_fd_logical_key table) and THROWS on a 1062 collision, so a
                    // concurrent-peer duplicate aborts this iteration BEFORE persistExpenses
                    // writes the aggregated 'finances' rows. 'finances' has no unique key, so
                    // this throw-before-persist ordering is the only thing keeping the two
                    // tables consistent under a concurrent peer — do not reorder, and do not
                    // make finance_details persistence non-throwing (e.g. INSERT IGNORE)
                    // without first adding a 'finances' duplicate guard.
                    allEntries = economicsService.getAllEntries(company, economicsUrlYear);
                    economicsService.persistExpenses(allEntries);
                    log.info("allEntries.size() = " + allEntries.size());
                    log.info("Entries for period " + economicsUrlYear + " persisted!");
                } catch (Exception e) {
                    if (isConcurrentDuplicate(e)) {
                        // A concurrent peer run (e.g. the draining OLD task re-firing the
                        // 21:00 schedule during an ECS-Express deploy bake) already loaded
                        // this (company, period). The uq_fd_logical_key 1062 collision rolled
                        // back ONLY this batch's own transaction (getAllEntries persists via
                        // QuarkusTransaction.requiringNew), so the rows are present and the
                        // rest of the run is unaffected. Skip quietly — expected during a
                        // double-fire and self-heals; no Slack alert.
                        log.warnf("Skipping company %s period %s: finance_details already loaded by a concurrent peer run (uq_fd_logical_key collision)",
                                company.getUuid(), economicsUrlYear);
                    } else {
                        log.error("Error loading data for company "+company.getUuid(), e);
                        fireSlackAlertIfNeeded(company, economicsUrlYear, e);
                    }
                }
            }
        }
        /*
        for (String period : periods) {
            for(Company company : companies) {
                log.info("Load data from periode: "+period);
                Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> allEntries;
                allEntries = economicsService.getAllEntries(company, period);
                economicsService.persistExpenses(allEntries);
                log.info("allEntries.size() = " + allEntries.size());
                log.info("Entries for period "+period+" persisted!");
            }
        }

         */
    }

    //@Scheduled(every="5m")
    //@Scheduled(cron = "0 0 22 * * ?") // disabled; replaced by JBeret job 'finance-invoice-sync' triggered via BatchScheduler
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
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

    /**
     * True when {@code e} (or anything in its cause chain) is a unique-key collision on
     * {@code uq_fd_logical_key} — i.e. a concurrent peer run already inserted these
     * finance_details rows. The persist runs in {@code QuarkusTransaction.requiringNew()}
     * inside {@link EconomicsService#getAllEntries}, so a commit-time violation surfaces
     * wrapped (RollbackException / ArcUndeclaredThrowableException); the whole cause chain
     * is inspected.
     *
     * <p>Detection is by message, anchored on the constraint name {@code uq_fd_logical_key}
     * (a locale-independent DB object name). This is deliberately narrow: a genuine,
     * unrelated integrity error (NOT NULL, FK, another unique key, …) does not carry this
     * marker, so it falls through to the Slack alert instead of being silently skipped on
     * every run. A real duplicate means the rows are already present, so the caller skips
     * this (company, period) quietly rather than alerting and re-deriving — see
     * {@link #loadEconomicsData()}.
     */
    static boolean isConcurrentDuplicate(Throwable e) {
        for (Throwable t : ExceptionUtils.getThrowableList(e)) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("uq_fd_logical_key")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Posts a Slack alert when a per-tenant load fails. Pre-2026-05-15 this
     * was a silent `log.error(...)` and per-tenant credential drift or
     * pagination quirks accumulated for months before being noticed (see the
     * Feb-Apr 2026 TWC/TWT gap analysis in
     * docs/superpowers/analysis/2026-05-13-ebitda-gap-final-reconciliation.md).
     *
     * <p>Rate-limited to one alert per (companyUuid) per {@link #ALERT_REPEAT_INTERVAL}
     * so a recurring failure across the per-FY inner loop doesn't spam the
     * channel — the channel keeper, not Slack, is the rate-limiting authority.
     */
    void fireSlackAlertIfNeeded(Company company, String period, Exception e) {
        Instant now = Instant.now();
        Instant previous = lastAlertByCompany.get(company.getUuid());
        if (previous != null
                && Duration.between(previous, now).compareTo(ALERT_REPEAT_INTERVAL) < 0) {
            log.debugf("finance-load-economics tenant=%s failure still active — suppressing duplicate Slack alert (last sent %s)",
                    company.getUuid(), previous);
            return;
        }
        String msg = ":rotating_light: *finance-load-economics: per-tenant failure*\n"
                + "• company: `" + (company.getName() != null ? company.getName() : "?") + "` (" + company.getUuid() + ")\n"
                + "• period: " + period + "\n"
                + "• error: `" + e.getClass().getSimpleName() + ": " + (e.getMessage() != null ? e.getMessage() : "(no message)") + "`\n"
                + "• impact: finance_details for this tenant is missing entries until the next successful nightly sync (21:00 UTC). "
                + "EBITDA chart will under-report this tenant's costs.\n"
                + "• action: check Quarkus production logs for the full stack trace and verify integration_keys credentials for this company.";
        slackService.sendMessage(opsAlertChannel, msg, "mother");
        lastAlertByCompany.put(company.getUuid(), now);
    }
}
