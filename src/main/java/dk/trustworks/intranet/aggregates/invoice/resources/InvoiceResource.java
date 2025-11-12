package dk.trustworks.intranet.aggregates.invoice.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import dk.trustworks.intranet.aggregates.invoice.InvoiceGenerator;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceNote;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV1;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV2;
import dk.trustworks.intranet.aggregates.invoice.resources.dto.*;
import dk.trustworks.intranet.aggregates.invoice.services.InternalInvoiceControllingService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceNotesService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.invoice.services.v2.FinalizationService;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceMapperService;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine;
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
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.rest.client.annotation.ClientHeaderParam;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;

@JBossLog
@Tag(name = "invoice", description = "Invoice Management API - Supports both V1 (legacy) and V2 (clean separated status) formats")
@Path("/invoices")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
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
    InvoiceMapperService mapper;

    @Inject
    FinalizationService finalizationService;

    @Inject
    InvoiceStateMachine stateMachine;

    @GET
    @Operation(
        summary = "List invoices with pagination and filtering",
        description = "Returns invoices in V2 format (clean separated status) by default. Use format=v1 for legacy format."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "List of invoices",
            content = @Content(schema = @Schema(implementation = InvoiceDtoV2.class))),
        @APIResponse(responseCode = "400", description = "Invalid date format"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response list(
            @Parameter(description = "Start date filter (inclusive, ISO format: yyyy-MM-dd)")
            @QueryParam("fromdate") String fromdate,
            @Parameter(description = "End date filter (inclusive, ISO format: yyyy-MM-dd)")
            @QueryParam("todate") String todate,
            @Parameter(description = "Page number (0-indexed)")
            @QueryParam("page") @DefaultValue("0") int page,
            @Parameter(description = "Page size")
            @QueryParam("size") @DefaultValue("1000") int size,
            @Parameter(description = "Sort fields")
            @QueryParam("sort") List<String> sort,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default, clean separated status)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        List<Invoice> invoices = invoiceService.findPaged(
                dateIt(fromdate),            // can be null
                dateIt(todate),              // can be null
                page,
                size,
                sort                         // may be empty or blank
        );

        // Map to appropriate DTO format
        if ("v1".equalsIgnoreCase(format)) {
            List<InvoiceDtoV1> dtos = invoices.stream()
                    .map(mapper::toV1Dto)
                    .collect(Collectors.toList());
            return Response.ok(dtos)
                    .header("X-Deprecated-Format", "v1")
                    .header("X-Deprecation-Warning", "V1 format is deprecated. Use format=v2 or omit format parameter for clean API.")
                    .build();
        } else {
            List<InvoiceDtoV2> dtos = invoices.stream()
                    .map(mapper::toV2Dto)
                    .collect(Collectors.toList());
            return Response.ok(dtos).build();
        }
    }

    @GET
    @Path("/{invoiceuuid}")
    @Operation(
        summary = "Get single invoice by UUID",
        description = "Returns invoice in V2 format (clean separated status) by default. Use format=v1 for legacy format."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice found",
            content = @Content(schema = @Schema(implementation = InvoiceDtoV2.class))),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response findOne(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceuuid,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        Invoice invoice = invoiceService.findOneByUuid(invoiceuuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Invoice not found: " + invoiceuuid)
                    .build();
        }

        // Map to appropriate DTO format
        if ("v1".equalsIgnoreCase(format)) {
            InvoiceDtoV1 dto = mapper.toV1Dto(invoice);
            return Response.ok(dto)
                    .header("X-Deprecated-Format", "v1")
                    .header("X-Deprecation-Warning", "V1 format is deprecated. Use format=v2 or omit format parameter for clean API.")
                    .build();
        } else {
            InvoiceDtoV2 dto = mapper.toV2Dto(invoice);
            return Response.ok(dto).build();
        }
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
        var invoiceStatuses = parseEnumList(statuses, LifecycleStatus::valueOf);
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
        var invoiceStatuses = parseEnumList(statuses, LifecycleStatus::valueOf);
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
        // Only pass valid lifecycle statuses (DRAFT, CREATED)
        // Note: CREDIT_NOTE is now a type (InvoiceType), not a lifecycle status
        // Note: QUEUED is now a processing state (ProcessingState), not a lifecycle status
        return invoiceService.findInvoicesForSingleMonth(dateIt(month), "CREATED", "DRAFT");
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
        draftInvoice.getInvoiceitems().forEach(System.out::println);
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

    // DEPRECATED: Bonus fields removed from unified Invoice model during Phase 1 consolidation
    // Bonus data now managed separately through bonus service
    // Use PUT /{invoiceuuid}/bonusstatus/{bonusStatus} endpoint instead
    /*
    @PUT
    @Path("/{invoiceuuid}/bonusstatus")
    public void updateInvoiceStatus(Invoice invoice) {
        invoiceService.updateInvoiceBonusStatus(invoice);
    }
    */

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
            "invoiceNumber", invoice.getInvoicenumber() != null ? invoice.getInvoicenumber() : 0,
            "financeStatus", invoice.getFinanceStatus(),
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

    // ============================================================================
    // Invoice Lifecycle State Machine Operations (merged from V2)
    // ============================================================================

    /**
     * Finalize a draft invoice (DRAFT → CREATED).
     *
     * Finalization:
     * - Assigns invoice number (except PHANTOM type)
     * - Sets invoice date and due date
     * - Transitions to CREATED state
     * - PDF generation happens asynchronously
     *
     * @param invoiceUuid The invoice UUID
     * @param format Response format: 'v1' (legacy) or 'v2' (default)
     * @return The finalized invoice
     */
    @POST
    @Path("/{invoiceuuid}/finalize")
    @Transactional
    @Operation(
        summary = "Finalize invoice",
        description = "Finalize a draft invoice (DRAFT → CREATED). Assigns invoice number, sets dates, and transitions to CREATED state."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice finalized successfully",
            content = @Content(schema = @Schema(implementation = InvoiceDtoV2.class))),
        @APIResponse(responseCode = "400", description = "Invalid state transition"),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response finalizeInvoice(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceUuid,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        log.infof("Finalizing invoice %s", invoiceUuid);
        Invoice finalized = finalizationService.finalize(invoiceUuid);

        if ("v1".equalsIgnoreCase(format)) {
            return Response.ok(mapper.toV1Dto(finalized))
                    .header("X-Deprecated-Format", "v1")
                    .build();
        } else {
            return Response.ok(mapper.toV2Dto(finalized)).build();
        }
    }

    /**
     * Submit an invoice (CREATED → SUBMITTED).
     *
     * Marks the invoice as submitted to the customer.
     *
     * @param invoiceUuid The invoice UUID
     * @param format Response format: 'v1' (legacy) or 'v2' (default)
     * @return The submitted invoice
     */
    @POST
    @Path("/{invoiceuuid}/submit")
    @Transactional
    @Operation(
        summary = "Submit invoice",
        description = "Mark invoice as submitted to customer (CREATED → SUBMITTED)"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice submitted successfully"),
        @APIResponse(responseCode = "400", description = "Invalid state transition"),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response submitInvoice(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceUuid,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Invoice not found: " + invoiceUuid)
                    .build();
        }

        stateMachine.transition(invoice, LifecycleStatus.SUBMITTED);
        invoice.persist();

        log.infof("Submitted invoice %s", invoiceUuid);

        if ("v1".equalsIgnoreCase(format)) {
            return Response.ok(mapper.toV1Dto(invoice))
                    .header("X-Deprecated-Format", "v1")
                    .build();
        } else {
            return Response.ok(mapper.toV2Dto(invoice)).build();
        }
    }

    /**
     * Mark invoice as paid (SUBMITTED → PAID).
     *
     * Manually marks the invoice as paid (independent of finance_status).
     *
     * @param invoiceUuid The invoice UUID
     * @param format Response format: 'v1' (legacy) or 'v2' (default)
     * @return The paid invoice
     */
    @POST
    @Path("/{invoiceuuid}/pay")
    @Transactional
    @Operation(
        summary = "Mark as paid",
        description = "Mark invoice as paid (SUBMITTED → PAID). This is independent of finance_status."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice marked as paid"),
        @APIResponse(responseCode = "400", description = "Invalid state transition"),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response markAsPaid(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceUuid,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Invoice not found: " + invoiceUuid)
                    .build();
        }

        stateMachine.transition(invoice, LifecycleStatus.PAID);
        invoice.persist();

        log.infof("Marked invoice %s as paid", invoiceUuid);

        if ("v1".equalsIgnoreCase(format)) {
            return Response.ok(mapper.toV1Dto(invoice))
                    .header("X-Deprecated-Format", "v1")
                    .build();
        } else {
            return Response.ok(mapper.toV2Dto(invoice)).build();
        }
    }

    /**
     * Cancel an invoice (any state → CANCELLED).
     *
     * Cancels the invoice. This is a terminal state.
     *
     * @param invoiceUuid The invoice UUID
     * @param format Response format: 'v1' (legacy) or 'v2' (default)
     * @return The cancelled invoice
     */
    @POST
    @Path("/{invoiceuuid}/cancel")
    @Transactional
    @Operation(
        summary = "Cancel invoice",
        description = "Cancel an invoice (any state → CANCELLED). This is a terminal state."
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "Invoice cancelled"),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response cancelInvoice(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceUuid,
            @Parameter(description = "Response format: 'v1' (legacy) or 'v2' (default)")
            @QueryParam("format") @DefaultValue("v2") String format) {

        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Invoice not found: " + invoiceUuid)
                    .build();
        }

        stateMachine.transition(invoice, LifecycleStatus.CANCELLED);
        invoice.persist();

        log.infof("Cancelled invoice %s", invoiceUuid);

        if ("v1".equalsIgnoreCase(format)) {
            return Response.ok(mapper.toV1Dto(invoice))
                    .header("X-Deprecated-Format", "v1")
                    .build();
        } else {
            return Response.ok(mapper.toV2Dto(invoice)).build();
        }
    }

    /**
     * Get lifecycle state machine information for an invoice.
     *
     * Returns valid next states from current state.
     *
     * @param invoiceUuid The invoice UUID
     * @return Response with valid next states
     */
    @GET
    @Path("/{invoiceuuid}/state-machine")
    @Operation(
        summary = "Get state machine info",
        description = "Get valid next lifecycle states for an invoice based on its current state"
    )
    @APIResponses({
        @APIResponse(responseCode = "200", description = "State machine information"),
        @APIResponse(responseCode = "404", description = "Invoice not found"),
        @APIResponse(responseCode = "500", description = "Internal server error")
    })
    public Response getStateMachineInfo(
            @Parameter(description = "Invoice UUID", required = true)
            @PathParam("invoiceuuid") String invoiceUuid) {

        Invoice invoice = Invoice.findById(invoiceUuid);
        if (invoice == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Invoice not found: " + invoiceUuid)
                    .build();
        }

        LifecycleStatus current = invoice.getLifecycleStatus();
        LifecycleStatus[] validNextStates = stateMachine.getValidNextStates(current);
        boolean isTerminal = stateMachine.isTerminalState(current);

        return Response.ok()
                .entity(new StateMachineInfo(current, validNextStates, isTerminal))
                .build();
    }

    /**
     * State machine information DTO.
     */
    public static class StateMachineInfo {
        public LifecycleStatus currentState;
        public LifecycleStatus[] validNextStates;
        public boolean isTerminal;

        public StateMachineInfo(LifecycleStatus currentState, LifecycleStatus[] validNextStates, boolean isTerminal) {
            this.currentState = currentState;
            this.validNextStates = validNextStates;
            this.isTerminal = isTerminal;
        }
    }
}