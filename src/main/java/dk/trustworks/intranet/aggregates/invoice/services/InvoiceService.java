package dk.trustworks.intranet.aggregates.invoice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedment.jpastreamer.application.JPAStreamer;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.network.CurrencyAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceDynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.network.dto.CurrencyData;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDTO;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.BonusApprovalRow;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.MyBonusFySum;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.MyBonusRow;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.ClientWithInternalsDTO;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.SortBuilder;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.LaunchMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;

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

    @Inject IntercompanyCalcService calcService;

    @Inject
    PricingEngine pricingEngine;

    @Inject
    @RestClient
    CurrencyAPI currencyAPI;

    @ConfigProperty(name = "currencyapi.key")
    String apiKey;

    @ConfigProperty(name = "invoice-generator.apikey")
    String invoiceGeneratorApiKey;

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

    @Inject InvoiceBonusService bonusService;

    @Inject
    JPAStreamer jpaStreamer;
    @Inject
    WorkService workService;

    @Inject
    UserService userService;

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

    public Invoice findOneByUuid(String invoiceuuid) {
        return Invoice.findById(invoiceuuid);
    }

    public List<Invoice> findAll() {
        return Invoice.findAll().list();
    }

    public List<Invoice> findPaged(LocalDate from,
                                   LocalDate to,
                                   int pageIdx,
                                   int pageSize,
                                   List<String> sortParams) {

        Sort sort = SortBuilder.from(sortParams);           // see § 3
        Page page = Page.of(pageIdx, pageSize);

        PanacheQuery<Invoice> q;

        if (from == null || to == null) {                   // no date filter
            q = Invoice.findAll(sort);
        } else {                                            // with date range
            q = Invoice.find("invoicedate >= ?1 and invoicedate < ?2",
                    sort, from, to);
        }
        return q.page(page).list();
    }

    public long countInvoices() {
        return Invoice.count();
    }

    // InvoiceService
    public static List<Invoice> findWithFilter(LocalDate fromdate, LocalDate todate, String... type) {
        List<InvoiceStatus> statuses =
                (type != null && type.length > 0)
                        ? Arrays.stream(type).map(InvoiceStatus::valueOf).toList()
                        : List.of(InvoiceStatus.CREATED, InvoiceStatus.CREDIT_NOTE, InvoiceStatus.DRAFT, InvoiceStatus.QUEUED);

        LocalDate from = (fromdate != null) ? fromdate : LocalDate.of(2014, 1, 1);
        LocalDate to   = (todate   != null) ? todate   : LocalDate.now();

        // All filtering happens in SQL
        return Invoice.find("invoicedate >= ?1 AND invoicedate <= ?2 AND status IN (?3)",
                from, to, statuses).list();
    }


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

    @Transactional
    public List<DateValueDTO> calculateInvoiceSumByPeriodAndWorkDateV2(String companyuuid,
                                                                       LocalDate fromdate,
                                                                       LocalDate todate)
            throws JsonProcessingException {

        Response response = currencyAPI.getExchangeRate(stringIt(fromdate), "EUR", "DKK", apiKey);
        double exchangeRate;
        if ((response.getStatus() > 199) & (response.getStatus() < 300)) {
            ObjectMapper objectMapper = new ObjectMapper();
            CurrencyData currencyData = objectMapper.readValue(response.readEntity(String.class), CurrencyData.class);
            exchangeRate = currencyData.getExchangeRate(stringIt(fromdate), "DKK");
        } else {
            exchangeRate = 1.0;
        }

        // External (non-internal-service, non-draft) invoices in period for the company
        List<Invoice> invoiceList = Invoice.<Invoice>find(
                        "company = ?1 AND invoicedate >= ?2 AND invoicedate < ?3 AND type <> ?4 AND status <> ?5",
                        Company.<Company>findById(companyuuid), fromdate, todate, InvoiceType.INTERNAL_SERVICE, InvoiceStatus.DRAFT)
                .list();

        List<DateValueDTO> invoicedSumList = invoiceList.stream()
                .map(i -> apply(i, exchangeRate))
                .toList();

        // Build set of invoicenumbers to join against (for internal counterpart)
        Set<Integer> invoiceNumbers = invoiceList.stream()
                .map(Invoice::getInvoicenumber)
                .filter(n -> n != null && n > 0)
                .collect(Collectors.toSet());

        List<DateValueDTO> internalInvoicedSumList = invoiceNumbers.isEmpty()
                ? List.of()
                : Invoice.<Invoice>find(
                        "company.uuid <> ?1 AND type = ?2 AND status <> ?3 AND invoiceref IN (?4)",
                        companyuuid, InvoiceType.INTERNAL, InvoiceStatus.DRAFT, invoiceNumbers)
                .list()
                .stream()
                .map(i -> apply(i, exchangeRate))
                .toList();

        // Monthly bucket
        LocalDate cursor = fromdate;
        List<DateValueDTO> result = new ArrayList<>();
        while (cursor.isBefore(todate)) {
            final LocalDate month = cursor;
            double invoicedSum = invoicedSumList.stream()
                    .filter(d -> d.getDate().isEqual(month)).mapToDouble(DateValueDTO::getValue).sum();
            double internalSum = internalInvoicedSumList.stream()
                    .filter(d -> d.getDate().isEqual(month)).mapToDouble(DateValueDTO::getValue).sum();
            result.add(new DateValueDTO(month, invoicedSum - internalSum));
            cursor = cursor.plusMonths(1);
        }
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
        String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "CREDIT_NOTE", "DRAFT", "QUEUED"};
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

    /**
     * Efficiently fetches invoices for multiple contracts in a single query.
     * This method retrieves all invoices for the given contract UUIDs and groups them by contract.
     *
     * @param contractUuids List of contract UUIDs to fetch invoices for
     * @return Map of contract UUID to list of invoices for that contract
     */
    public Map<String, List<Invoice>> findInvoicesForContracts(List<String> contractUuids) {
        if (contractUuids == null || contractUuids.isEmpty()) {
            return new HashMap<>();
        }

        Map<String, List<Invoice>> results = new HashMap<>();

        // Initialize all contracts with empty lists to ensure all requested contracts are in the result
        for (String uuid : contractUuids) {
            results.put(uuid, new ArrayList<>());
        }

        // Fetch all invoices for all contracts in a single query
        List<Invoice> invoices = Invoice.find(
                "contractuuid IN ?1 AND status IN ('CREATED','CREDIT_NOTE')",
                Sort.by("year", Sort.Direction.Descending).and("month", Sort.Direction.Descending),
                contractUuids
        ).list();

        // Group invoices by contract UUID
        Map<String, List<Invoice>> groupedInvoices = invoices.stream()
                .collect(Collectors.groupingBy(Invoice::getContractuuid));

        // Merge with results map to ensure all requested contracts are present
        results.putAll(groupedInvoices);

        return results;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Invoice createDraftInvoice(Invoice invoice) {
        log.debug("Persisting draft invoice");
        invoice.setStatus(InvoiceStatus.DRAFT);
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.getInvoiceitems().forEach(invoiceItem -> invoiceItem.setInvoiceuuid(invoice.uuid));
        Invoice.persist(invoice);
        log.debug("Draft invoice persisted: " + invoice.getUuid());
        return invoice;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Invoice updateDraftInvoice(Invoice invoice) {
        if (invoice.getType() == InvoiceType.CREDIT_NOTE || invoice.getType() == InvoiceType.INTERNAL) {
            return legacyUpdateDraft(invoice);
        }

        if (invoice.getStatus() != InvoiceStatus.DRAFT) throw new WebApplicationException(Response.Status.CONFLICT);

        Invoice.update("attention = ?1, bookingdate = ?2, clientaddresse = ?3, contractref = ?4, clientname = ?5, cvr = ?6, " +
                        "discount = ?7, ean = ?8, invoicedate = ?9, invoiceref = ?10, invoiceRefUuid = ?11, month = ?12, otheraddressinfo = ?13, " +
                        "projectname = ?14, projectref = ?15, projectuuid = ?16, specificdescription = ?17, status = ?18, " +
                        "invoicenumber = ?19, type = ?20, year = ?21, zipcity = ?22, company = ?23, currency = ?24, " +
                        "bonusConsultant = ?25, bonusConsultantApprovedStatus = ?26, bonusOverrideAmount = ?27, bonusOverrideNote = ?28, " +
                        "duedate = ?29, vat = ?30 WHERE uuid like ?31",
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
                invoice.getInvoiceRefUuid(),
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
                invoice.getDuedate(),
                invoice.getVat(),
                invoice.getUuid());

        recalculateInvoiceItems(invoice);
        bonusService.recalcForInvoice(invoice.getUuid());
        return invoice;
    }

    private void recalculateInvoiceItems(Invoice invoice) {
        var baseItems = invoice.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() == null || ii.getOrigin() == BASE)
                .toList();
        System.out.print("PRE: ");
        baseItems.forEach(System.out::println);

        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        baseItems.forEach(ii -> { ii.setInvoiceuuid(invoice.getUuid()); ii.setOrigin(BASE); });
        InvoiceItem.persist(baseItems);

        Map<String, String> cti = new HashMap<>();
        ContractTypeItem.<ContractTypeItem>find("contractuuid", invoice.getContractuuid())
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));

        var pr = pricingEngine.price(invoice, cti);

        pr.syntheticItems.forEach(ii -> ii.setInvoiceuuid(invoice.getUuid()));
        InvoiceItem.persist(pr.syntheticItems);

        invoice.invoiceitems.clear();
        invoice.invoiceitems.addAll(baseItems);
        invoice.invoiceitems.addAll(pr.syntheticItems);
        invoice.sumBeforeDiscounts = pr.sumBeforeDiscounts.doubleValue();
        invoice.sumAfterDiscounts  = pr.sumAfterDiscounts.doubleValue();
        invoice.vatAmount          = pr.vatAmount.doubleValue();
        invoice.grandTotal         = pr.grandTotal.doubleValue();
        invoice.calculationBreakdown = pr.breakdown;
        System.out.print("DONE: ");
        invoice.getInvoiceitems().forEach(System.out::println);
    }

    @Transactional
    public void updateInvoiceStatus(Invoice invoice, InvoiceStatus status) {
                Invoice.update("status = ?1 WHERE uuid = ?2",
                status,
                invoice.getUuid());
    }

    /** Legacy fallback hvis engine ikke må køre for denne type */
    private Invoice legacyUpdateDraft(Invoice invoice) {
        // (identisk med jeres nuværende implementering, forkortet for overskuelighed)
        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        invoice.getInvoiceitems().forEach(ii -> ii.setInvoiceuuid(invoice.getUuid()));
        InvoiceItem.persist(invoice.getInvoiceitems());
        return invoice;
    }

    @Transactional
    public Invoice createInvoice(Invoice draftInvoice) throws JsonProcessingException {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        draftInvoice.invoicenumber = getMaxInvoiceNumber(draftInvoice) + 1;
        if(draftInvoice.getType() == InvoiceType.CREDIT_NOTE) {
            Invoice parentInvoice = Invoice.findById(draftInvoice.getCreditnoteForUuid());
            clearBonusFields(parentInvoice);
            clearBonusFields(draftInvoice);
            updateInvoiceBonusStatus(parentInvoice);
            parentInvoice.status = InvoiceStatus.CREDIT_NOTE;
            updateInvoiceStatus(parentInvoice, InvoiceStatus.CREDIT_NOTE);
        }

        if (draftInvoice.getType() != InvoiceType.CREDIT_NOTE && draftInvoice.getType() != InvoiceType.INTERNAL) {
            recalculateInvoiceItems(draftInvoice);
            bonusService.recalcForInvoice(draftInvoice.getUuid());
        }
        draftInvoice.setStatus(InvoiceStatus.CREATED);
        draftInvoice.pdf = createInvoicePdf(draftInvoice);
        log.debug("Saving invoice...");
        saveInvoice(draftInvoice);
        log.debug("Invoice saved: "+draftInvoice.getUuid());
        log.debug("Uploading invoice to economics...");
        uploadToEconomics(draftInvoice);
        log.debug("Invoice uploaded to economics: "+draftInvoice.getUuid());
        String contractuuid = draftInvoice.getContractuuid();
        String projectuuid = draftInvoice.getProjectuuid();
        workService.registerAsPaidout(contractuuid, projectuuid, draftInvoice.getMonth()+1, draftInvoice.getYear());

        return draftInvoice;
    }

    private static void clearBonusFields(Invoice parentInvoice) {
        parentInvoice.setBonusConsultant(null);
        parentInvoice.setBonusOverrideAmount(0);
        parentInvoice.setBonusOverrideNote(null);
        parentInvoice.setBonusConsultantApprovedStatus(SalesApprovalStatus.PENDING);
    }

    @Transactional
    public void updateInvoiceBonusStatus(@NotNull Invoice invoice) {
        Invoice.update("bonusConsultant = ?1, " +
                        "bonusOverrideAmount = ?2, " +
                        "bonusOverrideNote = ?3, " +
                        "bonusConsultantApprovedStatus = ?4 " +
                        "WHERE uuid like ?5",
                invoice.getBonusConsultant(),
                invoice.getBonusOverrideAmount(),
                invoice.getBonusOverrideNote(),
                invoice.getBonusConsultantApprovedStatus(),
                invoice.getUuid());
    }

    @Transactional
    public Invoice createPhantomInvoice(Invoice draftInvoice) throws JsonProcessingException {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        recalculateInvoiceItems(draftInvoice);
        bonusService.recalcForInvoice(draftInvoice.getUuid());
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
    }

    @Transactional
    public Invoice createCreditNote(Invoice invoice) {
        // Validate no existing credit note for this invoice
        if (Invoice.find("creditnoteForUuid = ?1", invoice.getUuid()).count() > 0) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("A credit note already exists for this invoice.")
                            .build()
            );
        }

        Invoice creditNote = new Invoice(invoice.getUuid(), invoice.getInvoicenumber(), InvoiceType.CREDIT_NOTE, invoice.getContractuuid(), invoice.getProjectuuid(),
                invoice.getProjectname(), invoice.getDiscount(), invoice.getYear(), invoice.getMonth(), invoice.getClientname(),
                invoice.getClientaddresse(), invoice.getOtheraddressinfo(), invoice.getZipcity(),
                invoice.getEan(), invoice.getCvr(), invoice.getAttention(), LocalDate.now(), LocalDate.now().plusMonths(1),
                invoice.getProjectref(), invoice.getContractref(), invoice.contractType, invoice.getCompany(), invoice.getCurrency(), invoice.getVat(),
                "Kreditnota til faktura " + StringUtils.convertInvoiceNumberToString(invoice.invoicenumber), invoice.getBonusConsultant(), invoice.getBonusConsultantApprovedStatus());

        creditNote.invoicenumber = 0;
        creditNote.setCreditnoteForUuid(invoice.getUuid());

        for (InvoiceItem invoiceitem : invoice.invoiceitems) {
            creditNote.getInvoiceitems().add(new InvoiceItem(invoiceitem.consultantuuid, invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), invoiceitem.getPosition(), creditNote.uuid));
        }
        try {
            Invoice.persist(creditNote);
            creditNote.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
        } catch (Exception e) {
            // Map unique index violation to 409 Conflict to prevent duplicates in concurrent requests
            if (e.getCause() != null && e.getCause().getMessage() != null && e.getCause().getMessage().contains("ux_invoices_creditnote_for_uuid")) {
                throw new WebApplicationException(
                        Response.status(Response.Status.CONFLICT)
                                .entity("A credit note already exists for this invoice.")
                                .build()
                );
            }
            throw e;
        }
        return creditNote;
    }

    @Transactional
    public void createInternalInvoiceDraft(String companyuuid, Invoice invoice) {
        Invoice internalInvoice = new Invoice(
                invoice.getUuid(),
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
                LocalDate.now().plusMonths(1),
                invoice.getProjectref(),
                invoice.getContractref(),
                invoice.contractType,
                Company.findById(companyuuid),
                invoice.getCurrency(),
                "Intern faktura knyttet til " + invoice.getInvoicenumber(),
                invoice.getCompany().getUuid());
        int position = 1;
        for (InvoiceItem invoiceitem : invoice.getInvoiceitems()) {
            if(invoiceitem.getRate() == 0.0 || invoiceitem.hours == 0.0) continue;
            internalInvoice.getInvoiceitems().add(new InvoiceItem(invoiceitem.consultantuuid, invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), position++, invoice.uuid));
        }
        Invoice.persist(internalInvoice);
        internalInvoice.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
    }


    @Transactional
    public void createInternalServiceInvoiceDraft(String fromCompanyuuid, String toCompanyuuid, LocalDate month) {
        // Month boundaries
        final LocalDate from = month.withDayOfMonth(1);
        final LocalDate to   = from.plusMonths(1);

        // Load the common month context used by distribution
        final IntercompanyCalcService.MonthData md =
                calcService.loadMonthData(from, to, 1.02 /* same multiplier as distribution */);

        final Company fromCompany = Company.findById(fromCompanyuuid); // sender (receiver of money)
        final Company toCompany   = Company.findById(toCompanyuuid);   // payer (client on the invoice)

        // Use the same lumps and the same staff pool semantics as distribution
        final Map<String, BigDecimal> lumpsByAccount = calcService.lumpsMonthRange(from, to);
        final Map<String, BigDecimal> staffRemaining = new HashMap<>(md.staffBaseBI102);

        // For grouping invoice lines per parent category
        final Map<String, BigDecimal> amountByCategory = new LinkedHashMap<>();
        final Map<String, String>     catNameByCode    = new HashMap<>();

        // Same rounding policy as distribution
        final int SCALE = IntercompanyCalcService.SCALE;
        final RoundingMode RM = IntercompanyCalcService.RM;

        // Build using the *same* distribution logic per account
        for (AccountingCategory catSrc : md.categories) {
            final String catCode = catSrc.getAccountCode();
            final String catName = catSrc.getAccountname();
            catNameByCode.put(catCode, catName);

            for (AccountingAccount aa : catSrc.getAccounts()) {
                // Only origin = sender company
                if (!aa.getCompany().equals(fromCompany)) continue;

                // Invoiceable here = shared OR salary (matches distribution)
                if (!aa.isShared() && !aa.isSalary()) continue;

                // GL for the whole month (range), same as UI
                BigDecimal gl = md.glByCompanyAccountRange
                        .getOrDefault(fromCompanyuuid, Collections.emptyMap())
                        .getOrDefault(aa.getAccountCode(), BigDecimal.ZERO);

                // Lumps for the whole month, negatives clamped (same as UI)
                BigDecimal lump = lumpsByAccount.getOrDefault(aa.getUuid(), BigDecimal.ZERO);

                // Let the shared engine compute the split/cap for this account
                IntercompanyCalcService.ShareAmounts share =
                        calcService.computeDistributionLegacyShareForAccount(
                                md, aa, fromCompanyuuid, gl, lump, staffRemaining);

                // Amount owed by the payer (toCompany) on this account:
                BigDecimal rTo = md.ratioByCompany.getOrDefault(toCompanyuuid, BigDecimal.ZERO);
                BigDecimal owed = share.baseToShare.multiply(rTo).setScale(SCALE, RM);

                if (owed.compareTo(BigDecimal.ZERO) > 0) {
                    amountByCategory.merge(catCode, owed, BigDecimal::add);
                }
            }
        }

        // Create the invoice shell
        Invoice invoice = new Invoice(
                InvoiceType.INTERNAL_SERVICE,
                "", "", "",
                0.0, month.getYear(), month.getMonthValue(),
                toCompany.getName(), toCompany.getAddress(), "",
                toCompany.getZipcode(), "", toCompany.getCvr(), "Tobias Kjølsen",
                LocalDate.of(2025, 6, 30),
                LocalDate.of(2025, 7, 30),
                //month.plusMonths(1).withDayOfMonth(1).minusDays(1),
                //month.plusMonths(1).withDayOfMonth(1).minusDays(1).plusMonths(1),
                "", "", ContractType.PERIOD, fromCompany, "DKK",
                "Intern faktura knyttet til " + month.getMonth().name() + " " + month.getYear() + " fra " + fromCompany.getName() + " til " + toCompany.getName());

        invoice.persistAndFlush();

        // Add one invoice item per parent category (like before)
        amountByCategory.forEach((catCode, amt) -> {
            if (amt.compareTo(BigDecimal.ZERO) <= 0) return;
            String catName = catNameByCode.getOrDefault(catCode, catCode);
            // rate = amount, hours = 1  (your existing convention)
            InvoiceItem item = new InvoiceItem(catName, "", amt.doubleValue(), 1, invoice.getUuid());
            invoice.getInvoiceitems().add(item);
            item.persist();
        });
    }


    public List<Invoice> findInternalServiceInvoicesByMonth(String month) {
        LocalDate localDate = DateUtils.dateIt(month).withDayOfMonth(1);
        return Invoice.find("type = ?1 and year = ?2 AND month = ?3 ", InvoiceType.INTERNAL_SERVICE, localDate.getYear(), localDate.getMonthValue()).list();
    }

    public List<Invoice> findInternalServicesPaged(int pageIdx, int pageSize, List<String> sortParams) {
        Sort sort = SortBuilder.from(sortParams);
        Page page = Page.of(pageIdx, pageSize);
        PanacheQuery<Invoice> q = Invoice.find("type = ?1", sort, InvoiceType.INTERNAL_SERVICE);
        return q.page(page).list();
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

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateInvoiceReference(String invoiceuuid, InvoiceReference invoiceReference) {
        // Only perform the update if something actually changes to keep transactions short and avoid extra flushes
        Invoice.update("bookingdate = ?1, referencenumber = ?2 WHERE uuid like ?3 AND (bookingdate <> ?1 OR referencenumber <> ?2 OR bookingdate IS NULL OR referencenumber IS NULL)",
                invoiceReference.getBookingdate(), invoiceReference.getReferencenumber(), invoiceuuid);
    }

    @Transactional
    public void uploadToEconomics(Invoice invoice) {
        if (invoice.invoicenumber == 0) return;
        // Prevent double upload if already uploaded/booked/paid
        if (invoice.getEconomicsStatus() == EconomicsInvoiceStatus.UPLOADED
                || invoice.getEconomicsStatus() == EconomicsInvoiceStatus.BOOKED
                || invoice.getEconomicsStatus() == EconomicsInvoiceStatus.PAID) {
            return;
        }
        try {
            Response r = economicsInvoiceService.sendVoucher(invoice);

            // Persist voucher number if it was assigned by Economics (voucher posted)
            if (invoice.getEconomicsVoucherNumber() > 0) {
                Invoice.update("economicsVoucherNumber = ?1 WHERE uuid like ?2", invoice.getEconomicsVoucherNumber(), invoice.getUuid());
                // Mark as UPLOADED as soon as voucher exists in e-conomics (robust against attachment failures)
                Invoice.update("economicsStatus = ?1 WHERE uuid like ?2", EconomicsInvoiceStatus.UPLOADED, invoice.getUuid());
            } else if (r != null && r.getStatus() >= 200 && r.getStatus() < 300) {
                // Fallback: if we don’t have voucherNumber but file upload response is 2xx, still mark UPLOADED
                Invoice.update("economicsStatus = ?1 WHERE uuid like ?2", EconomicsInvoiceStatus.UPLOADED, invoice.getUuid());
            }
        } catch (Exception e) {
            // If voucher was created in e-conomics but an error occurred afterwards (e.g., attachment),
            // persist the voucher number and mark as UPLOADED to keep systems in sync.
            if (invoice.getEconomicsVoucherNumber() > 0) {
                Invoice.update("economicsVoucherNumber = ?1 WHERE uuid like ?2", invoice.getEconomicsVoucherNumber(), invoice.getUuid());
                Invoice.update("economicsStatus = ?1 WHERE uuid like ?2", EconomicsInvoiceStatus.UPLOADED, invoice.getUuid());
                // Do not rethrow to avoid rolling back DB changes when the external side-effect succeeded
                return;
            }
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

    public long countBonusApproval(List<InvoiceStatus> statuses) {
        // Only invoices with at least one bonus row
        String jpql = """
        SELECT COUNT(i) FROM Invoice i
        WHERE i.status IN :st
          AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                      WHERE b.invoiceuuid = i.uuid)
    """;
        return em.createQuery(jpql, Long.class)
                .setParameter("st", statuses)
                .getSingleResult();
    }

    // ADD this helper to parse statuses if caller passed none
    private static List<InvoiceStatus> defaultStatuses(List<InvoiceStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of(InvoiceStatus.CREATED, InvoiceStatus.SUBMITTED, InvoiceStatus.CREDIT_NOTE);
        }
        return statuses;
    }

    public List<BonusApprovalRow> findBonusApprovalPage(List<InvoiceStatus> rawStatuses,
                                                        int pageIdx, int pageSize) {

        var statuses = defaultStatuses(rawStatuses);

        // 1) page of invoices that have at least one bonus row
        String baseJpql = """
        SELECT i FROM Invoice i
        WHERE i.status IN :st
          AND EXISTS (SELECT 1 FROM InvoiceBonus b
                      WHERE b.invoiceuuid = i.uuid)
        ORDER BY i.invoicedate DESC, i.invoicenumber DESC
    """;
        var q = em.createQuery(baseJpql, Invoice.class)
                .setParameter("st", statuses)
                .setFirstResult(pageIdx * pageSize)
                .setMaxResults(pageSize);

        List<Invoice> page = q.getResultList();
        if (page.isEmpty()) return List.of();

        // Collect invoice ids once
        var ids = page.stream().map(Invoice::getUuid).toList();

        // 2) Amount (excl. VAT) = SUM(hours * rate) over *persisted* items (incl. CALCULATED)
        Map<String, Double> amountByInvoice = sumAmountNoTaxByInvoice(ids);

        // 3) Bonus aggregates (sum + aggregated status)
        var bonusSumByInvoice = new HashMap<String, Double>();
        var statusAggByInvoice = new HashMap<String, SalesApprovalStatus>();
        {
            String qBonus = """
            SELECT b.invoiceuuid, b.status, b.computedAmount
            FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
            WHERE b.invoiceuuid IN :ids
        """;
            var rows = em.createQuery(qBonus, Object[].class)
                    .setParameter("ids", ids)
                    .getResultList();

            var tmp = new HashMap<String, List<InvoiceBonus>>();
            for (Object[] r : rows) {
                var ib = new InvoiceBonus();
                ib.setInvoiceuuid((String) r[0]);
                ib.setStatus((SalesApprovalStatus) r[1]);
                ib.setComputedAmount(((Number) r[2]).doubleValue());
                tmp.computeIfAbsent(ib.getInvoiceuuid(), k -> new ArrayList<>()).add(ib);
            }
            for (var e : tmp.entrySet()) {
                var list = e.getValue();
                double sum = list.stream().mapToDouble(InvoiceBonus::getComputedAmount).sum();
                boolean hasPending  = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.PENDING);
                boolean hasRejected = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.REJECTED);
                boolean hasApproved = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.APPROVED);
                SalesApprovalStatus agg = hasPending ? SalesApprovalStatus.PENDING
                        : (hasRejected ? SalesApprovalStatus.REJECTED
                        : (hasApproved ? SalesApprovalStatus.APPROVED : SalesApprovalStatus.PENDING));
                bonusSumByInvoice.put(e.getKey(), sum);
                statusAggByInvoice.put(e.getKey(), agg);
            }
        }

        // 4) Users per invoice
        Map<String, Set<String>> usersByInvoice = new HashMap<>();
        Set<String> allUserIds = new HashSet<>();
        {
            String qUsers = """
            SELECT ii.invoiceuuid, ii.consultantuuid
            FROM InvoiceItem ii
            WHERE ii.invoiceuuid IN :ids AND ii.consultantuuid IS NOT NULL
        """;
            var rs = em.createQuery(qUsers, Object[].class)
                    .setParameter("ids", ids)
                    .getResultList();
            for (Object[] r : rs) {
                String invId = (String) r[0];
                String userId = (String) r[1];
                if (userId == null || userId.isBlank()) continue;
                usersByInvoice.computeIfAbsent(invId, k -> new LinkedHashSet<>()).add(userId);
                allUserIds.add(userId);
            }
        }

        // 5) Load users (once), hydrate statuses, build map
        List<User> users = allUserIds.isEmpty() ? List.of()
                : User.<User>list("uuid in ?1", allUserIds);
        users.forEach(UserService::addChildrenToUser);
        Map<String, User> userById = new HashMap<>();
        users.forEach(u -> userById.put(u.getUuid(), u));

        // 6) Build rows, using precomputed SUM instead of PricingEngine
        List<BonusApprovalRow> rows = new ArrayList<>(page.size());
        for (Invoice i : page) {
            double amountNoTax = amountByInvoice.getOrDefault(i.getUuid(), 0.0);
            Set<Company> companies = new LinkedHashSet<>();

            for (String uid : usersByInvoice.getOrDefault(i.getUuid(), Set.of())) {
                User u = userById.get(uid);
                if (u == null) continue;
                UserStatus st = userService.getUserStatus(u, i.getInvoicedate());
                if (st != null && st.getCompany() != null) companies.add(st.getCompany());
            }

            rows.add(new BonusApprovalRow(
                    i.getUuid(),
                    i.getInvoicenumber(),
                    i.getInvoicedate(),
                    i.getCurrency(),
                    i.getClientname(),
                    amountNoTax,
                    statusAggByInvoice.getOrDefault(i.getUuid(), SalesApprovalStatus.PENDING),
                    bonusSumByInvoice.getOrDefault(i.getUuid(), 0.0),
                    new ArrayList<>(companies)
            ));
        }
        return rows;
    }

    /** Count invoices (within default invoice lifecycle set) filtered by aggregated bonus status. */
    public long countBonusApprovalByBonusStatus(List<SalesApprovalStatus> bonusStatuses) {
        var invStatuses = defaultStatuses(List.of()); // -> CREATED, SUBMITTED, PAID, CREDIT_NOTE
        String aggWhere = buildAggregatedStatusWhere(bonusStatuses);
        String jpql = """
            SELECT COUNT(i) FROM Invoice i
            WHERE i.status IN :st
              """ + aggWhere;

        var q = em.createQuery(jpql, Long.class)
                .setParameter("st", invStatuses)
                .setParameter("P", SalesApprovalStatus.PENDING)
                .setParameter("R", SalesApprovalStatus.REJECTED)
                .setParameter("A", SalesApprovalStatus.APPROVED);

        return q.getSingleResult();
    }

    public List<BonusApprovalRow> findBonusApprovalPageByBonusStatus(List<SalesApprovalStatus> bonusStatuses,
                                                                     int pageIdx, int pageSize) {

        var invStatuses = defaultStatuses(List.of());
        String aggWhere = buildAggregatedStatusWhere(bonusStatuses);

        String baseJpql = """
        SELECT i FROM Invoice i
        WHERE i.status IN :st
          """ + aggWhere + """
        ORDER BY i.invoicedate DESC, i.invoicenumber DESC
    """;

        var q = em.createQuery(baseJpql, Invoice.class)
                .setParameter("st", invStatuses)
                .setParameter("P", SalesApprovalStatus.PENDING)
                .setParameter("R", SalesApprovalStatus.REJECTED)
                .setParameter("A", SalesApprovalStatus.APPROVED)
                .setFirstResult(pageIdx * pageSize)
                .setMaxResults(pageSize);

        List<Invoice> page = q.getResultList();
        if (page.isEmpty()) return List.of();

        var ids = page.stream().map(Invoice::getUuid).toList();

        // Reuse fast SUM aggregation
        Map<String, Double> amountByInvoice = sumAmountNoTaxByInvoice(ids);

        var bonusSumByInvoice = new HashMap<String, Double>();
        var statusAggByInvoice = new HashMap<String, SalesApprovalStatus>();
        {
            String qBonus = """
            SELECT b.invoiceuuid, b.status, b.computedAmount
            FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
            WHERE b.invoiceuuid IN :ids
        """;
            var rows = em.createQuery(qBonus, Object[].class)
                    .setParameter("ids", ids)
                    .getResultList();

            var tmp = new HashMap<String, List<dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus>>();
            for (Object[] r : rows) {
                var ib = new dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus();
                ib.setInvoiceuuid((String) r[0]);
                ib.setStatus((SalesApprovalStatus) r[1]);
                ib.setComputedAmount(((Number) r[2]).doubleValue());
                tmp.computeIfAbsent(ib.getInvoiceuuid(), k -> new ArrayList<>()).add(ib);
            }
            for (var e : tmp.entrySet()) {
                var list = e.getValue();
                double sum = list.stream().mapToDouble(InvoiceBonus::getComputedAmount).sum();
                boolean hasPending  = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.PENDING);
                boolean hasRejected = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.REJECTED);
                boolean hasApproved = list.stream().anyMatch(b -> b.getStatus() == SalesApprovalStatus.APPROVED);
                SalesApprovalStatus agg = hasPending ? SalesApprovalStatus.PENDING
                        : (hasRejected ? SalesApprovalStatus.REJECTED
                        : (hasApproved ? SalesApprovalStatus.APPROVED : SalesApprovalStatus.PENDING));
                bonusSumByInvoice.put(e.getKey(), sum);
                statusAggByInvoice.put(e.getKey(), agg);
            }
        }

        Map<String, Set<String>> usersByInvoice = new HashMap<>();
        Set<String> allUserIds = new HashSet<>();
        {
            String qUsers = """
            SELECT ii.invoiceuuid, ii.consultantuuid
            FROM InvoiceItem ii
            WHERE ii.invoiceuuid IN :ids AND ii.consultantuuid IS NOT NULL
        """;
            var rs = em.createQuery(qUsers, Object[].class)
                    .setParameter("ids", ids)
                    .getResultList();
            for (Object[] r : rs) {
                String invId = (String) r[0];
                String userId = (String) r[1];
                if (userId == null || userId.isBlank()) continue;
                usersByInvoice.computeIfAbsent(invId, k -> new LinkedHashSet<>()).add(userId);
                allUserIds.add(userId);
            }
        }

        List<User> users = allUserIds.isEmpty() ? List.of()
                : User.<User>list("uuid in ?1", allUserIds);
        users.forEach(UserService::addChildrenToUser);
        Map<String, User> userById = new HashMap<>();
        users.forEach(u -> userById.put(u.getUuid(), u));

        List<BonusApprovalRow> rows = new ArrayList<>(page.size());
        for (Invoice i : page) {
            double amountNoTax = amountByInvoice.getOrDefault(i.getUuid(), 0.0);
            Set<Company> companies = new LinkedHashSet<>();
            for (String uid : usersByInvoice.getOrDefault(i.getUuid(), Set.of())) {
                User u = userById.get(uid);
                if (u == null) continue;
                UserStatus st = userService.getUserStatus(u, i.getInvoicedate());
                if (st != null && st.getCompany() != null) companies.add(st.getCompany());
            }
            rows.add(new BonusApprovalRow(
                    i.getUuid(),
                    i.getInvoicenumber(),
                    i.getInvoicedate(),
                    i.getCurrency(),
                    i.getClientname(),
                    amountNoTax,
                    statusAggByInvoice.getOrDefault(i.getUuid(), SalesApprovalStatus.PENDING),
                    bonusSumByInvoice.getOrDefault(i.getUuid(), 0.0),
                    new ArrayList<>(companies)
            ));
        }
        return rows;
    }

    // --- ADD THIS SMALL HELPER (in the same class) ---
    private Map<String, Double> sumAmountNoTaxByInvoice(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        var rows = em.createQuery("""
            SELECT ii.invoiceuuid, SUM(ii.rate * ii.hours)
            FROM InvoiceItem ii
            WHERE ii.invoiceuuid IN :ids
            GROUP BY ii.invoiceuuid
        """, Object[].class)
                .setParameter("ids", ids)
                .getResultList();
        Map<String, Double> out = new HashMap<>();
        for (Object[] r : rows) {
            out.put((String) r[0], ((Number) r[1]).doubleValue());
        }
        return out;
    }

    /**
     * Builds the JPQL predicate for aggregated bonus status.
     * Aggregation rules:
     *  - PENDING   : exists any PENDING
     *  - REJECTED  : no PENDING and exists any REJECTED
     *  - APPROVED  : no PENDING and no REJECTED and exists any APPROVED
     *
     * If list is null/empty -> accept any invoice that has bonus rows.
     */
    private static String buildAggregatedStatusWhere(List<SalesApprovalStatus> agg) {
        if (agg == null || agg.isEmpty()) {
            return "AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b WHERE b.invoiceuuid = i.uuid) ";
        }
        boolean wantP = agg.contains(SalesApprovalStatus.PENDING);
        boolean wantR = agg.contains(SalesApprovalStatus.REJECTED);
        boolean wantA = agg.contains(SalesApprovalStatus.APPROVED);

        List<String> parts = new ArrayList<>();
        if (wantP) {
            parts.add("""
                EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b1
                        WHERE b1.invoiceuuid = i.uuid AND b1.status = :P)
            """);
        }
        if (wantR) {
            parts.add("""
                NOT EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus bp
                            WHERE bp.invoiceuuid = i.uuid AND bp.status = :P)
                AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus br
                            WHERE br.invoiceuuid = i.uuid AND br.status = :R)
            """);
        }
        if (wantA) {
            parts.add("""
                NOT EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus bp
                            WHERE bp.invoiceuuid = i.uuid AND bp.status = :P)
                AND NOT EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus br
                                WHERE br.invoiceuuid = i.uuid AND br.status = :R)
                AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus ba
                            WHERE ba.invoiceuuid = i.uuid AND ba.status = :A)
            """);
        }

        if (parts.isEmpty()) {
            return "AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b WHERE b.invoiceuuid = i.uuid) ";
        }
        return "AND (" + String.join(" OR ", parts) + ") ";
    }

    public List<MyBonusRow> findMyBonusPage(String useruuid,
                                            List<SalesApprovalStatus> statuses,
                                            LocalDate from, LocalDate to,
                                            int pageIdx, int pageSize) {
        String base = """
        FROM Invoice i, dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
        WHERE b.invoiceuuid = i.uuid AND b.useruuid = :user
    """;
        if (statuses != null && !statuses.isEmpty()) base += " AND b.status IN :st ";
        if (from != null) base += " AND i.invoicedate >= :from ";
        if (to   != null) base += " AND i.invoicedate <  :to ";

        String select = "SELECT i.uuid, i.invoicenumber, i.invoicedate, i.currency, i.clientname, i.type, " +
                "b.uuid, b.shareType, b.shareValue, b.computedAmount, b.status, b.approvedBy, b.approvedAt, b.createdAt, b.updatedAt, b.overrideNote " +
                base + " ORDER BY i.invoicedate DESC, i.invoicenumber DESC";

        var q = em.createQuery(select, Object[].class)
                .setParameter("user", useruuid)
                .setFirstResult(pageIdx * pageSize)
                .setMaxResults(pageSize);
        if (statuses != null && !statuses.isEmpty()) q.setParameter("st", statuses);
        if (from != null) q.setParameter("from", from);
        if (to != null) q.setParameter("to", to);

        var rs = q.getResultList();
        List<MyBonusRow> out = new ArrayList<>(rs.size());
        for (Object[] r : rs) {
            String invId = (String) r[0];
            String userId = (String) r[11]; // careful: adjust index if needed; safer to read explicitly below
            // safer: pull userId from bonus load instead:
            String bonusUuid = (String) r[6];
            var inv = Invoice.<Invoice>findById(invId);
            var bonus = InvoiceBonus.<InvoiceBonus>findById(bonusUuid);
            double original = computeDefaultOriginalForUser(inv, bonus.getUseruuid());

            out.add(new MyBonusRow(
                    (String) r[0], (Integer) r[1], (LocalDate) r[2], (String) r[3], (String) r[4], (InvoiceType) r[5],
                    bonusUuid,
                    (InvoiceBonus.ShareType) r[7], ((Number) r[8]).doubleValue(), ((Number) r[9]).doubleValue(),
                    (SalesApprovalStatus) r[10], (String) r[11], (LocalDateTime) r[12], (LocalDateTime) r[13],
                    (LocalDateTime) r[14], (String) r[15],
                    original
            ));
        }
        return out;
    }

    public long countMyBonus(String useruuid,
                             List<SalesApprovalStatus> statuses,
                             LocalDate from, LocalDate to) {
        String base = """
        FROM Invoice i, InvoiceBonus b
        WHERE b.invoiceuuid = i.uuid AND b.useruuid = :user
    """;
        if (statuses != null && !statuses.isEmpty()) base += " AND b.status IN :st ";
        if (from != null) base += " AND i.invoicedate >= :from ";
        if (to   != null) base += " AND i.invoicedate <  :to ";

        var q = em.createQuery("SELECT COUNT(b) " + base, Long.class)
                .setParameter("user", useruuid);
        if (statuses != null && !statuses.isEmpty()) q.setParameter("st", statuses);
        if (from != null) q.setParameter("from", from);
        if (to != null) q.setParameter("to", to);
        return q.getSingleResult();
    }

    public List<MyBonusFySum> myBonusFySummary(String useruuid) {
        String sel = """
        SELECT i.uuid, i.invoicedate, b.status, b.computedAmount, b.useruuid
        FROM Invoice i, dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
        WHERE b.invoiceuuid = i.uuid AND b.useruuid = :user
    """;
        var rows = em.createQuery(sel, Object[].class)
                .setParameter("user", useruuid)
                .getResultList();

        Map<LocalDate, double[]> acc = new HashMap<>(); // fyStart -> [approved, pending]
        for (Object[] r : rows) {
            String invoiceId = (String) r[0];
            LocalDate d = (LocalDate) r[1];
            SalesApprovalStatus st = (SalesApprovalStatus) r[2];
            double approvedAmt = ((Number) r[3]).doubleValue();
            String userId = (String) r[4];

            dk.trustworks.intranet.aggregates.invoice.model.Invoice inv =
                    dk.trustworks.intranet.aggregates.invoice.model.Invoice.findById(invoiceId);

            double engineOriginalForUser = computeDefaultOriginalForUser(inv, userId);

            LocalDate fyStart = fiscalYearStart(d);
            var a = acc.computeIfAbsent(fyStart, k -> new double[2]);
            if (st == SalesApprovalStatus.APPROVED) {
                a[0] += approvedAmt;                    // approved
            } else if (st == SalesApprovalStatus.PENDING) {
                a[1] += engineOriginalForUser;          // pending = engine original (exclude REJECTED)
            }
        }
        return acc.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new MyBonusFySum(e.getKey(),
                        round2(e.getValue()[0]),                // approved
                        round2(e.getValue()[0] + e.getValue()[1]) // total = approved + pending
                ))
                .toList();
    }

    /** Default user "original": 100% of non-self BASE lines + pro‑rata of CALCULATED lines. */
    private static double computeDefaultOriginalForUser(Invoice inv, String useruuid) {
        if (inv == null) return 0.0;
        double baseTotal = inv.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() != InvoiceItemOrigin.CALCULATED)
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();

        double baseSelected = inv.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() != InvoiceItemOrigin.CALCULATED)
                .filter(ii -> !Objects.equals(ii.getConsultantuuid(), useruuid)) // not own lines => 100%
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();

        double syntheticTotal = inv.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() == InvoiceItemOrigin.CALCULATED)
                .mapToDouble(ii -> ii.getHours() * ii.getRate())
                .sum();

        double ratio = baseTotal == 0.0 ? 0.0 : baseSelected / baseTotal;
        double amount = baseSelected + ratio * syntheticTotal;
        if (inv.getType() == InvoiceType.CREDIT_NOTE) amount = -amount;
        return round2(amount);
    }


    private static LocalDate fiscalYearStart(LocalDate d) {
        int y = (d.getMonthValue() >= 7) ? d.getYear() : d.getYear() - 1;
        return LocalDate.of(y, 7, 1);
    }
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    /**
     * Resolves the client name for a given invoice by following the reference chain
     * Invoice.contractuuid -> Contract.clientuuid -> Client.name.
     * If the contract or client cannot be found (or names are blank), the method
     * safely falls back to the snapshot name stored on the invoice itself.
     *
     * Note: This method is used when building SimpleInvoiceDTO for client invoices
     * to ensure the freshest canonical client name is returned.
     *
     * @param invoice the invoice whose client name should be resolved
     * @return the client name from Client entity, or invoice.clientname as fallback
     */
    private String resolveClientName(Invoice invoice) {
        if (invoice == null) return null;
        try {
            String contractUuid = invoice.getContractuuid();
            if (contractUuid != null && !contractUuid.isBlank()) {
                Contract contract = Contract.findById(contractUuid);
                if (contract != null) {
                    String clientUuid = contract.getClientuuid();
                    if (clientUuid != null && !clientUuid.isBlank()) {
                        Client client = Client.findById(clientUuid);
                        if (client != null && client.getName() != null && !client.getName().isBlank()) {
                            return client.getName();
                        }
                    }
                }
            }
        } catch (Exception ignore) {
            // Fall back to invoice snapshot if anything fails
        }
        return invoice.getClientname();
    }

    /**
     * Queues an INTERNAL DRAFT invoice to be automatically created when the referenced invoice is PAID.
     *
     * Validation:
     * - Invoice must be DRAFT status
     * - Invoice must be INTERNAL type
     * - Invoice must have valid invoice_ref (references an existing invoice)
     * - Invoice must have valid debtorCompanyuuid
     * - Debtor company must have internal-journal-number configured
     *
     * @param invoiceuuid UUID of the invoice to queue
     * @throws WebApplicationException if validation fails
     */
    @Transactional
    public void queueInternalInvoice(String invoiceuuid) {
        log.info("Queueing internal invoice: " + invoiceuuid);

        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            throw new WebApplicationException("Invoice not found: " + invoiceuuid, Response.Status.NOT_FOUND);
        }

        // Validation 1: Must be DRAFT
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new WebApplicationException(
                "Invoice must be DRAFT status to queue. Current status: " + invoice.getStatus(),
                Response.Status.BAD_REQUEST
            );
        }

        // Validation 2: Must be INTERNAL type
        if (invoice.getType() != InvoiceType.INTERNAL) {
            throw new WebApplicationException(
                "Only INTERNAL invoices can be queued. Current type: " + invoice.getType(),
                Response.Status.BAD_REQUEST
            );
        }

        // Validation 3: Must have valid invoice_ref_uuid
        if (invoice.getInvoiceRefUuid() == null || invoice.getInvoiceRefUuid().isBlank()) {
            throw new WebApplicationException(
                "Invoice must reference a client invoice (invoice_ref_uuid must be set)",
                Response.Status.BAD_REQUEST
            );
        }

        // Check that referenced client invoice exists by UUID
        Invoice referencedInvoice = Invoice.findById(invoice.getInvoiceRefUuid());
        if (referencedInvoice == null) {
            throw new WebApplicationException(
                "Referenced client invoice not found (uuid): " + invoice.getInvoiceRefUuid(),
                Response.Status.BAD_REQUEST
            );
        }

        // Validation 4: Must have debtor company
        if (invoice.getDebtorCompanyuuid() == null || invoice.getDebtorCompanyuuid().isBlank()) {
            throw new WebApplicationException(
                "Invoice must specify debtor company (debtorCompanyuuid)",
                Response.Status.BAD_REQUEST
            );
        }

        Company debtorCompany = Company.findById(invoice.getDebtorCompanyuuid());
        if (debtorCompany == null) {
            throw new WebApplicationException(
                "Debtor company not found: " + invoice.getDebtorCompanyuuid(),
                Response.Status.BAD_REQUEST
            );
        }

        // Validation 5: Debtor company must have internal-journal-number configured
        try {
            IntegrationKey.IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(debtorCompany);
            if (keys.internalJournalNumber() <= 0) {
                throw new WebApplicationException(
                    "Debtor company " + debtorCompany.getName() + " does not have internal-journal-number configured",
                    Response.Status.BAD_REQUEST
                );
            }
        } catch (Exception e) {
            throw new WebApplicationException(
                "Failed to validate debtor company integration keys: " + e.getMessage(),
                Response.Status.BAD_REQUEST
            );
        }

        // Additional validation: ensure there is no existing non-DRAFT INTERNAL invoice for this client invoice
        long existing = Invoice.count("type = ?1 and status in ?2 and invoiceRefUuid = ?3",
                InvoiceType.INTERNAL,
                java.util.List.of(InvoiceStatus.QUEUED, InvoiceStatus.CREATED),
                invoice.getInvoiceRefUuid());
        if (existing > 0) {
            throw new WebApplicationException(
                    "An INTERNAL invoice already exists (QUEUED or CREATED) for client invoice_ref_uuid=" + invoice.getInvoiceRefUuid(),
                    Response.Status.CONFLICT
            );
        }

        // All validations passed - queue the invoice
        invoice.setStatus(InvoiceStatus.QUEUED);
        invoice.persist();

        log.info("Invoice successfully queued: " + invoiceuuid +
                 ", references client invoice uuid: " + invoice.getInvoiceRefUuid() +
                 ", debtor company: " + debtorCompany.getName());
    }

    /**
     * Creates a queued internal invoice without uploading to e-conomics.
     * Upload is handled separately by InvoiceEconomicsUploadService for robust retry support.
     *
     * @param queuedInvoice Invoice in QUEUED status
     * @return Created invoice
     * @throws JsonProcessingException if PDF generation fails
     */
    @Transactional
    public Invoice createQueuedInvoiceWithoutUpload(Invoice queuedInvoice) throws JsonProcessingException {
        log.info("Creating queued invoice (without upload): " + queuedInvoice.getUuid());

        if (queuedInvoice.getStatus() != InvoiceStatus.QUEUED) {
            throw new RuntimeException("Invoice is not queued: " + queuedInvoice.getUuid());
        }

        // Assign invoice number
        queuedInvoice.invoicenumber = getMaxInvoiceNumber(queuedInvoice) + 1;

        // Create PDF
        queuedInvoice.setStatus(InvoiceStatus.CREATED);
        queuedInvoice.pdf = createInvoicePdf(queuedInvoice);

        log.debug("Saving queued invoice...");
        saveInvoice(queuedInvoice);
        log.debug("Invoice saved: " + queuedInvoice.getUuid());

        return queuedInvoice;
    }

    // Single-row BonusApprovalRow DTO for one invoice
    public BonusApprovalRow findBonusApprovalRow(String invoiceuuid) {
        Invoice i = Invoice.findById(invoiceuuid);
        if (i == null) throw new WebApplicationException(Response.Status.NOT_FOUND);

        // Amount excl. VAT (fast aggregation like paged version)
        Map<String, Double> amountByInvoice = sumAmountNoTaxByInvoice(java.util.List.of(invoiceuuid));
        double amountNoTax = amountByInvoice.getOrDefault(invoiceuuid, 0.0);

        // Bonus aggregates
        double total = 0.0;
        boolean hasPending = false, hasRejected = false, hasApproved = false;
        {
            String qBonus = """
                SELECT b.status, b.computedAmount
                FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                WHERE b.invoiceuuid = :id
            """;
            var rows = em.createQuery(qBonus, Object[].class)
                    .setParameter("id", invoiceuuid)
                    .getResultList();
            for (Object[] r : rows) {
                SalesApprovalStatus st = (SalesApprovalStatus) r[0];
                double amt = ((Number) r[1]).doubleValue();
                total += amt;
                if (st == SalesApprovalStatus.PENDING) hasPending = true;
                else if (st == SalesApprovalStatus.REJECTED) hasRejected = true;
                else if (st == SalesApprovalStatus.APPROVED) hasApproved = true;
            }
        }
        SalesApprovalStatus agg = hasPending ? SalesApprovalStatus.PENDING
                : (hasRejected ? SalesApprovalStatus.REJECTED
                : (hasApproved ? SalesApprovalStatus.APPROVED : SalesApprovalStatus.PENDING));

        // Companies derived from users on items
        Set<String> userIds = new LinkedHashSet<>();
        {
            String qUsers = """
                SELECT ii.consultantuuid
                FROM InvoiceItem ii
                WHERE ii.invoiceuuid = :id AND ii.consultantuuid IS NOT NULL
            """;
            var rs = em.createQuery(qUsers, String.class)
                    .setParameter("id", invoiceuuid)
                    .getResultList();
            for (String uid : rs) if (uid != null && !uid.isBlank()) userIds.add(uid);
        }
        java.util.List<User> users = userIds.isEmpty() ? java.util.List.of()
                : User.<User>list("uuid in ?1", userIds);
        users.forEach(UserService::addChildrenToUser);
        java.util.Set<Company> companies = new java.util.LinkedHashSet<>();
        for (User u : users) {
            var st = userService.getUserStatus(u, i.getInvoicedate());
            if (st != null && st.getCompany() != null) companies.add(st.getCompany());
        }

        return new BonusApprovalRow(
                i.getUuid(),
                i.getInvoicenumber(),
                i.getInvoicedate(),
                i.getCurrency(),
                i.getClientname(),
                amountNoTax,
                agg,
                total,
                new java.util.ArrayList<>(companies)
        );
    }

    /**
     * Find invoices where the invoice is issued by one company but contains invoice items
     * with consultants from another company. This is determined by checking the consultant's
     * latest userstatus on or before the invoice date ("as-of"), and comparing the
     * consultant company to the issuing invoice company.
     *
     * Implementation notes:
     * - Uses MariaDB 11 window functions to pick the latest status per (invoice, consultant)
     *   efficiently, avoiding correlated subqueries.
     * - Date bounds follow the existing semantics: fromdate inclusive, todate exclusive.
     * - Requires indexes: invoices(invoicedate), invoiceitems(invoiceuuid, consultantuuid), userstatus(useruuid, statusdate).
     *
     * @param fromdate Start date (inclusive) for invoice date range
     * @param todate End date (exclusive) for invoice date range
     * @return List of invoices with cross-company consultants
     */
    @SuppressWarnings("unchecked")
    public List<Invoice> findCrossCompanyInvoicesByDateRange(LocalDate fromdate, LocalDate todate) {
        LocalDate finalFromdate = fromdate != null ? fromdate : LocalDate.of(2014, 1, 1);
        LocalDate finalTodate = todate != null ? todate : LocalDate.now();

        String sql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :fromdate
                  AND invoicedate <  :todate
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            )
            SELECT DISTINCT i.*
            FROM inv i
            JOIN status_pick sp 
              ON sp.invoiceuuid = i.uuid
            WHERE sp.rn = 1
              AND i.companyuuid IS NOT NULL
              AND sp.users_companyuuid IS NOT NULL
              AND sp.users_companyuuid <> i.companyuuid
            ORDER BY i.invoicedate DESC, i.invoicenumber DESC
        """;

        return em.createNativeQuery(sql, Invoice.class)
                .setParameter("fromdate", finalFromdate)
                .setParameter("todate", finalTodate)
                .getResultList();
    }



/**
 * Returns pairs of client invoices that contain cross-company consultant lines and an optional
 * referring internal invoice (if present).
 *
 * Algorithm (MariaDB 11 optimized):
 * - Phase 1 (SQL):
 *   * Select client invoices within date range (CREATED, type INVOICE) that have at least one cross-company
 *     consultant, determined by picking for each (invoice, consultant) the latest userstatus where statusdate
 *     <= invoicedate (window function ROW_NUMBER()).
 *   * For each such client invoice, pick at most one internal invoice (status in QUEUED or CREATED) referring via
 *     invoice_ref.
 * - Phase 2 (SQL):
 *   * For all involved invoices (client + internal), fetch lines and compute a per-line cross-company flag using
 *     the same "as-of" userstatus logic.
 * - Phase 3 (Java):
 *   * Assemble DTOs and compute totals (sum of hours*rate) before VAT.
 *
 * Date bounds: from inclusive, to exclusive.
 *
 * Note: Lines with consultantuuid = NULL (e.g., calculated fees/discounts) are marked crossCompany = false.
 *
 * @param fromdate Start date (inclusive)
 * @param todate End date (exclusive)
 * @return List of client/internal invoice pairs. Internal may be null when not present.
 */
public java.util.List<CrossCompanyInvoicePairDTO> findCrossCompanyInvoicesWithInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
    java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
    java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

    String pickSql = """
        WITH inv AS (
            SELECT *
            FROM invoices
            WHERE invoicedate >= :from
              AND invoicedate <  :to
              AND status = 'CREATED'
              AND type   = 'INVOICE'
        ),
        status_pick AS (
            SELECT 
                i.uuid AS invoiceuuid,
                i.companyuuid AS invoice_companyuuid,
                ii.consultantuuid,
                us.companyuuid AS users_companyuuid,
                ROW_NUMBER() OVER (
                    PARTITION BY i.uuid, ii.consultantuuid
                    ORDER BY us.statusdate DESC
                ) AS rn
            FROM inv i
            JOIN invoiceitems ii 
              ON ii.invoiceuuid = i.uuid
             AND ii.consultantuuid IS NOT NULL
            JOIN userstatus us 
              ON us.useruuid = ii.consultantuuid
             AND us.statusdate <= i.invoicedate
        ),
        cross_clients AS (
            SELECT DISTINCT i.*
            FROM inv i
            JOIN status_pick sp ON sp.invoiceuuid = i.uuid
            WHERE sp.rn = 1
              AND i.companyuuid IS NOT NULL
              AND sp.users_companyuuid IS NOT NULL
              AND sp.users_companyuuid <> i.companyuuid
        ),
        internal_pick AS (
            SELECT 
                ci.uuid AS client_uuid,
                ii.uuid AS internal_uuid,
                ROW_NUMBER() OVER (
                   PARTITION BY ci.uuid
                   ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                ) AS r
            FROM cross_clients ci
            JOIN invoices ii
              ON ii.invoice_ref_uuid = ci.uuid
             AND ii.type          = 'INTERNAL'
             AND ii.status IN ('QUEUED','CREATED')
        )
        SELECT 
            ci.uuid       AS client_uuid,
            COALESCE(ip.internal_uuid, NULL) AS internal_uuid
        FROM cross_clients ci
        LEFT JOIN internal_pick ip
          ON ip.client_uuid = ci.uuid AND ip.r = 1
        ORDER BY ci.invoicedate DESC, ci.invoicenumber DESC
    """;

    @SuppressWarnings("unchecked")
    java.util.List<Object[]> pairs = em.createNativeQuery(pickSql)
            .setParameter("from", from)
            .setParameter("to", to)
            .getResultList();

    if (pairs.isEmpty()) return java.util.List.of();

    java.util.LinkedHashSet<String> allInvoiceUuids = new java.util.LinkedHashSet<>();
    for (Object[] r : pairs) {
        String clientUuid = (String) r[0];
        String internalUuid = (String) r[1];
        allInvoiceUuids.add(clientUuid);
        if (internalUuid != null) allInvoiceUuids.add(internalUuid);
    }

    java.util.List<Invoice> allInvoices = allInvoiceUuids.isEmpty() ? java.util.List.of()
            : Invoice.list("uuid in ?1", allInvoiceUuids);
    java.util.Map<String, Invoice> invoiceById = allInvoices.stream()
            .collect(java.util.stream.Collectors.toMap(Invoice::getUuid, i -> i));

    // Build dynamic IN clause for native SQL
    StringBuilder ph = new StringBuilder();
    int size = allInvoiceUuids.size();
    for (int i = 0; i < size; i++) {
        if (i > 0) ph.append(',');
        ph.append('?');
    }

    String linesSql = """
        WITH base AS (
            SELECT 
                i.uuid AS invoiceuuid,
                i.companyuuid AS invoice_companyuuid,
                i.invoicedate,
                ii.uuid AS line_uuid,
                ii.itemname,
                ii.description,
                ii.hours,
                ii.rate,
                ii.consultantuuid
            FROM invoices i
            JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
            WHERE i.uuid IN (%s)
        ),
        status_pick AS (
            SELECT 
                b.invoiceuuid,
                b.consultantuuid,
                us.companyuuid AS users_companyuuid,
                ROW_NUMBER() OVER (
                    PARTITION BY b.invoiceuuid, b.consultantuuid
                    ORDER BY us.statusdate DESC
                ) AS rn
            FROM base b
            LEFT JOIN userstatus us
              ON us.useruuid = b.consultantuuid
             AND us.statusdate <= b.invoicedate
        )
        SELECT 
            b.invoiceuuid,
            b.line_uuid,
            b.itemname,
            b.description,
            b.hours,
            b.rate,
            b.consultantuuid,
            CASE 
              WHEN b.consultantuuid IS NULL THEN 0
              WHEN sp.rn = 1 
                   AND sp.users_companyuuid IS NOT NULL
                   AND sp.users_companyuuid <> b.invoice_companyuuid 
                THEN 1
              ELSE 0
            END AS cross_company
        FROM base b
        LEFT JOIN status_pick sp
          ON sp.invoiceuuid = b.invoiceuuid
         AND sp.consultantuuid = b.consultantuuid
         AND sp.rn = 1
        ORDER BY b.invoiceuuid
    """.formatted(ph.toString());

    var q = em.createNativeQuery(linesSql);
    int idx = 1;
    for (String id : allInvoiceUuids) q.setParameter(idx++, id);

    @SuppressWarnings("unchecked")
    java.util.List<Object[]> lineRows = q.getResultList();

    java.util.Map<String, java.util.List<InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
    for (Object[] r : lineRows) {
        String invId   = (String) r[0];
        String lineId  = (String) r[1];
        String name    = (String) r[2];
        String desc    = (String) r[3];
        Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
        Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
        String cuuid   = (String) r[6];
        boolean cross  = ((Number) r[7]).intValue() == 1;
        double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

        linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                .add(new InvoiceLineDTO(lineId, name, desc, hours, rate, round2(amount), cuuid, cross));
    }

    java.util.List<CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
    for (Object[] r : pairs) {
        String clientUuid = (String) r[0];
        String internalUuid = (String) r[1];

        Invoice client = invoiceById.get(clientUuid);
        if (client == null) continue;
        java.util.List<InvoiceLineDTO> clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
        double clientTotal = clientLines.stream().mapToDouble(InvoiceLineDTO::amountNoTax).sum();
        String resolvedClientName = resolveClientName(client);
        SimpleInvoiceDTO clientDto = new SimpleInvoiceDTO(
                client.getUuid(),
                client.getInvoicenumber(),
                client.getInvoicedate(),
                client.getCompany() != null ? client.getCompany().getUuid() : null,
                client.getCompany() != null ? client.getCompany().getName() : null,
                resolvedClientName,
                client.getStatus(),
                client.getEconomicsStatus(),
                round2(clientTotal),
                clientLines
        );

        SimpleInvoiceDTO internalDto = null;
        if (internalUuid != null) {
            Invoice internal = invoiceById.get(internalUuid);
            if (internal != null) {
                java.util.List<InvoiceLineDTO> ilines = linesByInvoice.getOrDefault(internalUuid, java.util.List.of());
                double itotal = ilines.stream().mapToDouble(InvoiceLineDTO::amountNoTax).sum();
                internalDto = new SimpleInvoiceDTO(
                        internal.getUuid(),
                        internal.getInvoicenumber(),
                        internal.getInvoicedate(),
                        internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                        internal.getCompany() != null ? internal.getCompany().getName() : null,
                        null,
                        internal.getStatus(),
                        internal.getEconomicsStatus(),
                        round2(itotal),
                        ilines
                );
            }
        }

        result.add(new CrossCompanyInvoicePairDTO(clientDto, internalDto));
    }

    return result;
}

    /**
     * Finds client/internal invoice pairs where the client invoice contains cross-company
     * consultant lines and the sum of client invoice lines (hours * rate) is strictly less
     * than the sum of the corresponding internal invoice lines. The internal invoice is
     * linked by invoice_ref = client.invoicenumber and must be in status QUEUED or CREATED.
     *
     * Implementation details (MariaDB 11):
     * - Uses window functions (ROW_NUMBER) to select the latest userstatus per (invoice, consultant)
     *   on or before the invoice date to determine cross-company involvement.
     * - Selects at most one internal invoice per client (latest by invoicedate then invoicenumber).
     * - Computes totals for client and internal in SQL and filters to client_total < internal_total.
     * - Fetches lines for both invoices in one SQL and computes per-line crossCompany flags.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * Currency note: Amounts are compared in native invoice currency without FX conversion.
     * Ensure client/internal use the same currency for meaningful comparison.
     *
     * @param fromdate start date (inclusive)
     * @param todate end date (exclusive)
     * @return list of pairs containing the client and the selected internal invoice
     */
    public java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO>
    findCrossCompanyClientLessThanInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String pairSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  AND status = 'CREATED'
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            ),
            internal_pick AS (
                SELECT 
                    ci.uuid AS client_uuid,
                    ii.uuid AS internal_uuid,
                    ROW_NUMBER() OVER (
                       PARTITION BY ci.uuid
                       ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                    ) AS r
                FROM cross_clients ci
                JOIN invoices ii
                  ON ii.invoice_ref_uuid = ci.uuid
                 AND ii.type          = 'INTERNAL'
                 AND ii.status IN ('QUEUED','CREATED')
            ),
            chosen AS (
                SELECT client_uuid, internal_uuid
                FROM internal_pick
                WHERE r = 1
            ),
            client_totals AS (
                SELECT i.uuid AS invoiceuuid, COALESCE(SUM(ii.hours * ii.rate), 0) AS total
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (SELECT client_uuid FROM chosen)
                GROUP BY i.uuid
            ),
            internal_totals AS (
                SELECT i.uuid AS invoiceuuid, COALESCE(SUM(ii.hours * ii.rate), 0) AS total
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (SELECT internal_uuid FROM chosen)
                GROUP BY i.uuid
            )
            SELECT c.client_uuid, c.internal_uuid
            FROM chosen c
            JOIN client_totals  ct ON ct.invoiceuuid  = c.client_uuid
            JOIN internal_totals it ON it.invoiceuuid = c.internal_uuid
            WHERE ct.total < it.total
            ORDER BY c.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> pairs = em.createNativeQuery(pairSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (pairs.isEmpty()) return java.util.List.of();

        // Collect invoice IDs (client + internal)
        java.util.LinkedHashSet<String> allInvoiceUuids = new java.util.LinkedHashSet<>();
        for (Object[] r : pairs) {
            allInvoiceUuids.add((String) r[0]); // client
            allInvoiceUuids.add((String) r[1]); // internal
        }

        // Load headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allInvoiceUuids);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build dynamic placeholders for IN clause
        StringBuilder ph = new StringBuilder();
        int size = allInvoiceUuids.size();
        for (int i = 0; i < size; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Lines SQL with per-line cross-company flag via userstatus as-of invoicedate
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allInvoiceUuids) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        // Group lines per invoice
        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        // Assemble DTO pairs
        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
        for (Object[] r : pairs) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];

            var client = invoiceById.get(clientUuid);
            var internal = invoiceById.get(internalUuid);
            if (client == null || internal == null) continue;

            var clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
            var internalLines = linesByInvoice.getOrDefault(internalUuid, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            double internalTotal = internalLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines
            );

            var internalDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    internal.getUuid(),
                    internal.getInvoicenumber(),
                    internal.getInvoicedate(),
                    internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                    internal.getCompany() != null ? internal.getCompany().getName() : null,
                    null,
                    internal.getStatus(),
                    internal.getEconomicsStatus(),
                    round2(internalTotal),
                    internalLines
            );

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO(clientDto, internalDto));
        }

        return result;
    }

    /**
     * Finds client invoices (status CREATED, type INVOICE) that contain at least one cross-company
     * consultant line, but have no referring INTERNAL invoice in status QUEUED or CREATED.
     * Cross-company is determined by evaluating each consultant's latest userstatus on or before
     * the invoice date (as-of) and comparing that company UUID to the issuing invoice's company UUID.
     *
     * Implementation (MariaDB 11):
     * - Window function (ROW_NUMBER) to pick latest userstatus per (invoice, consultant).
     * - Selects client invoices with cross-company involvement, then left-joins to INTERNAL invoices
     *   (invoice_ref = client.invoicenumber, status in QUEUED/CREATED) and filters those with none.
     * - Fetches invoice lines and computes a per-line crossCompany boolean.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * @param fromdate Start date (inclusive)
     * @param todate   End date (exclusive)
     * @return List of SimpleInvoiceDTO for client invoices without a referring internal invoice
     */
    public java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO>
    findCrossCompanyClientInvoicesWithoutInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String selectSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  AND status = 'CREATED'
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            )
            SELECT ci.uuid AS client_uuid
            FROM cross_clients ci
            LEFT JOIN invoices ii
              ON ii.invoice_ref_uuid = ci.uuid
             AND ii.type = 'INTERNAL'
             AND ii.status IN ('QUEUED','CREATED')
            WHERE ii.uuid IS NULL
            ORDER BY ci.invoicedate DESC, ci.invoicenumber DESC
        """;

        @SuppressWarnings("unchecked")
        java.util.List<String> clientIds = em.createNativeQuery(selectSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (clientIds.isEmpty()) return java.util.List.of();

        // Load invoice headers for selected client invoices
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> clients =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", new java.util.LinkedHashSet<>(clientIds));
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = clients.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Prepare dynamic IN placeholders for native SQL
        StringBuilder ph = new StringBuilder();
        int size = clientIds.size();
        for (int i = 0; i < size; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Fetch lines and compute per-line crossCompany using userstatus as-of invoicedate
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : clientIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO> result = new java.util.ArrayList<>();
        for (String clientUuid : clientIds) {
            var client = invoiceById.get(clientUuid);
            if (client == null) continue;
            var clientLines = linesByInvoice.getOrDefault(clientUuid, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines
            );
            result.add(clientDto);
        }

        return result;
    }

    /**
     * Finds client/internal invoice pairs where the client invoice is a regular client invoice (type INVOICE)
     * that currently has status CREDIT_NOTE and contains at least one cross-company consultant line, and where
     * there exists a referring INTERNAL invoice (status in QUEUED/CREATED) linked via invoice_ref.
     *
     * Cross-company evaluation uses the consultant's latest userstatus on or before the invoice date (as-of)
     * to compare the consultant company to the issuing invoice company. Implemented using MariaDB 11 window
     * functions for performance.
     *
     * Date semantics: from inclusive, to exclusive.
     *
     * @param fromdate Start date (inclusive)
     * @param todate   End date (exclusive)
     * @return List of pairs (client + internal) with header data and line-level crossCompany flags
     */
    public java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO>
    findCrossCompanyClientInvoicesStatusCreditNoteWithInternal(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String pairsSql = """
            WITH inv AS (
                SELECT *
                FROM invoices
                WHERE invoicedate >= :from
                  AND invoicedate <  :to
                  AND status = 'CREDIT_NOTE'
                  AND type   = 'INVOICE'
            ),
            status_pick AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    ii.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY i.uuid, ii.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM inv i
                JOIN invoiceitems ii 
                  ON ii.invoiceuuid = i.uuid
                 AND ii.consultantuuid IS NOT NULL
                JOIN userstatus us 
                  ON us.useruuid = ii.consultantuuid
                 AND us.statusdate <= i.invoicedate
            ),
            cross_clients AS (
                SELECT DISTINCT i.*
                FROM inv i
                JOIN status_pick sp ON sp.invoiceuuid = i.uuid
                WHERE sp.rn = 1
                  AND i.companyuuid IS NOT NULL
                  AND sp.users_companyuuid IS NOT NULL
                  AND sp.users_companyuuid <> i.companyuuid
            ),
            internal_pick AS (
                SELECT 
                    ci.uuid AS client_uuid,
                    ii.uuid AS internal_uuid,
                    ROW_NUMBER() OVER (
                       PARTITION BY ci.uuid
                       ORDER BY ii.invoicedate DESC, ii.invoicenumber DESC
                    ) AS r
                FROM cross_clients ci
                JOIN invoices ii
                  ON ii.invoice_ref_uuid = ci.uuid
                 AND ii.type          = 'INTERNAL'
                 AND ii.status IN ('QUEUED','CREATED')
            )
            SELECT ip.client_uuid, ip.internal_uuid
            FROM internal_pick ip
            WHERE ip.r = 1
            ORDER BY ip.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> pairs = em.createNativeQuery(pairsSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (pairs.isEmpty()) return java.util.List.of();

        // Collect client+internal invoice UUIDs
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Object[] r : pairs) { allIds.add((String) r[0]); allIds.add((String) r[1]); }

        // Load headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allIds);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build dynamic IN placeholders
        StringBuilder ph = new StringBuilder();
        int n = allIds.size();
        for (int i = 0; i < n; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Lines and per-line cross-company flag
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invoiceUuid = (String) r[0];
            String lineUuid    = (String) r[1];
            String itemName    = (String) r[2];
            String description = (String) r[3];
            Double hours       = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate        = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String consultUuid = (String) r[6];
            boolean cross      = ((Number) r[7]).intValue() == 1;
            double amount      = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invoiceUuid, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineUuid, itemName, description, hours, rate,
                            round2(amount), consultUuid, cross
                    ));
        }

        java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO> result = new java.util.ArrayList<>();
        for (Object[] r : pairs) {
            String clientId = (String) r[0];
            String internalId = (String) r[1];
            var client = invoiceById.get(clientId);
            var internal = invoiceById.get(internalId);
            if (client == null || internal == null) continue;

            var clientLines = linesByInvoice.getOrDefault(clientId, java.util.List.of());
            var internalLines = linesByInvoice.getOrDefault(internalId, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            double internalTotal = internalLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();

            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines
            );

            var internalDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    internal.getUuid(),
                    internal.getInvoicenumber(),
                    internal.getInvoicedate(),
                    internal.getCompany() != null ? internal.getCompany().getUuid() : null,
                    internal.getCompany() != null ? internal.getCompany().getName() : null,
                    null,
                    internal.getStatus(),
                    internal.getEconomicsStatus(),
                    round2(internalTotal),
                    internalLines
            );

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.CrossCompanyInvoicePairDTO(clientDto, internalDto));
        }

        return result;
    }

    /**
     * Returns client invoices (type INVOICE) that have more than one INTERNAL invoice (status in
     * QUEUED or CREATED) referencing them via invoice_ref, within the given date window.
     *
     * Date semantics: from inclusive, to exclusive; applies to the client's invoicedate.
     *
     * Implementation outline (MariaDB 11):
     * - CTE clients: select client invoices by date and type INVOICE.
     * - CTE internals: all INTERNAL invoices in status QUEUED/CREATED.
     * - CTE pairs: join by invoice_ref to associate internals to clients.
     * - CTE dup_clients: retain only clients with COUNT(internal) > 1.
     * - Fetch headers for all involved invoices, then lines with per-line crossCompany flags using
     *   userstatus as-of invoicedate via ROW_NUMBER window function.
     *
     * @param fromdate Start date (inclusive), format yyyy-MM-dd
     * @param todate   End date (exclusive), format yyyy-MM-dd
     * @return List of ClientWithInternalsDTO containing the client and all its internal invoices
     */
    public java.util.List<ClientWithInternalsDTO> findClientInvoicesWithMultipleInternals(java.time.LocalDate fromdate, java.time.LocalDate todate) {
        java.time.LocalDate from = (fromdate != null) ? fromdate : java.time.LocalDate.of(2014, 1, 1);
        java.time.LocalDate to   = (todate   != null) ? todate   : java.time.LocalDate.now();

        String selectSql = """
            WITH clients AS (
                SELECT *
                FROM invoices i
                WHERE i.invoicedate >= :from
                  AND i.invoicedate <  :to
                  AND i.type = 'INVOICE'
                  AND i.status <> 'DRAFT'
            ),
            internals AS (
                SELECT ii.*
                FROM invoices ii
                WHERE ii.type = 'INTERNAL'
                  AND ii.status IN ('QUEUED','CREATED')
            ),
            pairs AS (
                SELECT c.uuid AS client_uuid, i.uuid AS internal_uuid
                FROM clients c
                JOIN internals i ON i.invoice_ref_uuid = c.uuid
            ),
            dup_clients AS (
                SELECT client_uuid, COUNT(*) AS internal_count
                FROM pairs
                GROUP BY client_uuid
                HAVING COUNT(*) > 1
            )
            SELECT p.client_uuid, p.internal_uuid
            FROM pairs p
            JOIN dup_clients d ON d.client_uuid = p.client_uuid
            ORDER BY p.client_uuid
        """;

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> rows = em.createNativeQuery(selectSql)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        if (rows.isEmpty()) return java.util.List.of();

        // Map client -> list of internal UUIDs and collect all IDs
        java.util.LinkedHashMap<String, java.util.List<String>> clientToInternals = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> allIds = new java.util.LinkedHashSet<>();
        for (Object[] r : rows) {
            String clientUuid = (String) r[0];
            String internalUuid = (String) r[1];
            clientToInternals.computeIfAbsent(clientUuid, k -> new java.util.ArrayList<>()).add(internalUuid);
            allIds.add(clientUuid);
            allIds.add(internalUuid);
        }

        // Load invoice headers
        java.util.List<dk.trustworks.intranet.aggregates.invoice.model.Invoice> allInvoices =
                dk.trustworks.intranet.aggregates.invoice.model.Invoice.list("uuid in ?1", allIds);
        java.util.Map<String, dk.trustworks.intranet.aggregates.invoice.model.Invoice> invoiceById = allInvoices.stream()
                .collect(java.util.stream.Collectors.toMap(dk.trustworks.intranet.aggregates.invoice.model.Invoice::getUuid, i -> i));

        // Build placeholders for IN clause
        StringBuilder ph = new StringBuilder();
        int n = allIds.size();
        for (int i = 0; i < n; i++) { if (i > 0) ph.append(','); ph.append('?'); }

        // Fetch lines and compute per-line crossCompany (userstatus as-of invoicedate)
        String linesSql = ("""
            WITH base AS (
                SELECT 
                    i.uuid AS invoiceuuid,
                    i.companyuuid AS invoice_companyuuid,
                    i.invoicedate,
                    ii.uuid AS line_uuid,
                    ii.itemname,
                    ii.description,
                    ii.hours,
                    ii.rate,
                    ii.consultantuuid
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.uuid IN (%s)
            ),
            status_pick AS (
                SELECT 
                    b.invoiceuuid,
                    b.consultantuuid,
                    us.companyuuid AS users_companyuuid,
                    ROW_NUMBER() OVER (
                        PARTITION BY b.invoiceuuid, b.consultantuuid
                        ORDER BY us.statusdate DESC
                    ) AS rn
                FROM base b
                LEFT JOIN userstatus us
                  ON us.useruuid = b.consultantuuid
                 AND us.statusdate <= b.invoicedate
            )
            SELECT 
                b.invoiceuuid,
                b.line_uuid,
                b.itemname,
                b.description,
                b.hours,
                b.rate,
                b.consultantuuid,
                CASE 
                  WHEN b.consultantuuid IS NULL THEN 0
                  WHEN sp.rn = 1 
                       AND sp.users_companyuuid IS NOT NULL
                       AND sp.users_companyuuid <> b.invoice_companyuuid 
                    THEN 1
                  ELSE 0
                END AS cross_company
            FROM base b
            LEFT JOIN status_pick sp
              ON sp.invoiceuuid = b.invoiceuuid
             AND sp.consultantuuid = b.consultantuuid
             AND sp.rn = 1
            ORDER BY b.invoiceuuid
        """).formatted(ph.toString());

        var q = em.createNativeQuery(linesSql);
        int idx = 1; for (String id : allIds) q.setParameter(idx++, id);

        @SuppressWarnings("unchecked")
        java.util.List<Object[]> lineRows = q.getResultList();

        java.util.Map<String, java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO>> linesByInvoice = new java.util.HashMap<>();
        for (Object[] r : lineRows) {
            String invId   = (String) r[0];
            String lineId  = (String) r[1];
            String name    = (String) r[2];
            String desc    = (String) r[3];
            Double hours   = r[4] == null ? null : ((Number) r[4]).doubleValue();
            Double rate    = r[5] == null ? null : ((Number) r[5]).doubleValue();
            String cuuid   = (String) r[6];
            boolean cross  = ((Number) r[7]).intValue() == 1;
            double amount  = (hours == null || rate == null) ? 0.0 : (hours * rate);

            linesByInvoice.computeIfAbsent(invId, k -> new java.util.ArrayList<>())
                    .add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO(
                            lineId, name, desc, hours, rate, round2(amount), cuuid, cross
                    ));
        }

        // Assemble DTOs
        java.util.List<ClientWithInternalsDTO> result = new java.util.ArrayList<>();
        for (var e : clientToInternals.entrySet()) {
            String clientId = e.getKey();
            java.util.List<String> internalIds = e.getValue();

            var client = invoiceById.get(clientId);
            if (client == null) continue;
            var clientLines = linesByInvoice.getOrDefault(clientId, java.util.List.of());
            double clientTotal = clientLines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
            String resolvedClientName = resolveClientName(client);
            var clientDto = new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                    client.getUuid(),
                    client.getInvoicenumber(),
                    client.getInvoicedate(),
                    client.getCompany() != null ? client.getCompany().getUuid() : null,
                    client.getCompany() != null ? client.getCompany().getName() : null,
                    resolvedClientName,
                    client.getStatus(),
                    client.getEconomicsStatus(),
                    round2(clientTotal),
                    clientLines
            );

            java.util.List<dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO> internals = new java.util.ArrayList<>();
            for (String internalId : internalIds) {
                var inv = invoiceById.get(internalId);
                if (inv == null) continue;
                var lines = linesByInvoice.getOrDefault(internalId, java.util.List.of());
                double total = lines.stream().mapToDouble(dk.trustworks.intranet.aggregates.invoice.resources.dto.InvoiceLineDTO::amountNoTax).sum();
                internals.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.SimpleInvoiceDTO(
                        inv.getUuid(),
                        inv.getInvoicenumber(),
                        inv.getInvoicedate(),
                        inv.getCompany() != null ? inv.getCompany().getUuid() : null,
                        inv.getCompany() != null ? inv.getCompany().getName() : null,
                        null,
                        inv.getStatus(),
                        inv.getEconomicsStatus(),
                        round2(total),
                        lines
                ));
            }

            result.add(new dk.trustworks.intranet.aggregates.invoice.resources.dto.ClientWithInternalsDTO(
                    clientDto, internals, internals.size()
            ));
        }

        return result;
    }

}