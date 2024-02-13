package dk.trustworks.intranet.invoiceservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.expenseservice.services.EconomicsInvoiceService;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.model.InvoiceItem;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.invoiceservice.model.enums.InvoiceType;
import dk.trustworks.intranet.invoiceservice.network.InvoiceAPI;
import dk.trustworks.intranet.invoiceservice.network.dto.InvoiceDTO;
import dk.trustworks.intranet.invoiceservice.utils.StringUtils;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.panache.common.Sort;
import io.quarkus.runtime.configuration.ProfileManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
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
    UserService userService;

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

    public double calculateInvoiceSumByMonthWorkWasDone(String companyuuid, LocalDate month) {
        String sql = "select sum(if(type = 0, (ii.rate*ii.hours), -(ii.rate*ii.hours))) sum from invoiceitems ii " +
                "LEFT JOIN invoices i on i.uuid = ii.invoiceuuid " +
                "WHERE status NOT LIKE 'DRAFT' AND companyuuid = '"+companyuuid+"' AND year = "+month.getYear()+" AND month = "+(month.getMonthValue()-1)+"; ";
        Object singleResult = em.createNativeQuery(sql).getSingleResult();
        return singleResult!=null?((Number) singleResult).doubleValue():0.0;
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
        System.out.println("InvoiceService.updateDraftInvoice");
        //if(!isDraft(invoice.getUuid())) throw new RuntimeException("Invoice is not a draft invoice: "+invoice.getUuid());
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
                        "isBonusConsultantApproved = ?25 " +
                        "WHERE uuid like ?26 ",
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
                invoice.isBonusConsultantApproved(),
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

        // Create internal invoice drafts
        List<User> users = userService.listAll(true);
        Invoice internalInvoice = new Invoice(InvoiceType.INVOICE, draftInvoice.getContractuuid(), draftInvoice.getProjectuuid(),
                draftInvoice.getProjectname(), draftInvoice.getDiscount(), draftInvoice.getYear(), draftInvoice.getMonth(), draftInvoice.getCompany().getName(),
                draftInvoice.getCompany().getAddress(), "", draftInvoice.getCompany().getZipcode() + " " + draftInvoice.getCompany().getCity(),
                "", draftInvoice.getCompany().getCvr(), "", draftInvoice.getInvoicedate(),
                draftInvoice.getProjectref(), draftInvoice.getContractref(), draftInvoice.getCompany(), draftInvoice.getCurrency(),
                "Intern faktura knyttet til " + draftInvoice.getInvoicenumber());
        for (InvoiceItem invoiceitem : draftInvoice.getInvoiceitems()) {
            if(invoiceitem.getRate() == 0.0 || invoiceitem.hours == 0.0) continue;
            Optional<User> optionalUser = users.stream().filter(u -> u.getFullname().equals(invoiceitem.getItemname())).findFirst();
            if(optionalUser.isEmpty()) continue;
            User user = optionalUser.get();
        }

        return draftInvoice;
    }

    @Transactional
    public Invoice createPhantomInvoice(Invoice draftInvoice) throws JsonProcessingException {
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
                invoice.getProjectref(), invoice.getContractref(), invoice.getCompany(), invoice.getCurrency(),
                "Kreditnota til faktura " + StringUtils.convertInvoiceNumberToString(invoice.invoicenumber), invoice.getBonusConsultant(), invoice.isBonusConsultantApproved());

        creditNote.invoicenumber = 0;
        for (InvoiceItem invoiceitem : invoice.invoiceitems) {
            creditNote.getInvoiceitems().add(new InvoiceItem(invoiceitem.getItemname(), invoiceitem.getDescription(), invoiceitem.getRate(), invoiceitem.getHours(), invoice.uuid));
        }
        Invoice.persist(creditNote);
        creditNote.getInvoiceitems().forEach(invoiceItem -> InvoiceItem.persist(invoiceItem));
        uploadToEconomics(creditNote);
        return creditNote;
    }

    public byte[] createInvoicePdf(Invoice invoice) throws JsonProcessingException {
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(new InvoiceDTO(invoice));
        return invoiceAPI.createInvoicePDF(json);
    }

    public void regenerateInvoicePdf(String invoiceuuid) throws JsonProcessingException {
        Invoice invoice = Invoice.findById(invoiceuuid);
        ObjectMapper o = new ObjectMapper();
        String json = o.writeValueAsString(new InvoiceDTO(invoice));
        invoice.pdf = invoiceAPI.createInvoicePDF(json);
        invoice.persist();
    }

    public Integer getMaxInvoiceNumber(Invoice invoice) {
        Optional<Invoice> latestInvoice = Invoice.find("company = ?1", Sort.descending("invoicenumber"), invoice.getCompany()).firstResultOptional();
        //Optional<Invoice> latestInvoice = Invoice.findAll(Sort.descending("invoicenumber")).firstResultOptional();
        return latestInvoice.map(i -> i.invoicenumber).orElse(1);
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
        if(invoice.invoicenumber == 0 || !invoice.getCompany().getCvr().equals("35648941")) return;
        try {
            economicsInvoiceService.sendVoucher(invoice);
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

}
