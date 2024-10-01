package dk.trustworks.intranet.aggregates.invoice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedment.jpastreamer.application.JPAStreamer;
import dk.trustworks.intranet.aggregates.availability.model.EmployeeAvailabilityPerMonth;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.network.CurrencyAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceDynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.network.dto.CurrencyData;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDTO;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.financeservice.model.AccountLumpSum;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.FinanceDetails;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TupleElement;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.io.IOException;
import java.net.URI;
import java.sql.Date;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static java.util.stream.Collectors.toList;

@JBossLog
@ApplicationScoped
public class InvoiceService {

    @Inject
    EntityManager em;

    /*
    @Inject
    @RestClient
    InvoiceAPI invoiceAPI;

     */

    @Inject
    @RestClient
    CurrencyAPI currencyAPI;

    @ConfigProperty(name = "currencyapi.key")
    String apiKey;

    @ConfigProperty(name = "invoice-generator.apikey")
    String invoiceGeneratorApiKey;

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

    @Inject
    AvailabilityService availabilityService;

    @Inject
    JPAStreamer jpaStreamer;

    private static DateValueDTO apply(Invoice invoice, double exchangeRate) {
        double sum = switch (invoice.getType()) {
            case INVOICE -> invoice.getSumWithNoTaxInDKK(exchangeRate);
            case PHANTOM -> invoice.getSumWithNoTaxInDKK(exchangeRate);
            case INTERNAL -> invoice.getSumWithNoTaxInDKK(exchangeRate);
            case CREDIT_NOTE -> -invoice.getSumWithNoTaxInDKK(exchangeRate);
            default -> 0.0;
        };
        return new DateValueDTO(LocalDate.of(invoice.getYear(), invoice.getMonth() + 1, 1), sum);
    }

    public List<Invoice> findAll() {
        return Invoice.findAll().list();
    }

    public static List<Invoice> findWithFilter(LocalDate fromdate, LocalDate todate, String... type) {
        String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "CREDIT_NOTE", "DRAFT"};
        LocalDate finalFromdate = fromdate!=null?fromdate:LocalDate.of(2014,1,1);
        LocalDate finalTodate = todate!=null?todate:LocalDate.now();
        List<Invoice> result;
        try (Stream<Invoice> invoices = Invoice.streamAll()) {
            result = invoices.filter(invoice ->
                            (invoice.getInvoicedate().isAfter(finalFromdate) || invoice.getInvoicedate().equals(finalFromdate)) &&
                                    (invoice.getInvoicedate().isBefore(finalTodate) ||invoice.getInvoicedate().isEqual(finalTodate)) &&
                                    Arrays.stream(finalType).anyMatch(s -> s.equals(invoice.getStatus().name())))
                    .collect(toList());

        }
        return result;
    }

    /**
     * {@code @Param} fromdate Date is included
     * {@code @Param} todate Date is not included
     * @return  type
     */
    public List<Invoice> findByBookingDate(LocalDate fromdate, LocalDate todate) {
        //String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "CREDIT_NOTE", "DRAFT"};
        LocalDate finalFromdate = fromdate!=null?fromdate:LocalDate.of(2014,1,1);
        LocalDate finalTodate = todate!=null?todate:LocalDate.now();

        List<Invoice> invoices = Invoice.find("invoicedate >= ?1 AND invoicedate < ?2 AND bookingdate = '1900-01-01'",
                finalFromdate, finalTodate).list();

        invoices.addAll(Invoice.find("bookingdate >= ?1 AND bookingdate < ?2",
                finalFromdate, finalTodate).list());
        return invoices;
    }

    public List<DateValueDTO> calculateInvoiceSumByPeriodAndWorkDate(String companyuuid, LocalDate fromdate, LocalDate todate) throws JsonProcessingException {
        Response response = currencyAPI.getExchangeRate(stringIt(fromdate), "EUR", "DKK", apiKey);
        double exchangeRate = 1.0;
        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            CurrencyData currencyData = objectMapper.readValue(response.readEntity(String.class), CurrencyData.class);
            exchangeRate = currencyData.getExchangeRate(stringIt(fromdate), "DKK");
        }
        System.out.println("exchangeRate = " + exchangeRate);
        String sql = "select " +
                "    STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') date, sum(if((i.type = 0 OR i.type = 3), (ii.rate*ii.hours * if(i.currency='DKK', 1, "+exchangeRate+")), -(ii.rate*ii.hours * if(i.currency='DKK', 1, "+exchangeRate+")))) value " +
                "from " +
                "    invoiceitems ii " +
                "LEFT JOIN " +
                "    invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE " +
                "    i.type != 4 AND " +
                "    i.status NOT LIKE 'DRAFT' AND " +
                "    i.companyuuid = '"+companyuuid+"' AND " +
                "    STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') >= '"+ stringIt(fromdate) +"' AND " +
                "    STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') < '"+ stringIt(todate)+"' " +
                "GROUP BY " +
                "    i.month, i.year;";

        String sql2 = "select " +
                "    STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') date, sum(ii.rate*ii.hours * if(i.currency='DKK', 1, "+exchangeRate+")) value " +
                "from " +
                "    invoiceitems ii " +
                "LEFT JOIN " +
                "    invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE " +
                "    i.type = 3 AND " +
                "    i.invoice_ref IN ( " +
                "        SELECT " +
                "            i.invoicenumber " +
                "        FROM " +
                "            invoices i " +
                "        WHERE " +
                "            i.status NOT LIKE 'DRAFT' AND " +
                "            i.companyuuid = '"+companyuuid+"' AND " +
                "            STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') >= '"+ stringIt(fromdate) +"' AND " +
                "            STR_TO_DATE(CONCAT(i.year, '-', i.month+1, '-01'), '%Y-%m-%d') < '"+ stringIt(todate)+"') " +
                "GROUP BY " +
                "    i.month, i.year;";


        List<Tuple> invoicedTupleList = ((List<Tuple>) em.createNativeQuery(sql, Tuple.class).getResultList());
        List<DateValueDTO> invoicedSumList = invoicedTupleList.stream()
                .map(tuple -> new DateValueDTO(
                        ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                        (Double) tuple.get("value")
                ))
                .toList();

        List<Tuple> internalTupleList = em.createNativeQuery(sql2, Tuple.class).getResultList();
        List<DateValueDTO> internalInvoicedSumList = new ArrayList<>();
        if(!internalTupleList.isEmpty()) {
            internalTupleList.stream().forEach(tuple -> {
                List<TupleElement<?>> elements = tuple.getElements();
            });
            internalInvoicedSumList = internalTupleList.stream()
                    .map(tuple -> {
                        return new DateValueDTO(
                                ((Date) tuple.get("date")).toLocalDate().withDayOfMonth(1),
                                (Double) tuple.get("value")
                        );
                    })
                    .toList();
        }

        LocalDate testDate = fromdate;
        List<DateValueDTO> result = new ArrayList<>();
        do {
            LocalDate finalTestDate = testDate;
            double invoicedSum = invoicedSumList.stream().filter(i -> i.getDate().isEqual(finalTestDate)).mapToDouble(DateValueDTO::getValue).sum();
            double internalInvoicedSum = internalInvoicedSumList.stream().filter(i -> i.getDate().isEqual(finalTestDate)).mapToDouble(DateValueDTO::getValue).sum();
            result.add(new DateValueDTO(testDate, invoicedSum - internalInvoicedSum));

            testDate = testDate.plusMonths(1);
        } while (testDate.isBefore(todate));
        return result;
    }

    @Transactional
    public List<DateValueDTO> calculateInvoiceSumByPeriodAndWorkDateV2(String companyuuid, LocalDate fromdate, LocalDate todate) throws JsonProcessingException {
        Response response = currencyAPI.getExchangeRate(stringIt(fromdate), "EUR", "DKK", apiKey);
        double exchangeRate;
        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            CurrencyData currencyData = objectMapper.readValue(response.readEntity(String.class), CurrencyData.class);
            exchangeRate = currencyData.getExchangeRate(stringIt(fromdate), "DKK");
        } else {
            exchangeRate = 1.0;
        }
        List<Invoice> invoiceList = Invoice.<Invoice>find("company = ?1 AND invoicedate >= ?2 AND invoicedate < ?3 AND type != ?4 AND status != ?5", Company.<Company>findById(companyuuid), fromdate, todate, InvoiceType.INTERNAL_SERVICE, InvoiceStatus.DRAFT).list();
        List<DateValueDTO> invoicedSumList = invoiceList.stream()
                .map((Invoice i) -> apply(i, exchangeRate))
                .toList();

        List<DateValueDTO> internalInvoicedSumList = jpaStreamer.stream(Invoice.class)
                .filter(invoice -> !invoice.getCompany().getUuid().equals(companyuuid))
                .filter(invoice -> invoice.getType().equals(InvoiceType.INTERNAL))
                .filter(invoice -> !invoice.getStatus().equals(InvoiceStatus.DRAFT))
                .filter(invoice -> invoiceList.stream().anyMatch(i -> i.getInvoicenumber() == invoice.getInvoiceref()))
                .map((Invoice i) -> apply(i, exchangeRate))
                .toList();

        LocalDate testDate = fromdate;
        List<DateValueDTO> result = new ArrayList<>();
        do {
            LocalDate finalTestDate = testDate;
            double invoicedSum = invoicedSumList.stream().filter(i -> i.getDate().isEqual(finalTestDate)).mapToDouble(DateValueDTO::getValue).sum();
            double internalInvoicedSum = internalInvoicedSumList.stream().filter(i -> i.getDate().isEqual(finalTestDate)).mapToDouble(DateValueDTO::getValue).sum();
            result.add(new DateValueDTO(testDate, invoicedSum - internalInvoicedSum));

            testDate = testDate.plusMonths(1);
        } while (testDate.isBefore(todate));
        return result;
    }

    public double calculateInvoiceSumByMonth(String companyuuid, LocalDate month) {
        String sql = "select sum(if(type = 0, (ii.rate*ii.hours), -(ii.rate*ii.hours))) sum from invoiceitems ii " +
                "LEFT JOIN invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE status NOT LIKE 'DRAFT' AND companyuuid = '"+companyuuid+"' AND EXTRACT(YEAR_MONTH FROM if(i.bookingdate != '1900-01-01', i.bookingdate, i.invoicedate)) = "+stringIt(month, "yyyyMM")+"; ";
        Object singleResult = em.createNativeQuery(sql).getSingleResult();
        return singleResult!=null?((Number) singleResult).doubleValue():0.0;
    }

    @SuppressWarnings("unchecked")
    public List<Invoice> findInvoicesForSingleMonth(LocalDate month, String... type) {
        String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "CREDIT_NOTE", "DRAFT"};
        LocalDate date = month.withDayOfMonth(1);
        String sql = "SELECT * FROM invoices i WHERE " +
                "i.year = " + date.getYear() + " AND i.month = " + (date.getMonthValue() - 1) + " " +
                " AND i.status IN ('"+String.join("','", finalType)+"');";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        return Collections.unmodifiableList(invoices);
    }

    @SuppressWarnings("unchecked")
    public List<Invoice> findProjectInvoices(String projectuuid) {
        String sql = "SELECT * FROM invoices i WHERE i.projectuuid like '"+projectuuid+"' AND i.status IN ('CREATED','CREDIT_NOTE') ORDER BY year DESC, month DESC;";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        return Collections.unmodifiableList(invoices);
    }

    public List<Invoice> findContractInvoices(String contractuuid) {
        return Invoice.find("contractuuid = ?1 AND status IN ('CREATED','CREDIT_NOTE') ORDER BY year DESC, month DESC", contractuuid).list();
    }

    @Transactional
    public Invoice createDraftInvoice(Invoice invoice) {
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.getInvoiceitems().forEach(invoiceItem -> invoiceItem.setInvoiceuuid(invoice.uuid));
        Invoice.persist(invoice);
        return invoice;
    }

    @Transactional
    public Invoice updateDraftInvoice(Invoice invoice) {
        System.out.println("InvoiceService.updateDraftInvoice");
        System.out.println("Updating invoice...");
        Invoice.update("attention = ?1, " +
                        "bookingdate = ?2, " +
                        "clientaddresse = ?3, " +
                        "contractref = ?4, " +
                        "clientname = ?5, " +
                        "cvr = ?6, " +
                        "discount = ?7, " +
                        "ean = ?8, " +
                        "invoicedate = ?9, " +
                        "invoiceref = ?10, " +
                        "month = ?11, " +
                        "otheraddressinfo = ?12, " +
                        "projectname = ?13, " +
                        "projectref = ?14, " +
                        "projectuuid = ?15, " +
                        "specificdescription = ?16, " +
                        "status = ?17, " +
                        "invoicenumber = ?18, " +
                        "type = ?19, " +
                        "year = ?20, " +
                        "zipcity = ?21, " +
                        "company = ?22, " +
                        "currency = ?23, " +
                        "bonusConsultant = ?24, " +
                        "bonusConsultantApprovedStatus = ?25, " +
                        "bonusOverrideAmount = ?26, " +
                        "bonusOverrideNote = ?27 " +
                        "WHERE uuid like ?28 ",
                invoice.getAttention(),
                invoice.getBookingdate(),
                invoice.getClientaddresse(),
                invoice.getContractref(),
                invoice.getClientname(),
                invoice.getCvr(),
                invoice.getDiscount(),
                invoice.getEan(),
                invoice.getInvoicedate(),
                invoice.getInvoiceref(),
                invoice.getMonth(),
                invoice.getOtheraddressinfo(),
                invoice.getProjectname(),
                invoice.getProjectref(),
                invoice.getProjectuuid(),
                invoice.getSpecificdescription(),
                invoice.getStatus(),
                invoice.getInvoicenumber(),
                invoice.getType(),
                invoice.getYear(),
                invoice.getZipcity(),
                invoice.getCompany(),
                invoice.getCurrency(),
                invoice.getBonusConsultant(),
                invoice.getBonusConsultantApprovedStatus(),
                invoice.getBonusOverrideAmount(),
                invoice.getBonusOverrideNote(),
                invoice.getUuid());
        System.out.println("Updating invoice items...");
        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        invoice.getInvoiceitems().forEach(invoiceItem -> {
            invoiceItem.setInvoiceuuid(invoice.uuid);
        });
        System.out.println("Persisting invoice items...");
        InvoiceItem.persist(invoice.invoiceitems);
        System.out.println("Invoice updated: "+invoice.getUuid());

        return invoice;
    }

    public Invoice createInvoice(Invoice draftInvoice) throws JsonProcessingException {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        draftInvoice.setStatus(InvoiceStatus.CREATED);
        draftInvoice.invoicenumber = getMaxInvoiceNumber(draftInvoice) + 1;
        draftInvoice.pdf = createInvoicePdf(draftInvoice);
        System.out.println("Saving invoice...");
        saveInvoice(draftInvoice);
        System.out.println("Invoice saved: "+draftInvoice.getUuid());
        System.out.println("Uploading invoice to economics...");
        uploadToEconomics(draftInvoice);
        System.out.println("Invoice uploaded to economics: "+draftInvoice.getUuid());

        return draftInvoice;
    }

    @Transactional
    public Invoice createPhantomInvoice(Invoice draftInvoice) throws JsonProcessingException {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        draftInvoice.setStatus(InvoiceStatus.CREATED);
        draftInvoice.invoicenumber = 0;
        draftInvoice.setType(InvoiceType.PHANTOM);
        draftInvoice.pdf = createInvoicePdf(draftInvoice);
        saveInvoice(draftInvoice);
        if(!"dev".equals(LaunchMode.current().getProfileKey())) {
            uploadToEconomics(draftInvoice);
            log.info("Uploaded invoice to economics ("+draftInvoice.invoicenumber+"): "+draftInvoice.getUuid());
        } else {
            log.warn("The invoice is not uploaded to e-conomics in Dev environment");
        }
        //createEmitter.send(draftInvoice);
        return draftInvoice;
    }

    private void saveInvoice(Invoice invoice) {
        Invoice.update(
                        "status = ?1, " +
                        "invoicenumber = ?2, " +
                        "pdf = ?3 " +
                        "WHERE uuid like ?4 ",
                invoice.getStatus(),
                invoice.getInvoicenumber(),
                invoice.getPdf(),
                invoice.getUuid());

        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        invoice.getInvoiceitems().forEach(invoiceItem -> {
            invoiceItem.setInvoiceuuid(invoice.uuid);
        });
        InvoiceItem.persist(invoice.invoiceitems);
    }

    @Transactional
    public Invoice createCreditNote(Invoice invoice) {
        invoice.status = InvoiceStatus.CREDIT_NOTE;
        updateDraftInvoice(invoice);

        Invoice creditNote = new Invoice(invoice.getInvoicenumber(), InvoiceType.CREDIT_NOTE, invoice.getContractuuid(), invoice.getProjectuuid(),
                invoice.getProjectname(), invoice.getDiscount(), invoice.getYear(), invoice.getMonth(), invoice.getClientname(),
                invoice.getClientaddresse(), invoice.getOtheraddressinfo(), invoice.getZipcity(),
                invoice.getEan(), invoice.getCvr(), invoice.getAttention(), LocalDate.now(),
                invoice.getProjectref(), invoice.getContractref(), invoice.contractType, invoice.getCompany(), invoice.getCurrency(),
                "Kreditnota til faktura " + StringUtils.convertInvoiceNumberToString(invoice.invoicenumber), invoice.getBonusConsultant(), invoice.getBonusConsultantApprovedStatus());

        creditNote.invoicenumber = 0;
        for (InvoiceItem invoiceitem : invoice.invoiceitems) {
            creditNote.getInvoiceitems().add(new InvoiceItem(invoiceitem.consultantuuid, invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), invoice.uuid));
        }
        Invoice.persist(creditNote);
        creditNote.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
        return creditNote;
    }

    @Transactional
    public void createInternalInvoiceDraft(String companyuuid, Invoice invoice) {
        Invoice internalInvoice = new Invoice(
                invoice.getInvoicenumber(),
                InvoiceType.INTERNAL,
                invoice.getContractuuid(),
                invoice.getProjectuuid(),
                invoice.getProjectname(),
                invoice.getDiscount(),
                invoice.getYear(),
                invoice.getMonth(),
                invoice.getCompany().getName(),
                invoice.getCompany().getAddress(),
                "",
                invoice.getCompany().getZipcode(),
                "",
                invoice.getCompany().getCvr(),
                "Tobias Kjølsen",
                LocalDate.now(),
                invoice.getProjectref(),
                invoice.getContractref(),
                invoice.contractType,
                Company.findById(companyuuid),
                invoice.getCurrency(),
                "Intern faktura knyttet til " + invoice.getInvoicenumber());
        for (InvoiceItem invoiceitem : invoice.getInvoiceitems()) {
            if(invoiceitem.getRate() == 0.0 || invoiceitem.hours == 0.0) continue;
            internalInvoice.getInvoiceitems().add(new InvoiceItem(invoiceitem.consultantuuid, invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), invoice.uuid));
        }
        Invoice.persist(internalInvoice);
        internalInvoice.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
    }

    @Transactional
    public void createInternalServiceInvoiceDraft(String fromCompanyuuid, String toCompanyuuid, LocalDate month) {
        List<EmployeeAvailabilityPerMonth> employeeAvailabilityPerMonthList = availabilityService.getAllEmployeeAvailabilityByPeriod(month, month.plusMonths(1));
        Company fromCompany = Company.findById(fromCompanyuuid);
        Company toCompany = Company.findById(toCompanyuuid);

        List<AccountingCategory> allAccountingCategories = AccountingCategory.listAll(Sort.by("accountCode", Sort.Direction.Ascending));
        List<FinanceDetails> allFinanceDetails = FinanceDetails.list("expensedate >= ?1 and expensedate < ?2", month, month.plusMonths(1));

        List<FinanceDetails> financeDetails = allFinanceDetails.stream().filter(fd -> fd.getCompany().equals(fromCompany)).toList();

        // Beregn totale lønsum for den primært valgte virksomhed. Dette bruges til at trække fra lønsummen, så den ikke deles mellem virksomhederne.
        double primaryCompanySalarySum = availabilityService.calculateSalarySum(fromCompany, month, employeeAvailabilityPerMonthList);

        // Beregn det gennemsnitlige antal konsulenter i den primære virksomhed. Dette bruges til at omkostningsfordele forbrug mellem virksomhederne.
        double fromCompanyConsultantAvg = availabilityService.calculateConsultantCount(fromCompany, month, employeeAvailabilityPerMonthList);

        double toCompanyConsultantAvg = availabilityService.calculateConsultantCount(toCompany, month, employeeAvailabilityPerMonthList);

        // Beregn det totale gennemsnitlige antal konsulenter i alle andre virksomheder end den primære. Dette bruge til at kunne regne en andel ud. F.eks. 65 medarbejdere ud af 100 i alt.
        AtomicReference<Double> secondaryCompanyConsultant = new AtomicReference<>(0.0);
        AtomicReference<Double> secondaryCompanySalarySum = new AtomicReference<>(0.0);
        Company.<Company>listAll().stream().filter(c -> !c.equals(fromCompany)).forEach(secondaryCompany -> {
            secondaryCompanySalarySum.updateAndGet(v -> v + availabilityService.calculateSalarySum(secondaryCompany, month, employeeAvailabilityPerMonthList));
            secondaryCompanyConsultant.updateAndGet(v -> v + availabilityService.calculateConsultantCount(secondaryCompany, month, employeeAvailabilityPerMonthList));
        });
        double totalNumberOfConsultants = fromCompanyConsultantAvg + secondaryCompanyConsultant.get();

        Invoice invoice = new Invoice(0, InvoiceType.INTERNAL_SERVICE, "", "", "", 0.0, month.getYear(), month.getMonthValue(), toCompany.getName(), toCompany.getAddress(), "", toCompany.getZipcode(), "", toCompany.getCvr(), "Tobias Kjølsen", LocalDate.now().withDayOfMonth(1).minusDays(1), "", "", ContractType.PERIOD, fromCompany, "DKK", "Intern faktura knyttet til " + month.getMonth().name());
        invoice.persistAndFlush();

        for (AccountingCategory accountingCategory : allAccountingCategories) {
            double accountingCategorySum = 0.0;
            for (AccountingAccount aa : accountingCategory.getAccounts()) {
                if (aa.getCompany().equals(fromCompany) && aa.isShared()) {
                    double fullExpenses = financeDetails.stream()
                            .filter(fd -> fd.getAccountnumber() == aa.getAccountCode() && fd.getExpensedate().equals(month))
                            .mapToDouble(FinanceDetails::getAmount)
                            .sum();

                    // Check and skip if expenses are negative, since they are not relevant for the calculation
                    if(fullExpenses <= 0) continue;

                    // Start by making the partial expenses equal fullExpenses
                    double partialExpenses = fullExpenses;

                    // Remove lump sums from the expenses
                    double lumpSum = AccountLumpSum.<AccountLumpSum>list("accountingAccount = ?1 and registeredDate = ?2", aa, month.withDayOfMonth(1)).stream().mapToDouble(AccountLumpSum::getAmount).sum();
                    partialExpenses -= lumpSum;

                        // Hvis kontoen er en lønkonto, så træk lønsummen fra, så den ikke deles mellem virksomhederne.
                    if (aa.isSalary()) {
                        AtomicReference<Double> otherSalarySources = new AtomicReference<>(0.0);
                        accountingCategory.getAccounts().stream()
                                .filter(value -> value.getCompany().equals(fromCompany) && !value.isShared() && value.isSalary())
                                        .forEach(value -> {
                                            otherSalarySources.updateAndGet(v -> v + financeDetails.stream()
                                                    .filter(fd -> fd.getAccountnumber() == value.getAccountCode() && fd.getExpensedate().equals(month))
                                                    .mapToDouble(FinanceDetails::getAmount)
                                                    .sum());
                                        });
                        partialExpenses += otherSalarySources.get();
                        partialExpenses = (Math.max(0, partialExpenses - (primaryCompanySalarySum * 1.02)));
                    }
                    if(partialExpenses <= 0) continue;

                    // partial fullExpenses should only account for the part of the fullExpenses equal to the share of consultants in the primary company
                    partialExpenses *= (toCompanyConsultantAvg / totalNumberOfConsultants);

                    // The loan is the difference between the fullExpenses and the partialExpenses
                    accountingCategorySum += (Math.max(0, partialExpenses));
                }
            }
            if(accountingCategorySum <= 0) continue;
            InvoiceItem invoiceItem = new InvoiceItem(accountingCategory.getAccountname(), "", accountingCategorySum, 1, invoice.getUuid());
            invoice.getInvoiceitems().add(invoiceItem);
            invoiceItem.persist();
        }
    }

    public byte[] createInvoicePdf(Invoice invoice) throws JsonProcessingException {
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(new InvoiceDTO(invoice));
        InvoiceAPI invoiceAPI = getInvoiceAPI();
        return invoiceAPI.createInvoicePDF(json);
    }

    public void regenerateInvoicePdf(String invoiceuuid) throws JsonProcessingException {
        Invoice invoice = Invoice.findById(invoiceuuid);
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(new InvoiceDTO(invoice));
        InvoiceAPI invoiceAPI = getInvoiceAPI();
        invoice.pdf = invoiceAPI.createInvoicePDF(json);
        invoice.persist();
    }

    public Integer getMaxInvoiceNumber(Invoice invoice) {
        Optional<Invoice> latestInvoice = Invoice.find("company = ?1", Sort.descending("invoicenumber"), invoice.getCompany()).firstResultOptional();
        return latestInvoice.map(i -> i.invoicenumber).orElse(1);
    }

    @Transactional
    public void deleteDraftInvoice(String invoiceuuid) {
        if(isDraftOrPhantom(invoiceuuid)) Invoice.deleteById(invoiceuuid);
    }

    private boolean isDraft(String invoiceuuid) {
        return Invoice.find("uuid LIKE ?1 AND status LIKE ?2", invoiceuuid, InvoiceStatus.DRAFT).count() > 0;
    }

    private boolean isDraftOrPhantom(String invoiceuuid) {
        return Invoice.find("uuid LIKE ?1 AND (status LIKE ?2 OR type = ?3 OR invoicenumber = 0)", invoiceuuid, InvoiceStatus.DRAFT, InvoiceType.PHANTOM).count() > 0;
    }

    @Transactional
    public void updateInvoiceReference(String invoiceuuid, InvoiceReference invoiceReference) {
        Invoice.update("bookingdate = ?1, referencenumber = ?2 WHERE uuid like ?3 ", invoiceReference.getBookingdate(), invoiceReference.getReferencenumber(), invoiceuuid);
    }

    public void uploadToEconomics(Invoice invoice) {
        if(invoice.invoicenumber == 0) return;
        try {
            economicsInvoiceService.sendVoucher(invoice);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void updateInvoiceStatus(String invoiceuuid, SalesApprovalStatus status) {
        Invoice.update("bonusConsultantApprovedStatus = ?1 WHERE uuid like ?2", status, invoiceuuid);
    }

    private InvoiceAPI getInvoiceAPI() {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://invoice-generator.com"))
                .register(new InvoiceDynamicHeaderFilter(invoiceGeneratorApiKey))
                .build(InvoiceAPI.class);
    }
}
