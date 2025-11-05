package dk.trustworks.intranet.aggregates.invoice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedment.jpastreamer.application.JPAStreamer;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus;
import dk.trustworks.intranet.aggregates.invoice.bonus.services.InvoiceBonusService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
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
import static dk.trustworks.intranet.utils.DateUtils.fiscalYearStart;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static dk.trustworks.intranet.utils.NumberUtils.round2;

@JBossLog
@ApplicationScoped
public class InvoiceService {

    @Inject
    EntityManager em;

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
    InvoiceEconomicsUploadService uploadService;

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
        return new DateValueDTO(LocalDate.of(invoice.getInvoiceYear(), invoice.getInvoiceMonth() + 1, 1), sum);
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
        List<LifecycleStatus> statuses =
                (type != null && type.length > 0)
                        ? Arrays.stream(type).map(LifecycleStatus::valueOf).toList()
                        : List.of(LifecycleStatus.CREATED, LifecycleStatus.DRAFT);

        LocalDate from = (fromdate != null) ? fromdate : LocalDate.of(2014, 1, 1);
        LocalDate to   = (todate   != null) ? todate   : LocalDate.now();

        // All filtering happens in SQL
        return Invoice.find("invoicedate >= ?1 AND invoicedate <= ?2 AND lifecycleStatus IN (?3)",
                from, to, statuses).list();
    }


    public List<Invoice> findByBookingDate(LocalDate fromdate, LocalDate todate) {
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
                        "company = ?1 AND invoicedate >= ?2 AND invoicedate < ?3 AND type <> ?4 AND lifecycleStatus <> ?5",
                        Company.<Company>findById(companyuuid), fromdate, todate, InvoiceType.INTERNAL_SERVICE, LifecycleStatus.DRAFT)
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
                        "company.uuid <> ?1 AND type = ?2 AND lifecycleStatus <> ?3 AND sourceInvoiceUuid IN (SELECT i.uuid FROM Invoice i WHERE i.invoicenumber IN (?4))",
                        companyuuid, InvoiceType.INTERNAL, LifecycleStatus.DRAFT, invoiceNumbers)
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
                "LEFT JOIN invoices_v2 i on i.uuid = ii.invoiceuuid " +
                "WHERE lifecycle_status NOT LIKE 'DRAFT' AND issuer_companyuuid = '"+companyuuid+"' AND EXTRACT(YEAR_MONTH FROM if(i.bookingdate != '1900-01-01', i.bookingdate, i.invoicedate)) = "+stringIt(month, "yyyyMM")+"; ";
        Object singleResult = em.createNativeQuery(sql).getSingleResult();
        return singleResult!=null?((Number) singleResult).doubleValue():0.0;
    }

    @SuppressWarnings("unchecked")
    public List<Invoice> findInvoicesForSingleMonth(LocalDate month, String... type) {
        String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "DRAFT"};
        LocalDate date = month.withDayOfMonth(1);
        String sql = "SELECT * FROM invoices_v2 i WHERE " +
                "i.invoice_year = " + date.getYear() + " AND i.invoice_month = " + (date.getMonthValue() - 1) + " " +
                " AND i.lifecycle_status IN ('"+String.join("','", finalType)+"');";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        return Collections.unmodifiableList(invoices);
    }

    @SuppressWarnings("unchecked")
    public List<Invoice> findProjectInvoices(String projectuuid) {
        String sql = "SELECT * FROM invoices_v2 i WHERE i.projectuuid like '"+projectuuid+"' AND i.lifecycle_status IN ('CREATED') ORDER BY invoice_year DESC, invoice_month DESC;";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        return Collections.unmodifiableList(invoices);
    }

    public List<Invoice> findContractInvoices(String contractuuid) {
        return Invoice.find("contractuuid = ?1 AND lifecycleStatus IN ('CREATED') ORDER BY invoiceYear DESC, invoiceMonth DESC", contractuuid).list();
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
                "contractuuid IN ?1 AND lifecycleStatus IN ('CREATED')",
                Sort.by("invoiceYear", Sort.Direction.Descending).and("invoiceMonth", Sort.Direction.Descending),
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
        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.getInvoiceitems().forEach(invoiceItem -> invoiceItem.setInvoiceuuid(invoice.getUuid()));
        Invoice.persist(invoice);
        log.debug("Draft invoice persisted: " + invoice.getUuid());
        return invoice;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Invoice updateDraftInvoice(Invoice invoice) {
        if (invoice.getType() == InvoiceType.CREDIT_NOTE) {
            return legacyUpdateDraft(invoice);
        }

        if (invoice.getLifecycleStatus() != LifecycleStatus.DRAFT) throw new WebApplicationException(Response.Status.CONFLICT);

        Invoice.update("billToAttn = ?1, bookingdate = ?2, billToLine1 = ?3, billToName = ?4, billToCvr = ?5, " +
                        "headerDiscountPct = ?6, billToEan = ?7, invoicedate = ?8, sourceInvoiceUuid = ?9, " +
                        "billToLine2 = ?10, projectuuid = ?11, lifecycleStatus = ?12, " +
                        "invoicenumber = ?13, type = ?14, billToZip = ?15, billToCity = ?16, company = ?17, currency = ?18, " +
                        "duedate = ?19, vatPct = ?20 WHERE uuid like ?21",
                invoice.getBillToAttn(),
                invoice.getBookingdate(),
                invoice.getBillToLine1(),
                invoice.getBillToName(),
                invoice.getBillToCvr(),
                invoice.getHeaderDiscountPct(),
                invoice.getBillToEan(),
                invoice.getInvoicedate(),
                invoice.getSourceInvoiceUuid(),
                invoice.getBillToLine2(),
                invoice.getProjectuuid(),
                invoice.getLifecycleStatus(),
                invoice.getInvoicenumber(),
                invoice.getType(),
                invoice.getBillToZip(),
                invoice.getBillToCity(),
                invoice.getCompany(),
                invoice.getCurrency(),
                invoice.getDuedate(),
                invoice.getVatPct(),
                invoice.getUuid());

        recalculateInvoiceItems(invoice);
        // Bonus calculation only for non-internal invoices
        if (invoice.getType() != InvoiceType.INTERNAL) {
            bonusService.recalcForInvoice(invoice.getUuid());
        }
        return invoice;
    }

    private void recalculateInvoiceItems(Invoice invoice) {
        var baseItems = invoice.getInvoiceitems().stream()
                .filter(ii -> {
                    // Explicitly exclude CALCULATED items
                    if (ii.getOrigin() == InvoiceItemOrigin.CALCULATED) return false;
                    // Also exclude items with CALCULATED-specific metadata (prevents
                    // preserving CALCULATED items when origin defaults to BASE during JSON deserialization)
                    if (ii.getCalculationRef() != null) return false;
                    if (ii.getRuleId() != null) return false;
                    if (ii.getLabel() != null) return false;
                    // Keep only true BASE items
                    return true;
                })
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

        invoice.getInvoiceitems().clear();
        invoice.getInvoiceitems().addAll(baseItems);
        invoice.getInvoiceitems().addAll(pr.syntheticItems);
        invoice.setSumBeforeDiscounts(pr.sumBeforeDiscounts.doubleValue());
        invoice.setSumAfterDiscounts(pr.sumAfterDiscounts.doubleValue());
        invoice.setVatAmount(pr.vatAmount.doubleValue());
        invoice.setGrandTotal(pr.grandTotal.doubleValue());
        System.out.print("DONE: ");
        invoice.getInvoiceitems().forEach(System.out::println);
    }

    @Transactional
    public void updateInvoiceStatus(Invoice invoice, LifecycleStatus status) {
                Invoice.update("lifecycleStatus = ?1 WHERE uuid = ?2",
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
        draftInvoice.setInvoicenumber(getMaxInvoiceNumber(draftInvoice) + 1);
        if(draftInvoice.getType() == InvoiceType.CREDIT_NOTE) {
            Invoice parentInvoice = Invoice.findById(draftInvoice.getCreditnoteForUuid());
            updateInvoiceStatus(parentInvoice, LifecycleStatus.CREATED);
            parentInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        }

        if (draftInvoice.getType() != InvoiceType.CREDIT_NOTE) {
            recalculateInvoiceItems(draftInvoice);
            // Bonus calculation only for non-internal invoices
            if (draftInvoice.getType() != InvoiceType.INTERNAL) {
                bonusService.recalcForInvoice(draftInvoice.getUuid());
            }
        }
        draftInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        // Note: PDF generation handled externally
        log.debug("Saving invoice...");
        saveInvoice(draftInvoice);
        log.debug("Invoice saved: "+draftInvoice.getUuid());
        log.debug("Queued invoice for economics upload (will process in background)");
        uploadService.queueUploads(draftInvoice);
        log.infof("Invoice %s queued for e-conomics upload with status PENDING", draftInvoice.getUuid());
        String contractuuid = draftInvoice.getContractuuid();
        String projectuuid = draftInvoice.getProjectuuid();
        workService.registerAsPaidout(contractuuid, projectuuid, draftInvoice.getInvoiceMonth()+1, draftInvoice.getInvoiceYear());

        return draftInvoice;
    }

    @Transactional
    public Invoice createPhantomInvoice(Invoice draftInvoice) throws JsonProcessingException {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        recalculateInvoiceItems(draftInvoice);
        bonusService.recalcForInvoice(draftInvoice.getUuid());
        draftInvoice.setLifecycleStatus(LifecycleStatus.CREATED);
        draftInvoice.setInvoicenumber(0);
        draftInvoice.setType(InvoiceType.PHANTOM);
        // Note: PDF generation handled externally
        saveInvoice(draftInvoice);
        if(!"dev".equals(LaunchMode.current().getProfileKey())) {
            uploadService.queueUploads(draftInvoice);
            log.infof("Queued phantom invoice %s for e-conomics upload (will process in background)", draftInvoice.getUuid());
        } else {
            log.warn("The invoice is not uploaded to e-conomics in Dev environment");
        }
        return draftInvoice;
    }

    private void saveInvoice(Invoice invoice) {
        Invoice.update(
                        "lifecycleStatus = ?1, " +
                        "invoicenumber = ?2 " +
                        "WHERE uuid like ?3 ",
                invoice.getLifecycleStatus(),
                invoice.getInvoicenumber(),
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

        Invoice creditNote = new Invoice();
        creditNote.setUuid(UUID.randomUUID().toString());
        creditNote.setSourceInvoiceUuid(invoice.getUuid());
        creditNote.setType(InvoiceType.CREDIT_NOTE);
        creditNote.setContractuuid(invoice.getContractuuid());
        creditNote.setProjectuuid(invoice.getProjectuuid());
        creditNote.setHeaderDiscountPct(invoice.getHeaderDiscountPct());
        creditNote.setBillToName(invoice.getBillToName());
        creditNote.setBillToLine1(invoice.getBillToLine1());
        creditNote.setBillToLine2(invoice.getBillToLine2());
        creditNote.setBillToZip(invoice.getBillToZip());
        creditNote.setBillToCity(invoice.getBillToCity());
        creditNote.setBillToEan(invoice.getBillToEan());
        creditNote.setBillToCvr(invoice.getBillToCvr());
        creditNote.setBillToAttn(invoice.getBillToAttn());
        creditNote.setInvoicedate(LocalDate.now());
        creditNote.setDuedate(LocalDate.now().plusMonths(1));
        creditNote.setCompany(invoice.getCompany());
        creditNote.setIssuerCompanyuuid(invoice.getIssuerCompanyuuid());
        creditNote.setCurrency(invoice.getCurrency());
        creditNote.setVatPct(invoice.getVatPct());
        creditNote.setCreditnoteForUuid(invoice.getUuid());

        for (InvoiceItem invoiceitem : invoice.getInvoiceitems()) {
            InvoiceItem newItem = new InvoiceItem(
                invoiceitem.getConsultantuuid(),
                invoiceitem.getItemname(),
                invoiceitem.getDescription(),
                invoiceitem.getRate(),
                invoiceitem.getHours(),
                invoiceitem.getPosition(),
                creditNote.getUuid(),
                invoiceitem.getOrigin()
            );
            // Preserve additional fields for CALCULATED items
            if (invoiceitem.getOrigin() == InvoiceItemOrigin.CALCULATED) {
                newItem.setCalculationRef(invoiceitem.getCalculationRef());
                newItem.setRuleId(invoiceitem.getRuleId());
                newItem.setLabel(invoiceitem.getLabel());
            }
            creditNote.getInvoiceitems().add(newItem);
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
        Invoice internalInvoice = new Invoice();
        internalInvoice.setUuid(UUID.randomUUID().toString());
        internalInvoice.setSourceInvoiceUuid(invoice.getUuid());
        internalInvoice.setType(InvoiceType.INTERNAL);
        internalInvoice.setContractuuid(invoice.getContractuuid());
        internalInvoice.setProjectuuid(invoice.getProjectuuid());
        internalInvoice.setHeaderDiscountPct(invoice.getHeaderDiscountPct());
        internalInvoice.setBillToName(invoice.getCompany().getName());
        internalInvoice.setBillToLine1(invoice.getCompany().getAddress());
        internalInvoice.setBillToLine2("");
        internalInvoice.setBillToZip(invoice.getCompany().getZipcode());
        internalInvoice.setBillToCity("");
        internalInvoice.setBillToEan("");
        internalInvoice.setBillToCvr(invoice.getCompany().getCvr());
        internalInvoice.setBillToAttn("Tobias Kjølsen");
        internalInvoice.setInvoicedate(LocalDate.now());
        internalInvoice.setDuedate(LocalDate.now().plusMonths(1));
        internalInvoice.setCompany(Company.findById(companyuuid));
        internalInvoice.setIssuerCompanyuuid(companyuuid);
        internalInvoice.setCurrency(invoice.getCurrency());
        internalInvoice.setVatPct(invoice.getVatPct());
        internalInvoice.setDebtorCompanyuuid(invoice.getIssuerCompanyuuid());

        int position = 1;
        for (InvoiceItem invoiceitem : invoice.getInvoiceitems()) {
            if(invoiceitem.getRate() == 0.0 || invoiceitem.getHours() == 0.0) continue;
            InvoiceItem newItem = new InvoiceItem(
                invoiceitem.getConsultantuuid(),
                invoiceitem.getItemname(),
                invoiceitem.getDescription(),
                invoiceitem.getRate(),
                invoiceitem.getHours(),
                position++,
                internalInvoice.getUuid(),
                invoiceitem.getOrigin()
            );
            // Preserve additional fields for CALCULATED items
            if (invoiceitem.getOrigin() == InvoiceItemOrigin.CALCULATED) {
                newItem.setCalculationRef(invoiceitem.getCalculationRef());
                newItem.setRuleId(invoiceitem.getRuleId());
                newItem.setLabel(invoiceitem.getLabel());
            }
            internalInvoice.getInvoiceitems().add(newItem);
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
        Invoice invoice = new Invoice();
        invoice.setUuid(UUID.randomUUID().toString());
        invoice.setType(InvoiceType.INTERNAL_SERVICE);
        invoice.setContractuuid("");
        invoice.setProjectuuid("");
        invoice.setHeaderDiscountPct(BigDecimal.ZERO);
        invoice.setBillToName(toCompany.getName());
        invoice.setBillToLine1(toCompany.getAddress());
        invoice.setBillToLine2("");
        invoice.setBillToZip(toCompany.getZipcode());
        invoice.setBillToCity("");
        invoice.setBillToEan("");
        invoice.setBillToCvr(toCompany.getCvr());
        invoice.setBillToAttn("Tobias Kjølsen");
        invoice.setInvoicedate(LocalDate.of(2025, 6, 30));
        invoice.setDuedate(LocalDate.of(2025, 7, 30));
        invoice.setCompany(fromCompany);
        invoice.setIssuerCompanyuuid(fromCompanyuuid);
        invoice.setCurrency("DKK");
        invoice.setVatPct(BigDecimal.ZERO);

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
        return Invoice.find("type = ?1 and invoiceYear = ?2 AND invoiceMonth = ?3 ", InvoiceType.INTERNAL_SERVICE, localDate.getYear(), localDate.getMonthValue()).list();
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
        // Note: PDF storage handled externally
        invoice.persist();
    }

    public Integer getMaxInvoiceNumber(Invoice invoice) {
        Optional<Invoice> latestInvoice = Invoice.find("company = ?1", Sort.descending("invoicenumber"), invoice.getCompany()).firstResultOptional();
        return latestInvoice.map(Invoice::getInvoicenumber).orElse(1);
    }

    @Transactional
    public void deleteDraftInvoice(String invoiceuuid) {
        if(isDraftOrPhantom(invoiceuuid)) Invoice.deleteById(invoiceuuid);
    }

    private boolean isDraft(String invoiceuuid) {
        return Invoice.find("uuid LIKE ?1 AND lifecycleStatus LIKE ?2", invoiceuuid, LifecycleStatus.DRAFT).count() > 0;
    }

    private boolean isDraftOrPhantom(String invoiceuuid) {
        return Invoice.find("uuid LIKE ?1 AND (lifecycleStatus LIKE ?2 OR type = ?3 OR invoicenumber = 0)", invoiceuuid, LifecycleStatus.DRAFT, InvoiceType.PHANTOM).count() > 0;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void updateInvoiceReference(String invoiceuuid, InvoiceReference invoiceReference) {
        // Only perform the update if something actually changes to keep transactions short and avoid extra flushes
        Invoice.update("bookingdate = ?1 WHERE uuid like ?2 AND (bookingdate <> ?1 OR bookingdate IS NULL)",
                invoiceReference.getBookingdate(), invoiceuuid);
    }

    /**
     * @deprecated Use InvoiceEconomicsUploadService.queueUploads() + processUploads() instead.
     * This method is kept for backward compatibility only. All new code should use the async
     * queue system which provides automatic retries, partial success handling, and audit trails.
     */
    @Deprecated
    @Transactional
    public void uploadToEconomics(Invoice invoice) {
        if (invoice.getInvoicenumber() == 0) return;
        // Prevent double upload if already uploaded/booked/paid
        if (invoice.getFinanceStatus() == FinanceStatus.UPLOADED
                || invoice.getFinanceStatus() == FinanceStatus.BOOKED
                || invoice.getFinanceStatus() == FinanceStatus.PAID) {
            return;
        }
        try {
            Response r = economicsInvoiceService.sendVoucher(invoice);

            // Persist voucher number if it was assigned by Economics (voucher posted)
            if (invoice.getEconomicsVoucherNumber() > 0) {
                Invoice.update("economicsVoucherNumber = ?1 WHERE uuid like ?2", invoice.getEconomicsVoucherNumber(), invoice.getUuid());
                // Mark as UPLOADED as soon as voucher exists in e-conomics (robust against attachment failures)
                Invoice.update("financeStatus = ?1 WHERE uuid like ?2", FinanceStatus.UPLOADED, invoice.getUuid());
            } else if (r != null && r.getStatus() >= 200 && r.getStatus() < 300) {
                // Fallback: if we don't have voucherNumber but file upload response is 2xx, still mark UPLOADED
                Invoice.update("financeStatus = ?1 WHERE uuid like ?2", FinanceStatus.UPLOADED, invoice.getUuid());
            }
        } catch (Exception e) {
            // If voucher was created in e-conomics but an error occurred afterwards (e.g., attachment),
            // persist the voucher number and mark as UPLOADED to keep systems in sync.
            if (invoice.getEconomicsVoucherNumber() > 0) {
                Invoice.update("economicsVoucherNumber = ?1 WHERE uuid like ?2", invoice.getEconomicsVoucherNumber(), invoice.getUuid());
                Invoice.update("financeStatus = ?1 WHERE uuid like ?2", FinanceStatus.UPLOADED, invoice.getUuid());
                // Do not rethrow to avoid rolling back DB changes when the external side-effect succeeded
                return;
            }
            throw new RuntimeException(e);
        }
    }

    @Transactional
    public void updateInvoiceStatus(String invoiceuuid, SalesApprovalStatus status) {
        // Note: Bonus approval status now stored in InvoiceBonus table, not on invoice
        log.warnf("updateInvoiceStatus called with SalesApprovalStatus - this may need migration to InvoiceBonus table");
    }

    private InvoiceAPI getInvoiceAPI() {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create("https://invoice-generator.com"))
                .register(new InvoiceDynamicHeaderFilter(invoiceGeneratorApiKey))
                .build(InvoiceAPI.class);
    }

    public long countBonusApproval(List<LifecycleStatus> statuses) {
        // Only invoices with at least one bonus row
        String jpql = """
        SELECT COUNT(i) FROM Invoice i
        WHERE i.lifecycleStatus IN :st
          AND EXISTS (SELECT 1 FROM dk.trustworks.intranet.aggregates.invoice.bonus.model.InvoiceBonus b
                      WHERE b.invoiceuuid = i.uuid)
    """;
        return em.createQuery(jpql, Long.class)
                .setParameter("st", statuses)
                .getSingleResult();
    }

    // ADD this helper to parse statuses if caller passed none
    private static List<LifecycleStatus> defaultStatuses(List<LifecycleStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of(LifecycleStatus.CREATED, LifecycleStatus.SUBMITTED);
        }
        return statuses;
    }

    public List<BonusApprovalRow> findBonusApprovalPage(List<LifecycleStatus> rawStatuses,
                                                        int pageIdx, int pageSize) {

        var statuses = defaultStatuses(rawStatuses);

        // 1) page of invoices that have at least one bonus row
        String baseJpql = """
        SELECT i FROM Invoice i
        WHERE i.lifecycleStatus IN :st
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
                    i.getBillToName(),
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
            WHERE i.lifecycleStatus IN :st
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
        WHERE i.lifecycleStatus IN :st
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
                    i.getBillToName(),
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

        String select = "SELECT i.uuid, i.invoicenumber, i.invoicedate, i.currency, i.billToName, i.type, " +
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

    /**
     * Queues an INTERNAL DRAFT invoice to be automatically created when the referenced invoice is PAID.
     *
     * Validation:
     * - Invoice must be DRAFT status
     * - Invoice must be INTERNAL type
     * - Invoice must have valid source_invoice_uuid (references an existing invoice)
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
        if (invoice.getLifecycleStatus() != LifecycleStatus.DRAFT) {
            throw new WebApplicationException(
                "Invoice must be DRAFT status to queue. Current status: " + invoice.getLifecycleStatus(),
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

        // Validation 3: Must have valid source_invoice_uuid
        if (invoice.getSourceInvoiceUuid() == null || invoice.getSourceInvoiceUuid().isBlank()) {
            throw new WebApplicationException(
                "Invoice must reference a client invoice (source_invoice_uuid must be set)",
                Response.Status.BAD_REQUEST
            );
        }

        // Check that referenced client invoice exists by UUID
        Invoice referencedInvoice = Invoice.findById(invoice.getSourceInvoiceUuid());
        if (referencedInvoice == null) {
            throw new WebApplicationException(
                "Referenced client invoice not found (uuid): " + invoice.getSourceInvoiceUuid(),
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
        long existing = Invoice.count("type = ?1 and lifecycleStatus in ?2 and sourceInvoiceUuid = ?3",
                InvoiceType.INTERNAL,
                java.util.List.of(LifecycleStatus.CREATED),
                invoice.getSourceInvoiceUuid());
        if (existing > 0) {
            throw new WebApplicationException(
                    "An INTERNAL invoice already exists (QUEUED or CREATED) for client source_invoice_uuid=" + invoice.getSourceInvoiceUuid(),
                    Response.Status.CONFLICT
            );
        }

        // All validations passed - queue the invoice
        invoice.setLifecycleStatus(LifecycleStatus.CREATED);
        invoice.persist();

        log.info("Invoice successfully queued: " + invoiceuuid +
                 ", references client invoice uuid: " + invoice.getSourceInvoiceUuid() +
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

        if (queuedInvoice.getLifecycleStatus() != LifecycleStatus.CREATED) {
            throw new RuntimeException("Invoice is not queued: " + queuedInvoice.getUuid());
        }

        // Assign invoice number
        queuedInvoice.setInvoicenumber(getMaxInvoiceNumber(queuedInvoice) + 1);

        // Apply pricing engine for INTERNAL invoices (but not bonus calculation)
        if (queuedInvoice.getType() == InvoiceType.INTERNAL) {
            recalculateInvoiceItems(queuedInvoice);
        }

        // Create PDF - handled externally
        queuedInvoice.setLifecycleStatus(LifecycleStatus.CREATED);

        log.debug("Saving queued invoice...");
        saveInvoice(queuedInvoice);
        log.debug("Invoice saved: " + queuedInvoice.getUuid());

        return queuedInvoice;
    }

    /**
     * Forces creation of a queued internal invoice immediately, bypassing the wait for referenced invoice to be PAID.
     * This method is identical to the batch job processing but without the PAID status check.
     *
     * Use case: Manual intervention when user wants to create internal invoice before client invoice is paid.
     *
     * @param invoiceuuid UUID of the invoice to force-create
     * @return Created invoice
     * @throws JsonProcessingException if PDF generation fails
     * @throws WebApplicationException if validation fails
     */
    @Transactional
    public Invoice forceCreateQueuedInvoice(String invoiceuuid) throws JsonProcessingException {
        log.info("Force creating queued invoice (manual bypass): " + invoiceuuid);

        Invoice queuedInvoice = Invoice.findById(invoiceuuid);
        if (queuedInvoice == null) {
            throw new WebApplicationException("Invoice not found: " + invoiceuuid, Response.Status.NOT_FOUND);
        }

        // Validation 1: Must be QUEUED (CREATED in new model represents queued state)
        if (queuedInvoice.getLifecycleStatus() != LifecycleStatus.CREATED) {
            throw new WebApplicationException(
                "Invoice must be QUEUED status. Current status: " + queuedInvoice.getLifecycleStatus(),
                Response.Status.BAD_REQUEST
            );
        }

        // Validation 2: Must be INTERNAL type
        if (queuedInvoice.getType() != InvoiceType.INTERNAL) {
            throw new WebApplicationException(
                "Only INTERNAL invoices can be force-created. Current type: " + queuedInvoice.getType(),
                Response.Status.BAD_REQUEST
            );
        }

        // Set dates: invoicedate = today, duedate = tomorrow (same as batch job)
        queuedInvoice.setInvoicedate(LocalDate.now());
        queuedInvoice.setDuedate(LocalDate.now().plusDays(1));

        // Create the invoice (assigns number, generates PDF - NO upload yet)
        Invoice createdInvoice = createQueuedInvoiceWithoutUpload(queuedInvoice);

        log.infof("Force-created invoice %s with number %d - now handling uploads",
                createdInvoice.getUuid(), createdInvoice.getInvoicenumber());

        // Note: Upload handling will be done by InvoiceEconomicsUploadService in the REST layer
        // to match the pattern used in the batch job

        return createdInvoice;
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
                i.getBillToName(),
                amountNoTax,
                agg,
                total,
                new java.util.ArrayList<>(companies)
        );
    }



}
