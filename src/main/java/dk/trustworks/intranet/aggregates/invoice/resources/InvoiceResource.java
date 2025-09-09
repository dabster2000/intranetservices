package dk.trustworks.intranet.aggregates.invoice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import dk.trustworks.intranet.aggregates.invoice.InvoiceGenerator;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceNote;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceNotesService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.dto.ProjectSummary;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

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
    InvoiceNotesService invoiceNotesService;

    @Inject
    InvoiceGenerator invoiceGenerator;

    @GET
    public List<Invoice> list(@QueryParam("fromdate") String fromdate,
                              @QueryParam("todate")   String todate,
                              @QueryParam("page")     @DefaultValue("0")  int page,
                              @QueryParam("size")     @DefaultValue("1000") int size,
                              @QueryParam("sort")     List<String> sort) {

        return invoiceService.findPaged(
                dateIt(fromdate),            // can be null
                dateIt(todate),              // can be null
                page,
                size,
                sort                         // may be empty or blank
        );
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
    @Path("/count")
    public long countInvoices() {
        log.debug("countInvoices");
        return invoiceService.countInvoices();
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
    public Response createDraftInvoice(@QueryParam("contractuuid") String contractuuid,
                                       @QueryParam("projectuuid") String projectuuid,
                                       @QueryParam("month") String month,
                                       @QueryParam("type") String type) {
        log.infof("createDraftInvoice contractuuid=%s, projectuuid=%s, month=%s, type=%s",
                contractuuid, projectuuid, month, type);

        if(contractuuid == null || contractuuid.isBlank() ||
                projectuuid == null || projectuuid.isBlank() ||
                month == null || month.isBlank() ||
                type == null || type.isBlank()) {
            log.warn("Missing required parameters when creating draft invoice");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("contractuuid, projectuuid, month and type are required")
                    .build();
        }

        try {
            LocalDate localDate = dateIt(month);
            Invoice invoice = invoiceGenerator.createDraftInvoiceFromProject(contractuuid, projectuuid, localDate, type);
            log.info("Draft invoice created: " + invoice.getUuid());
            return Response.ok(invoice).build();
        } catch (WebApplicationException wae) {
            log.warn("Draft invoice creation failed", wae);
            throw wae;
        } catch (Exception e) {
            log.error("Failed to create draft invoice", e);
            return Response.serverError().entity("Failed to create draft invoice").build();
        }
    }

    @PUT
    @Path("/{invoiceuuid}")
    public Invoice updateDraftInvoice(@PathParam("invoiceuuid") String invoiceuuid, Invoice draftInvoice) {
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
        log.debug("InvoiceResource.createInvoice");
        log.debugf("draftInvoice = " + draftInvoice);
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

    @GET
    @Path("/internalservices")
    public List<Invoice> findInternalServiceInvoices(@QueryParam("page") @DefaultValue("0") int page,
                                                      @QueryParam("size") @DefaultValue("1000") int size,
                                                      @QueryParam("sort") List<String> sort) {
        return invoiceService.findInternalServicesPaged(page, size, sort);
    }

    @GET
    @Path("/internalservices/months/{month}")
    public List<Invoice> findInternalServiceInvoicesByMonth(@PathParam("month") String month) {
        return invoiceService.findInternalServiceInvoicesByMonth(month);
    }

    @POST
    @Path("/internalservices")
    public void createInternalServiceInvoiceDraft(@QueryParam("fromCompany") String fromCompanyuuid, @QueryParam("toCompany") String toCompanyuuid, @QueryParam("month") String month) {
        log.info("InvoiceResource.createInternalServiceInvoiceDraft");
        log.info("fromCompanyuuid = " + fromCompanyuuid + ", toCompanyuuid = " + toCompanyuuid + ", month = " + month);
        invoiceService.createInternalServiceInvoiceDraft(fromCompanyuuid, toCompanyuuid, dateIt(month));
    }

    @PUT
    @Path("/{invoiceuuid}/bonusstatus/{bonusStatus}")
    public void updateInvoiceStatus(@PathParam("invoiceuuid") String invoiceuuid, @PathParam("bonusStatus") String bonusStatus) {
        invoiceService.updateInvoiceStatus(invoiceuuid, SalesApprovalStatus.valueOf(bonusStatus));
    }

    @PUT
    @Path("/{invoiceuuid}/bonusstatus")
    public void updateInvoiceStatus(Invoice invoice) {
        invoiceService.updateInvoiceBonusStatus(invoice);
    }

    @GET
    @Path("/notes")
    public InvoiceNote getInvoiceNote(@QueryParam("contractuuid") String contractuuid, @QueryParam("projectuuid") String projectuuid, @QueryParam("month") String month) {
        return invoiceNotesService.getInvoiceNoteByClientProjectMonth(contractuuid, projectuuid, DateUtils.dateIt(month));
    }

    @PUT
    @Path("/notes")
    public void createOrUpdateInvoiceNote(InvoiceNote invoiceNote) {
        invoiceNotesService.createOrUpdateInvoiceNote(invoiceNote);
    }
}