package dk.trustworks.intranet.aggregates.invoice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import dk.trustworks.intranet.aggregates.invoice.InvoiceGenerator;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.dto.ProjectSummary;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@JBossLog
@Tag(name = "invoice")
@Path("/invoices")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@ClientHeaderParam(name="Authorization", value="{generateRequestId}")
public class InvoiceResource {

    @Inject
    InvoiceService invoiceService;

    @Inject
    InvoiceGenerator invoiceGenerator;

    @GET
    public List<Invoice> findByPeriod(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        if (fromdate == null || todate == null) return invoiceService.findAll();
        return InvoiceService.findWithFilter(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/months/{month}")
    public List<Invoice> findByYearAndMonth(@PathParam("month") String month) {
        return invoiceService.findInvoicesForSingleMonth(dateIt(month), "CREATED", "CREDIT_NOTE", "DRAFT");
    }

    @GET
    @Path("/search/bookingdate")
    public List<Invoice> getInvoicesByBookingdate(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return invoiceService.findByBookingDate(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/candidates/months/{month}")
    public List<ProjectSummary> loadProjectSummaryByYearAndMonth(@PathParam("month") String strMonth) {
        LocalDate month = dateIt(strMonth).withDayOfMonth(1);
        return invoiceGenerator.loadProjectSummaryByYearAndMonth(month);
    }

    @GET
    @Path("/drafts")
    public List<Invoice> findDraftsByPeriod(@QueryParam("fromdate") Optional<String> fromdate, @QueryParam("todate") Optional<String> todate) {
        if(fromdate.isPresent() && todate.isPresent())
            return InvoiceService.findWithFilter(dateIt(fromdate.get()), dateIt(todate.get()));
        else
            return invoiceService.findAll();
    }

    @POST
    @Path("/drafts")
    @Transactional
    public Invoice createDraftInvoice(@QueryParam("contractuuid") String contractuuid, @QueryParam("projectuuid") String projectuuid, @QueryParam("month") String month, @QueryParam("type") String type) {
        System.out.println("InvoiceResource.createDraftInvoice");
        System.out.println("contractuuid = " + contractuuid + ", projectuuid = " + projectuuid + ", month = " + month + ", type = " + type);
        LocalDate localDate = dateIt(month);
        return invoiceGenerator.createDraftInvoiceFromProject(contractuuid, projectuuid, localDate, type);
    }

    @PUT
    @Path("/drafts")
    public Invoice updateDraftInvoice(Invoice draftInvoice) {
        System.out.println("InvoiceResource.updateDraftInvoice");
        System.out.println("draftInvoice = " + draftInvoice);
        return invoiceService.updateDraftInvoice(draftInvoice);
    }

    @DELETE
    @Path("/drafts/{invoiceuuid}")
    public void deleteInvoice(@PathParam("invoiceuuid") String invoiceuuid) {
        invoiceService.deleteDraftInvoice(invoiceuuid);
    }

    @POST
    @Transactional
    public Invoice createInvoice(Invoice draftInvoice) throws JsonProcessingException {
        System.out.println("InvoiceResource.createInvoice");
        System.out.println("draftInvoice = " + draftInvoice);
        return invoiceService.createInvoice(draftInvoice);
    }

    @POST
    @Path("/regenerate/{invoiceuuid}")
    @Transactional
    public void regenerateInvoicePdf(@PathParam("invoiceuuid") String invoiceuuid) throws JsonProcessingException {
        invoiceService.regenerateInvoicePdf(invoiceuuid);
    }

    @POST
    @Path("/phantoms")
    public Invoice createPhantomInvoice(Invoice draftInvoice) throws JsonProcessingException {
        return invoiceService.createPhantomInvoice(draftInvoice);
    }

    @POST
    @Path("/creditnotes")
    public Invoice createCreditNote(Invoice draftInvoice) {
        return invoiceService.createCreditNote(draftInvoice);
    }

    @POST
    @Path("/internal/companies/{companyuuid}")
    public void createInternalInvoiceDraft(@PathParam("companyuuid") String companyuuid, Invoice invoice) {
        invoiceService.createInternalInvoiceDraft(companyuuid, invoice);
    }

    @POST
    @Path("/{invoiceuuid}/reference")
    public void updateInvoiceReference(@PathParam("invoiceuuid") String invoiceuuid, InvoiceReference invoiceReference) {
        log.info("InvoiceResource.updateInvoiceReference");
        log.info("invoiceuuid = " + invoiceuuid + ", invoiceReference = " + invoiceReference);
        invoiceService.updateInvoiceReference(invoiceuuid, invoiceReference);
    }

    @POST
    @Path("/internalservices")
    public void createInternalServiceInvoiceDraft(@QueryParam("fromCompany") String fromCompanyuuid, @QueryParam("toCompany") String toCompanyuuid, @QueryParam("month") String month) {
        invoiceService.createInternalServiceInvoiceDraft(fromCompanyuuid, toCompanyuuid, dateIt(month));
    }
}