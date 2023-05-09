package dk.trustworks.intranet.invoiceservice.services;

import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.model.InvoiceItem;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceType;
import dk.trustworks.intranet.invoiceservice.network.InvoiceAPI;
import dk.trustworks.intranet.invoiceservice.network.dto.InvoiceDTO;
import dk.trustworks.intranet.invoiceservice.utils.StringUtils;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.configuration.ProfileManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.transaction.Transactional;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@JBossLog
@ApplicationScoped
public class InvoiceService {

    @Inject
    EntityManager em;

    @Inject
    @RestClient
    InvoiceAPI invoiceAPI;

    @Inject
    EconomicsInvoiceService economicsInvoiceService;

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
                    .collect(Collectors.toList());

        }
        return result;
    }

    @SuppressWarnings("unchecked")
    /**
     * @Param fromdate Date is included
     * @Param todate Date is not included
     * @Param type
     */
    public List<Invoice> findByBookingDate(LocalDate fromdate, LocalDate todate) {
        //String[] finalType = (type!=null && type.length>0)?type:new String[]{"CREATED", "CREDIT_NOTE", "DRAFT"};
        LocalDate finalFromdate = fromdate!=null?fromdate:LocalDate.of(2014,1,1);
        LocalDate finalTodate = todate!=null?todate:LocalDate.now();

        List<Invoice> invoices = Invoice.find("invoicedate >= ?1 AND invoicedate < ?2 AND bookingdate = '1900-01-01'",
                finalFromdate, finalTodate).list();

        invoices.addAll(Invoice.find("bookingdate >= ?1 AND bookingdate < ?2",
                finalFromdate, finalTodate).list());


        /*
        String sql = "SELECT * FROM invoices i WHERE " +
                "i.invoicedate >= " + stringIt(finalFromdate) + " AND i.invoicedate < " + stringIt(finalTodate) +
                " AND i.bookingdate = '1900-01-01' " +
                "AND i.status IN ('"+String.join("','", finalType)+"');";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        invoices.addAll(em.createNativeQuery("SELECT * FROM invoices i WHERE " +
                "i.bookingdate >= " + stringIt(finalFromdate) + " AND i.bookingdate <= "+ stringIt(finalTodate) +" " +
                "AND i.status IN ('"+String.join("','", finalType)+"');", Invoice.class).getResultList());

         */
        return invoices;
    }

    public double calculateInvoiceSumByMonth(LocalDate month) {
        String sql = "select sum(if(type = 0, (ii.rate*ii.hours), -(ii.rate*ii.hours))) sum from invoiceitems ii " +
                "LEFT JOIN invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE status NOT LIKE 'DRAFT' AND EXTRACT(YEAR_MONTH FROM if(i.bookingdate != '1900-01-01', i.bookingdate, i.invoicedate)) = "+stringIt(month, "yyyyMM")+"; ";
        Object singleResult = em.createNativeQuery(sql).getSingleResult();
        return singleResult!=null?((Number) singleResult).doubleValue():0.0;
    }

    public List<GraphKeyValue> calculateInvoiceSumByPeriod() {
        String sql = "SELECT i.uuid uuid, EXTRACT(YEAR_MONTH FROM if(i.bookingdate != '1900-01-01', i.bookingdate, i.invoicedate)) as description, if(type = 0, sum(ii.rate*ii.hours), -sum(ii.rate*ii.hours)) value from invoiceitems ii " +
                "LEFT JOIN invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE status != 'DRAFT' " +
                "GROUP BY cmonth ORDER BY cmonth; ";
        return (List<GraphKeyValue>) em.createNativeQuery(sql, GraphKeyValue.class).getResultList();
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

    @SuppressWarnings("unchecked")
    public List<Invoice> findInvoicesForSingleMonthByBookingDate(LocalDate month, String... type) {
        LocalDate date = month.withDayOfMonth(1);
        String sql = "SELECT * FROM invoices i WHERE " +
                "i.invoicedate >= " + stringIt(date) + " AND i.invoicedate < " + stringIt(date.plusMonths(1)) +
                " AND i.bookingdate = '1900-01-01' " +
                "AND i.status IN ('"+String.join("','", type)+"');";
        List<Invoice> invoices = em.createNativeQuery(sql, Invoice.class).getResultList();
        invoices.addAll(em.createNativeQuery("SELECT * FROM invoices i WHERE " +
                "i.bookingdate >= " + stringIt(date) + " AND i.bookingdate < " + stringIt(date.plusMonths(1)) + " " +
                "AND i.status IN ('"+String.join("','", type)+"');", Invoice.class).getResultList());
        return Collections.unmodifiableList(invoices);
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
        //if(!isDraft(invoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+invoice.getUuid());
        Invoice.update("attention = ?1, " +
                        "bookingdate = ?2, " +
                        "clientaddresse = ?3, " +
                        "contractref = ?4, " +
                        "clientname = ?5, " +
                        "cvr = ?6, " +
                        "discount = ?7, " +
                        "ean = ?8, " +
                        "invoicedate = ?9, " +
                        "invoice_ref = ?10, " +
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
                        "zipcity = ?21 " +
                        "WHERE uuid like ?22 ",
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
                invoice.getUuid());

        InvoiceItem.delete("invoiceuuid LIKE ?1", invoice.getUuid());
        invoice.getInvoiceitems().forEach(invoiceItem -> {
            invoiceItem.setInvoiceuuid(invoice.uuid);
        });
        InvoiceItem.persist(invoice.invoiceitems);

        return invoice;
    }


    public Invoice createInvoice(Invoice draftInvoice) {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        draftInvoice.setStatus(InvoiceStatus.CREATED);
        draftInvoice.invoicenumber = getMaxInvoiceNumber() + 1;
        draftInvoice.pdf = createInvoicePdf(draftInvoice);
        saveInvoice(draftInvoice);
        uploadToEconomics(draftInvoice);
        //createEmitter.send(draftInvoice);
        return draftInvoice;
    }

    @Transactional
    public Invoice createPhantomInvoice(Invoice draftInvoice) {
        if(!isDraft(draftInvoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+draftInvoice.getUuid());
        draftInvoice.setStatus(InvoiceStatus.CREATED);
        draftInvoice.invoicenumber = 0;
        draftInvoice.pdf = createInvoicePdf(draftInvoice);
        saveInvoice(draftInvoice);
        if(!"dev".equals(ProfileManager.getActiveProfile())) {
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

        Invoice creditNote = new Invoice(InvoiceType.CREDIT_NOTE, invoice.getContractuuid(), invoice.getProjectuuid(),
                invoice.getProjectname(), invoice.getDiscount(), invoice.getYear(), invoice.getMonth(), invoice.getClientname(),
                invoice.getClientaddresse(), invoice.getOtheraddressinfo(), invoice.getZipcity(),
                invoice.getEan(), invoice.getCvr(), invoice.getAttention(), LocalDate.now(),
                invoice.getProjectref(), invoice.getContractref(),
                "Kreditnota til faktura " + StringUtils.convertInvoiceNumberToString(invoice.invoicenumber));

        creditNote.invoicenumber = 0;
        for (InvoiceItem invoiceitem : invoice.invoiceitems) {
            creditNote.getInvoiceitems().add(new InvoiceItem(invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), invoice.uuid));
        }
        Invoice.persist(creditNote);
        creditNote.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
        uploadToEconomics(creditNote);
        return creditNote;
    }

    public byte[] createInvoicePdf(Invoice invoice) {
        InvoiceDTO invoiceDTO = new InvoiceDTO(invoice);
        invoice.getInvoiceitems().forEach(invoiceItem -> System.out.println("invoiceItem.itemname = " + invoiceItem.itemname));
        return invoiceAPI.createInvoicePDF(invoiceDTO);
    }

    public void regenerateInvoicePdf(String invoiceuuid) {
        Invoice invoice = Invoice.findById(invoiceuuid);
        invoice.pdf = invoiceAPI.createInvoicePDF(new InvoiceDTO(invoice));
        invoice.persist();
    }

    public Integer getMaxInvoiceNumber() {
        Optional<Invoice> latestInvoice = Invoice.findAll(Sort.descending("invoicenumber")).firstResultOptional();
        return latestInvoice.map(invoice -> invoice.invoicenumber).orElse(1);
    }

    @Transactional
    public void deleteDraftInvoice(String invoiceuuid) {
        if(isDraft(invoiceuuid)) Invoice.deleteById(invoiceuuid);
    }

    private boolean isDraft(String invoiceuuid) {
        return Invoice.find("uuid LIKE ?1 AND status LIKE ?2", invoiceuuid, InvoiceStatus.DRAFT).count() > 0;
    }

    @Transactional
    public void updateInvoiceReference(String invoiceuuid, InvoiceReference invoiceReference) {
        Invoice.update("bookingdate = ?1, referencenumber = ?2 WHERE uuid like ?3 ", invoiceReference.getBookingdate(), invoiceReference.getReferencenumber(), invoiceuuid);
    }

    public void uploadToEconomics(Invoice invoice) {
        if(invoice.invoicenumber == 0) return;
        try {
            Response response = economicsInvoiceService.sendVoucher(invoice);
            response.close();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    int invoicePage = 1;

    @Transactional
    //@Scheduled(every = "3m")
    public void init() {
        int skipCount = (invoicePage - 1) * 10;
        log.info("InvoiceService scheduler started, (skipCount = "+skipCount+"), (invoicePage = "+invoicePage+")...");
        Stream<Invoice> invoices = Invoice.streamAll(Sort.by("invoicedate").descending().and("uuid").ascending());
        invoices
                .filter(invoice -> !invoice.getStatus().equals(InvoiceStatus.DRAFT) && invoice.pdf != null && !invoice.getContractuuid().equals("receipt"))
                .skip(skipCount)
                .limit(10)
                .forEach(invoice -> {
            log.info("Processing invoice: "+invoice.uuid);
            regenerateInvoicePdf(invoice.uuid);
            log.info("...saved");
        });
        invoicePage++;
    }
}
