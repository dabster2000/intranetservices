package dk.trustworks.intranet.aggregates.invoice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speedment.jpastreamer.application.JPAStreamer;
import dk.trustworks.intranet.aggregates.accounting.services.IntercompanyCalcService;
import dk.trustworks.intranet.aggregates.availability.services.AvailabilityService;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.EconomicsInvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.network.CurrencyAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceAPI;
import dk.trustworks.intranet.aggregates.invoice.network.InvoiceDynamicHeaderFilter;
import dk.trustworks.intranet.aggregates.invoice.network.dto.CurrencyData;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDTO;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;
import dk.trustworks.intranet.aggregates.invoice.utils.StringUtils;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.model.enums.ContractType;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.financeservice.model.AccountingAccount;
import dk.trustworks.intranet.financeservice.model.AccountingCategory;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.utils.DateUtils;
import dk.trustworks.intranet.utils.SortBuilder;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
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
import java.util.*;
import java.util.stream.Stream;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin.BASE;
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

    @Inject
    AvailabilityService availabilityService;

    @Inject
    JPAStreamer jpaStreamer;
    @Inject
    WorkService workService;

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

        // Sikkerhed: kun Draft må opdateres
        if (!isDraft(invoice.getUuid())) throw new WebApplicationException(Response.Status.CONFLICT);

        Invoice.update("attention = ?1, bookingdate = ?2, clientaddresse = ?3, contractref = ?4, clientname = ?5, cvr = ?6, " +
                        "discount = ?7, ean = ?8, invoicedate = ?9, invoiceref = ?10, month = ?11, otheraddressinfo = ?12, " +
                        "projectname = ?13, projectref = ?14, projectuuid = ?15, specificdescription = ?16, status = ?17, " +
                        "invoicenumber = ?18, type = ?19, year = ?20, zipcity = ?21, company = ?22, currency = ?23, " +
                        "bonusConsultant = ?24, bonusConsultantApprovedStatus = ?25, bonusOverrideAmount = ?26, bonusOverrideNote = ?27, " +
                        "duedate = ?28, vat = ?29 WHERE uuid like ?30",
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
                invoice.getDuedate(),
                invoice.getVat(),
                invoice.getUuid());

        recalculateInvoiceItems(invoice);
        return invoice;
    }

    private void recalculateInvoiceItems(Invoice invoice) {
        var baseItems = invoice.getInvoiceitems().stream()
                .filter(ii -> ii.getOrigin() == null || ii.getOrigin() == BASE)
                .toList();

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
    }

    @Scheduled(every = "24h")
    public void migrateLegacyInvoices() {
        QuarkusTransaction.begin();
        List<Invoice> unprocessed = Invoice.listAll();
        QuarkusTransaction.commit();
        int migrated = unprocessed.size();
        for (Invoice invoice : unprocessed) {
            try {
                QuarkusTransaction.begin();
                recalculateInvoiceItems(invoice);
                log.debugf("Migrations left: %d", migrated--);
                QuarkusTransaction.commit();
            } catch (Exception e) {
                log.error("Failed to migrate invoice: " + invoice.getUuid(), e);
            }
        }
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public Invoice updateInvoiceStatus(Invoice invoice, InvoiceStatus status) {
                Invoice.update("status = ?1 WHERE uuid like ?2",
                status,
                invoice.getUuid());
        return invoice;
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

        if (draftInvoice.getType() != InvoiceType.CREDIT_NOTE && draftInvoice.getType() != InvoiceType.INTERNAL)
            recalculateInvoiceItems(draftInvoice);
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

        Invoice creditNote = new Invoice(invoice.getInvoicenumber(), InvoiceType.CREDIT_NOTE, invoice.getContractuuid(), invoice.getProjectuuid(),
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
                "Intern faktura knyttet til " + invoice.getInvoicenumber());
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
                0, InvoiceType.INTERNAL_SERVICE,
                "", "", "",
                0.0, month.getYear(), month.getMonthValue(),
                toCompany.getName(), toCompany.getAddress(), "",
                toCompany.getZipcode(), "", toCompany.getCvr(), "Tobias Kjølsen",
                month.plusMonths(1).withDayOfMonth(1).minusDays(1),
                month.plusMonths(1).withDayOfMonth(1).minusDays(1).plusMonths(1),
                "", "", ContractType.PERIOD, fromCompany, "DKK",
                "Intern faktura knyttet til " + month.getMonth().name());

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
}
