package dk.trustworks.intranet.aggregates.invoice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import dk.trustworks.intranet.aggregates.invoice.InvoiceGenerator;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceControlHistory;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceNote;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.*;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceControllingService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceNotesService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.dto.ProjectSummary;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.model.enums.SalesApprovalStatus;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
    InternalInvoiceControllingService internalInvoiceControllingService;

    @Inject
    InvoiceNotesService invoiceNotesService;

    @Inject
    InvoiceGenerator invoiceGenerator;

    @Inject
    dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService uploadService;

    @Inject
    dk.trustworks.intranet.aggregates.invoice.services.InvoicePdfS3Service invoicePdfS3Service;

    @Inject
    jakarta.batch.operations.JobOperator jobOperator;

    @POST
    @Path("/admin/migrate-pdfs-to-s3")
    @Produces(MediaType.TEXT_PLAIN)
    @jakarta.annotation.security.PermitAll
    public Response triggerPdfMigration() {
        long executionId = jobOperator.start("invoice-pdf-migration", new java.util.Properties());
        return Response.ok("PDF migration job started. Execution ID: " + executionId).build();
    }

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
    @Path("/{invoiceuuid}")
    public Invoice findOne(@PathParam("invoiceuuid") String invoiceuuid) {
        return invoiceService.findOneByUuid(invoiceuuid);
    }

    @GET
    @Path("/{invoiceuuid}/pdf")
    @Produces("application/pdf")
    public Response downloadPdf(@PathParam("invoiceuuid") String invoiceuuid) {
        byte[] pdfBytes = invoiceService.getInvoicePdfBytes(invoiceuuid);
        if (pdfBytes == null || pdfBytes.length == 0) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("No PDF available for invoice: " + invoiceuuid)
                    .type(MediaType.TEXT_PLAIN)
                    .build();
        }
        Invoice invoice = invoiceService.findOneByUuid(invoiceuuid);
        String filename = (invoice.invoicenumber > 0 ? invoice.invoicenumber : "draft") + "_" +
                invoice.getType() + "-" + invoiceuuid + ".pdf";
        return Response.ok(pdfBytes, "application/pdf")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .build();
    }

    @GET
    @Path("/bonus-approval")
    public java.util.List<BonusApprovalRow> bonusApprovalPage(
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("50") int size,
            @QueryParam("statuses") java.util.List<String> statuses
    ) {
        // First try SalesApprovalStatus (PENDING/APPROVED/REJECTED)
        var bonusStatuses = parseEnumList(statuses, SalesApprovalStatus::valueOf);
        if (!bonusStatuses.isEmpty()) {
            return invoiceService.findBonusApprovalPageByBonusStatus(bonusStatuses, page, size);
        }

        // Backward compatibility: interpret as invoice lifecycle statuses (CREATED/PAID/...)
        var invoiceStatuses = parseEnumList(statuses, InvoiceStatus::valueOf);
        return invoiceService.findBonusApprovalPage(invoiceStatuses, page, size);
    }

    // New: count for the same filter
    @GET
    @Path("/bonus-approval/count")
    public long bonusApprovalCount(@QueryParam("statuses") java.util.List<String> statuses) {
        // First try SalesApprovalStatus (PENDING/APPROVED/REJECTED)
        var bonusStatuses = parseEnumList(statuses, SalesApprovalStatus::valueOf);
        if (!bonusStatuses.isEmpty()) {
            return invoiceService.countBonusApprovalByBonusStatus(bonusStatuses);
        }

        // Backward compatibility: interpret as invoice lifecycle statuses
        var invoiceStatuses = parseEnumList(statuses, InvoiceStatus::valueOf);
        return invoiceService.countBonusApproval(invoiceStatuses);
    }

    @GET
    @Path("/bonus-approval/{invoiceuuid}")
    public BonusApprovalRow bonusApprovalRow(@PathParam("invoiceuuid") String invoiceuuid) {
        return invoiceService.findBonusApprovalRow(invoiceuuid);
    }

    // ---------------- helpers ----------------

    /** Parse CSV and repeated query params into an enum list, ignoring invalid values. */
    private static <E extends Enum<E>> java.util.List<E> parseEnumList(
            java.util.List<String> raw, Function<String, E> parser) {
        if (raw == null || raw.isEmpty()) return java.util.List.of();
        java.util.List<E> out = new java.util.ArrayList<>();
        for (String token : raw) {
            if (token == null || token.isBlank()) continue;
            for (String part : token.split(",")) {
                String v = part.trim();
                if (v.isEmpty()) continue;
                try { out.add(parser.apply(v)); } catch (Exception ignore) { /* skip invalid */ }
            }
        }
        return out;
    }

    @GET
    @Path("/months/{month}")
    public List<Invoice> findByYearAndMonth(@PathParam("month") String month) {
        return invoiceService.findInvoicesForSingleMonth(dateIt(month));
    }

    @GET
    @Path("/search/bookingdate")
    public List<Invoice> getInvoicesByBookingdate(@QueryParam("fromdate") String fromdate, @QueryParam("todate") String todate) {
        return invoiceService.findByBookingDate(dateIt(fromdate), dateIt(todate));
    }

    @GET
    @Path("/search/number/{invoicenumber}")
    public Response findByInvoiceNumber(@PathParam("invoicenumber") int invoicenumber) {
        Optional<Invoice> invoice = Invoice.find("invoicenumber", invoicenumber).firstResultOptional();
        if (invoice.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "Invoice not found"))
                    .build();
        }
        Invoice inv = invoice.get();
        // Look up the client UUID via the contract
        String clientuuid = null;
        if (inv.contractuuid != null) {
            dk.trustworks.intranet.contracts.model.Contract contract =
                    dk.trustworks.intranet.contracts.model.Contract.findById(inv.contractuuid);
            if (contract != null) {
                clientuuid = contract.getClientuuid();
            }
        }
        return Response.ok(java.util.Map.of(
                "invoice", inv,
                "clientuuid", clientuuid != null ? clientuuid : ""
        )).build();
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
        } catch (InconsistantDataException ide) {
            log.warn("Data inconsistency detected during draft invoice creation", ide);
            throw ide;  // Let InconsistantDataExceptionMapper handle it
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
        System.out.print("INCOMING: ");
        draftInvoice.invoiceitems.forEach(System.out::println);
        if (draftInvoice.getUuid() != null && !invoiceuuid.equals(draftInvoice.getUuid())) {
            throw new WebApplicationException("Path and body uuid mismatch", Response.Status.BAD_REQUEST);
        }
        draftInvoice.setUuid(invoiceuuid);
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

    @GET
    @Path("/my-bonus")
    public List<MyBonusRow> myBonusPage(@QueryParam("useruuid") String useruuid,
                                        @QueryParam("from") String from,
                                        @QueryParam("to") String to,
                                        @QueryParam("page") @DefaultValue("0") int page,
                                        @QueryParam("size") @DefaultValue("50") int size,
                                        @QueryParam("statuses") List<String> statuses) {
        var st = parseEnumList(statuses, SalesApprovalStatus::valueOf);
        return invoiceService.findMyBonusPage(useruuid, st, dateIt(from), dateIt(to), page, size);
    }

    @GET
    @Path("/my-bonus/count")
    public long myBonusCount(@QueryParam("useruuid") String useruuid,
                             @QueryParam("from") String from,
                             @QueryParam("to") String to,
                             @QueryParam("statuses") List<String> statuses) {
        var st = parseEnumList(statuses, SalesApprovalStatus::valueOf);
        return invoiceService.countMyBonus(useruuid, st, dateIt(from), dateIt(to));
    }

    @GET
    @Path("/my-bonus/summary")
    public List<MyBonusFySum> myBonusSummary(@QueryParam("useruuid") String useruuid) {
        return invoiceService.myBonusFySummary(useruuid);
    }

    @GET
    @Path("/cross-company")
    public List<Invoice> findCrossCompanyInvoices(@QueryParam("fromdate") String fromdate,
                                                   @QueryParam("todate") String todate) {
        return internalInvoiceControllingService.findCrossCompanyInvoicesByDateRange(dateIt(fromdate), dateIt(todate));
    }

    /**
     * Returns client invoices that contain cross-company consultant lines within the
     * specified date range, bundled with an optional referring internal invoice.
     *
     * Semantics:
     * - fromdate inclusive; todate exclusive (aligns with existing /cross-company).
     * - For each client invoice (status CREATED, type INVOICE) that contains at least
     *   one cross-company consultant, include zero or one internal invoice (status
     *   in QUEUED or CREATED) where invoice_ref = client's invoicenumber.
     *
     * @param fromdate start date (inclusive), format yyyy-MM-dd
     * @param todate end date (exclusive), format yyyy-MM-dd
     * @return List of pairs (client + optional internal) with header data and lines.
     */
    @GET
    @Path("/cross-company/with-internal")
    public List<CrossCompanyInvoicePairDTO> findCrossCompanyInvoicesWithInternal(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate) {
        return internalInvoiceControllingService.findCrossCompanyInvoicesWithInternal(dateIt(fromdate), dateIt(todate));
    }

    /**
     * Returns client/internal invoice pairs where:
     * - The client invoice (CREATED, INVOICE) has at least one cross-company consultant line, and
     * - A referring internal invoice exists (INTERNAL, status in QUEUED/CREATED), and
     * - The sum of client invoice lines (hours * rate) is strictly less than the sum of the
     *   internal invoice lines.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * Note: Amounts are compared in the invoices' native currency without conversion.
     * Ensure client and internal invoices use the same currency if you rely on the comparison.
     *
     * @param fromdate start date (inclusive), format yyyy-MM-dd
     * @param todate end date (exclusive), format yyyy-MM-dd
     * @return list of pairs (client + internal) with header data and per-line cross-company flags
     */
    @GET
    @Path("/cross-company/with-internal/client-less-than-internal")
    public List<CrossCompanyInvoicePairDTO>
    findCrossCompanyClientLessThanInternal(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate
    ) {
        return internalInvoiceControllingService.findCrossCompanyClientLessThanInternal(dateIt(fromdate), dateIt(todate));
    }

    /**
     * Returns client invoices that contain cross-company consultant lines within the
     * specified date range but have no referring INTERNAL invoice (status in QUEUED/CREATED)
     * linked via invoice_ref.
     *
     * Semantics:
     * - fromdate inclusive; todate exclusive.
     * - Client invoices considered are status CREATED and type INVOICE.
     * - Cross-company determination uses userstatus as-of the invoice date.
     *
     * @param fromdate start date (inclusive), format yyyy-MM-dd
     * @param todate end date (exclusive), format yyyy-MM-dd
     * @return List of client invoices without a corresponding internal invoice
     */
    @GET
    @Path("/cross-company/without-internal")
    public List<SimpleInvoiceDTO>
    findCrossCompanyClientInvoicesWithoutInternal(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate
    ) {
        return internalInvoiceControllingService.findCrossCompanyClientInvoicesWithoutInternal(dateIt(fromdate), dateIt(todate));
    }

    /**
     * Returns pairs where the client invoice is a regular client invoice (type INVOICE)
     * that currently has status CREDIT_NOTE, contains at least one cross-company consultant
     * line, and has one INTERNAL invoice (status in QUEUED/CREATED) referencing it via invoice_ref.
     *
     * Cross-company determination uses userstatus as-of the invoice date and compares the
     * consultant company UUID against the issuing invoice's company UUID.
     *
     * Date window semantics: from inclusive, to exclusive.
     *
     * @param fromdate start date (inclusive), format yyyy-MM-dd
     * @param todate   end date (exclusive), format yyyy-MM-dd
     * @return list of pairs (client + internal) with header and line-level crossCompany flags
     */
    @GET
    @Path("/cross-company/clients-status-credit-note-with-internal")
    public List<CrossCompanyInvoicePairDTO>
    findCrossCompanyClientInvoicesStatusCreditNoteWithInternal(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate
    ) {
        return internalInvoiceControllingService.findCrossCompanyClientInvoicesStatusCreditNoteWithInternal(dateIt(fromdate), dateIt(todate));
    }

    /**
     * Returns client invoices (type INVOICE) that have more than one INTERNAL invoice
     * (status in QUEUED/CREATED) referencing them via invoice_ref. The date window applies
     * to the client's invoicedate and follows from inclusive, to exclusive semantics.
     *
     * @param fromdate start date (inclusive), format yyyy-MM-dd
     * @param todate   end date (exclusive), format yyyy-MM-dd
     * @return list of client invoices each bundled with all their internal invoices and a count
     */
    @GET
    @Path("/internal/multiple")
    public List<ClientWithInternalsDTO>
    findClientInvoicesWithMultipleInternals(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate
    ) {
        return internalInvoiceControllingService.findClientInvoicesWithMultipleInternals(dateIt(fromdate), dateIt(todate));
    }

    @POST
    @Path("/{invoiceuuid}/queue")
    @Transactional
    public Response queueInternalInvoice(@PathParam("invoiceuuid") String invoiceuuid, KeyValueDTO body) {
        log.infof("queueInternalInvoice: invoiceuuid=%s", invoiceuuid);
        try {
            invoiceService.queueInternalInvoice(invoiceuuid);
            return Response.ok().entity(new KeyValueDTO("result", "success")).build();
        } catch (WebApplicationException wae) {
            log.warn("Failed to queue invoice: " + wae.getMessage(), wae);
            throw wae;
        } catch (Exception e) {
            log.error("Unexpected error queuing invoice: " + invoiceuuid, e);
            return Response.serverError()
                    .entity("Failed to queue invoice: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Forces creation of a queued internal invoice immediately, bypassing the wait for referenced invoice to be PAID.
     *
     * This endpoint provides manual override of the normal queued invoice flow. The standard flow waits for the
     * referenced client invoice to reach economics_status = PAID before creating the internal invoice. This endpoint
     * allows immediate creation regardless of the referenced invoice status.
     *
     * The created invoice follows the same process as the batch job:
     * - Validates invoice is QUEUED and INTERNAL type
     * - Sets invoice date to today and due date to tomorrow
     * - Assigns invoice number and generates PDF
     * - Queues uploads to e-conomics for both issuing and debtor companies
     * - Processes uploads with automatic retry support on failure
     *
     * @param invoiceuuid UUID of the queued invoice to force-create
     * @return Response with success message or error details
     */
    @POST
    @Path("/{invoiceuuid}/force-create-queued")
    @Transactional
    public Response forceCreateQueuedInvoice(@PathParam("invoiceuuid") String invoiceuuid) {
        log.infof("forceCreateQueuedInvoice: invoiceuuid=%s (manual bypass)", invoiceuuid);
        try {
            // Create the invoice (assigns number, generates PDF, changes status to CREATED)
            Invoice createdInvoice = invoiceService.forceCreateQueuedInvoice(invoiceuuid);

            // Queue uploads for both issuing and debtor companies (same as batch job)
            uploadService.queueUploads(createdInvoice);

            // Process uploads immediately (failures will be retried by separate job)
            dk.trustworks.intranet.aggregates.invoice.services.InvoiceEconomicsUploadService.UploadResult result =
                uploadService.processUploads(createdInvoice.getUuid());

            log.infof("Invoice %d upload result: %d succeeded, %d failed (total: %d)",
                    createdInvoice.getInvoicenumber(),
                    result.successCount(), result.failedCount(), result.totalCount());

            String message = String.format(
                "Invoice %d created successfully. Uploads: %d succeeded, %d failed (total: %d)",
                createdInvoice.getInvoicenumber(),
                result.successCount(), result.failedCount(), result.totalCount()
            );

            if (result.allSucceeded()) {
                log.infof("Successfully created and uploaded invoice %d to all companies", createdInvoice.getInvoicenumber());
            } else if (result.partialSuccess()) {
                log.warnf("Invoice %d partially uploaded (%d of %d succeeded) - failures will be retried",
                        createdInvoice.getInvoicenumber(), result.successCount(), result.totalCount());
                message += ". Failed uploads will be retried automatically.";
            } else if (result.allFailed()) {
                log.errorf("Invoice %d creation succeeded but all uploads failed - will retry automatically",
                        createdInvoice.getInvoicenumber());
                message += ". All uploads failed but will retry automatically.";
            }

            return Response.ok()
                    .entity(message)
                    .build();

        } catch (WebApplicationException wae) {
            log.warn("Failed to force-create queued invoice: " + wae.getMessage(), wae);
            throw wae;
        } catch (Exception e) {
            log.error("Unexpected error force-creating queued invoice: " + invoiceuuid, e);
            return Response.serverError()
                    .entity("Failed to force-create invoice: " + e.getMessage())
                    .build();
        }
    }

    /**
     * Get e-conomics upload status for an invoice.
     * Shows all upload tasks (ISSUER/DEBTOR) with their current status.
     */
    @GET
    @Path("/{invoiceuuid}/economics-upload-status")
    public Response getEconomicsUploadStatus(@PathParam("invoiceuuid") String invoiceuuid) {
        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        var uploads = uploadService.getUploadsForInvoice(invoiceuuid);

        return Response.ok(java.util.Map.of(
            "invoiceUuid", invoiceuuid,
            "invoiceNumber", invoice.getInvoicenumber(),
            "economicsStatus", invoice.getEconomicsStatus(),
            "uploads", uploads.stream().map(u -> java.util.Map.of(
                "uuid", u.getUuid(),
                "uploadType", u.getUploadType(),
                "companyUuid", u.getCompanyuuid(),
                "status", u.getStatus(),
                "attemptCount", u.getAttemptCount(),
                "maxAttempts", u.getMaxAttempts(),
                "lastAttemptAt", u.getLastAttemptAt() != null ? u.getLastAttemptAt().toString() : null,
                "voucherNumber", u.getVoucherNumber(),
                "lastError", u.getLastError() != null ? u.getLastError() : ""
            )).toList()
        )).build();
    }

    /**
     * Manually trigger e-conomics upload retry for an invoice.
     * Useful for urgent cases or after fixing e-conomics connectivity.
     */
    @POST
    @Path("/{invoiceuuid}/retry-economics-upload")
    @Transactional
    public Response retryEconomicsUpload(@PathParam("invoiceuuid") String invoiceuuid) {
        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        log.infof("Manual retry triggered for invoice %s", invoiceuuid);

        var result = uploadService.processUploads(invoiceuuid);

        return Response.ok(java.util.Map.of(
            "message", "Upload retry processed",
            "successCount", result.successCount(),
            "failedCount", result.failedCount(),
            "totalCount", result.totalCount()
        )).build();
    }

    /**
     * Get overall e-conomics upload statistics.
     * Useful for monitoring/dashboard.
     */
    @GET
    @Path("/economics-upload-stats")
    public Response getEconomicsUploadStats() {
        var stats = uploadService.getUploadStats();
        return Response.ok(stats).build();
    }

    /**
     * Update invoice control status for CLIENT invoices.
     * Requires X-Requested-By header with user UUID for audit trail.
     *
     * @param invoiceuuid The invoice UUID
     * @param userUuid User UUID from X-Requested-By header
     * @param request The control status update request
     * @return Response with updated control status and audit details
     */
    @PUT
    @Path("/{invoiceuuid}/control-status")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Transactional
    public Response updateControlStatus(
            @PathParam("invoiceuuid") String invoiceuuid,
            @HeaderParam("X-Requested-By") String userUuid,
            UpdateControlStatusRequest request) {

        // Validate user UUID header
        if (userUuid == null || userUuid.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(java.util.Map.of("error", "X-Requested-By header is required"))
                    .build();
        }

        // Find invoice
        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "Invoice not found"))
                    .build();
        }

        // Update control status fields
        invoice.controlStatus = request.controlStatus();
        invoice.controlNote = request.controlNote();
        invoice.controlStatusUpdatedAt = LocalDateTime.now();
        invoice.controlStatusUpdatedBy = userUuid;

        // Persist changes
        invoice.persist();

        // Create history entry
        InvoiceControlHistory historyEntry = new InvoiceControlHistory(
                invoice.uuid,
                request.controlStatus(),
                request.controlNote(),
                userUuid
        );
        historyEntry.persist();

        log.infof("Updated control status for invoice %s to %s by user %s",
                invoiceuuid, request.controlStatus(), userUuid);

        // Build response with audit details
        UpdateControlStatusResponse response = new UpdateControlStatusResponse(
                invoice.uuid,
                invoice.controlStatus,
                invoice.controlNote,
                invoice.controlStatusUpdatedAt,
                invoice.controlStatusUpdatedBy,
                "Control status updated successfully"
        );

        return Response.ok(response).build();
    }

    /**
     * Get invoice control status history.
     * Returns all historical status changes for an invoice in reverse chronological order.
     *
     * @param invoiceuuid The invoice UUID
     * @return List of control status history entries
     */
    @GET
    @Path("/{invoiceuuid}/control-history")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getControlHistory(@PathParam("invoiceuuid") String invoiceuuid) {
        // Find invoice to verify it exists
        Invoice invoice = Invoice.findById(invoiceuuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(java.util.Map.of("error", "Invoice not found"))
                    .build();
        }

        // Fetch history entries
        List<InvoiceControlHistory> historyEntries = InvoiceControlHistory.findByInvoiceUuid(invoiceuuid);

        // Convert to DTOs
        List<InvoiceControlHistoryEntry> response = historyEntries.stream()
                .map(entry -> new InvoiceControlHistoryEntry(
                        entry.id,
                        entry.controlStatus,
                        entry.controlNote,
                        entry.changedAt,
                        entry.changedBy
                ))
                .toList();

        return Response.ok(response).build();
    }

    // ──────────────────────────────────────────────────────────────
    // Temporary: Invoice item recovery endpoints (data-loss fix)
    // ──────────────────────────────────────────────────────────────

    @GET
    @Path("/admin/recovery/affected")
    @Produces(MediaType.APPLICATION_JSON)
    public Response listAffectedInvoices() {
        var rows = invoiceService.findInternalInvoicesWithMissingBaseItems();
        return Response.ok(rows).build();
    }

    @POST
    @Path("/admin/recovery/{invoiceuuid}")
    @Transactional
    public Response recoverInvoiceItems(@PathParam("invoiceuuid") String invoiceuuid) {
        int inserted = invoiceService.recoverBaseItemsForInvoice(invoiceuuid);
        return Response.ok(java.util.Map.of(
                "invoiceuuid", invoiceuuid,
                "insertedCount", inserted
        )).build();
    }
}