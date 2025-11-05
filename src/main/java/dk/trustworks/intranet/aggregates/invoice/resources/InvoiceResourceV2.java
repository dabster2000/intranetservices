package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.model.InvoiceV2;
import dk.trustworks.intranet.aggregates.invoice.network.dto.InvoiceDtoV2;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.repositories.InvoiceV2Repository;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceMapperService;
import dk.trustworks.intranet.aggregates.invoice.services.v2.FinalizationService;
import dk.trustworks.intranet.aggregates.invoice.services.v2.InvoiceStateMachine;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.logging.Log;
import io.quarkus.panache.common.Parameters;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API V2 for invoice management.
 *
 * This is the clean v2 API with separated status fields:
 * - lifecycle_status: DRAFT, CREATED, SUBMITTED, PAID, CANCELLED
 * - finance_status: NONE, UPLOADED, BOOKED, PAID, ERROR
 * - processing_state: IDLE, QUEUED
 *
 * All endpoints accept and return InvoiceDtoV2 with clean field names.
 */
@Path("/api/v2/invoices")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "invoice-v2", description = "Invoice API V2 - Clean separated status fields")
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class InvoiceResourceV2 {

    @Inject
    InvoiceV2Repository repository;

    @Inject
    InvoiceMapperService mapper;

    @Inject
    FinalizationService finalizationService;

    @Inject
    InvoiceStateMachine stateMachine;

    /**
     * List invoices with optional filtering.
     *
     * @param fromdate Filter by invoice date >= (ISO format: YYYY-MM-DD)
     * @param todate Filter by invoice date <= (ISO format: YYYY-MM-DD)
     * @param type Filter by invoice type
     * @param lifecycleStatus Filter by lifecycle status
     * @param page Page number (0-indexed)
     * @param size Page size (default: 1000)
     * @return List of invoices matching filters
     */
    @GET
    @Operation(summary = "List invoices", description = "List all invoices with optional filtering")
    public List<InvoiceDtoV2> list(
            @QueryParam("fromdate") String fromdate,
            @QueryParam("todate") String todate,
            @QueryParam("type") InvoiceType type,
            @QueryParam("lifecycleStatus") LifecycleStatus lifecycleStatus,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("1000") int size) {

        PanacheQuery<InvoiceV2> query;

        // Build query based on date filters
        if (fromdate != null && todate != null) {
            LocalDate from = LocalDate.parse(fromdate);
            LocalDate to = LocalDate.parse(todate);
            query = repository.find("invoicedate >= ?1 AND invoicedate <= ?2", from, to);
        } else if (fromdate != null) {
            LocalDate from = LocalDate.parse(fromdate);
            query = repository.find("invoicedate >= ?1", from);
        } else if (todate != null) {
            LocalDate to = LocalDate.parse(todate);
            query = repository.find("invoicedate <= ?1", to);
        } else {
            query = repository.findAll();
        }

        // Apply type filter
        if (type != null) {
            String queryStr = query.toString();
            if (queryStr.contains("WHERE")) {
                query = (PanacheQuery<InvoiceV2>) repository.find(queryStr + " AND type = :type",
                        Parameters.with("type", type));
            } else {
                query = repository.find("type", type);
            }
        }

        // Apply lifecycle status filter
        if (lifecycleStatus != null) {
            String queryStr = query.toString();
            if (queryStr.contains("WHERE")) {
                query = (PanacheQuery<InvoiceV2>) repository.find(queryStr + " AND lifecycleStatus = :status",
                        Parameters.with("status", lifecycleStatus));
            } else {
                query = repository.find("lifecycleStatus", lifecycleStatus);
            }
        }

        return query.page(page, size)
                   .list()
                   .stream()
                   .map(mapper::toV2Dto)
                   .collect(Collectors.toList());
    }

    /**
     * Get a single invoice by UUID.
     *
     * @param invoiceUuid The invoice UUID
     * @return The invoice DTO
     */
    @GET
    @Path("/{invoiceUuid}")
    @Operation(summary = "Get invoice", description = "Get a single invoice by UUID")
    public InvoiceDtoV2 findOne(@PathParam("invoiceUuid") String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
        }
        return mapper.toV2Dto(invoice);
    }

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
     * @return The finalized invoice
     */
    @POST
    @Path("/{invoiceUuid}/finalize")
    @Transactional
    @Operation(summary = "Finalize invoice", description = "Finalize a draft invoice (DRAFT → CREATED)")
    public InvoiceDtoV2 finalize(@PathParam("invoiceUuid") String invoiceUuid) {
        Log.infof("Finalizing invoice %s", invoiceUuid);
        InvoiceV2 finalized = finalizationService.finalize(invoiceUuid);
        return mapper.toV2Dto(finalized);
    }

    /**
     * Submit an invoice (CREATED → SUBMITTED).
     *
     * Marks the invoice as submitted to the customer.
     *
     * @param invoiceUuid The invoice UUID
     * @return The submitted invoice
     */
    @POST
    @Path("/{invoiceUuid}/submit")
    @Transactional
    @Operation(summary = "Submit invoice", description = "Mark invoice as submitted (CREATED → SUBMITTED)")
    public InvoiceDtoV2 submit(@PathParam("invoiceUuid") String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
        }

        stateMachine.transition(invoice, LifecycleStatus.SUBMITTED);
        repository.persist(invoice);

        Log.infof("Submitted invoice %s", invoiceUuid);
        return mapper.toV2Dto(invoice);
    }

    /**
     * Mark invoice as paid (SUBMITTED → PAID).
     *
     * Manually marks the invoice as paid (independent of finance_status).
     *
     * @param invoiceUuid The invoice UUID
     * @return The paid invoice
     */
    @POST
    @Path("/{invoiceUuid}/pay")
    @Transactional
    @Operation(summary = "Mark as paid", description = "Mark invoice as paid (SUBMITTED → PAID)")
    public InvoiceDtoV2 pay(@PathParam("invoiceUuid") String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
        }

        stateMachine.transition(invoice, LifecycleStatus.PAID);
        repository.persist(invoice);

        Log.infof("Marked invoice %s as paid", invoiceUuid);
        return mapper.toV2Dto(invoice);
    }

    /**
     * Cancel an invoice (any state → CANCELLED).
     *
     * Cancels the invoice. This is a terminal state.
     *
     * @param invoiceUuid The invoice UUID
     * @return The cancelled invoice
     */
    @POST
    @Path("/{invoiceUuid}/cancel")
    @Transactional
    @Operation(summary = "Cancel invoice", description = "Cancel an invoice (any state → CANCELLED)")
    public InvoiceDtoV2 cancel(@PathParam("invoiceUuid") String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
        }

        stateMachine.transition(invoice, LifecycleStatus.CANCELLED);
        repository.persist(invoice);

        Log.infof("Cancelled invoice %s", invoiceUuid);
        return mapper.toV2Dto(invoice);
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
    @Path("/{invoiceUuid}/state-machine")
    @Operation(summary = "Get state machine info", description = "Get valid next lifecycle states")
    public Response getStateMachineInfo(@PathParam("invoiceUuid") String invoiceUuid) {
        InvoiceV2 invoice = repository.findById(invoiceUuid);
        if (invoice == null) {
            throw new WebApplicationException(
                "Invoice not found: " + invoiceUuid,
                Response.Status.NOT_FOUND
            );
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
