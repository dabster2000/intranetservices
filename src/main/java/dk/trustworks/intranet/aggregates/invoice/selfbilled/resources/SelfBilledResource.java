package dk.trustworks.intranet.aggregates.invoice.selfbilled.resources;

import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.AssignRequest;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.ConsultantPeriodRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.HistoryRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.LinkRequest;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledAssignmentDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledDocumentDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledDocumentsResponse;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SelfBilledSourceDTO;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.SettleRequest;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.dto.UnlinkedInternalRow;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.model.AssignmentSourceType;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.HistoryReconciliationService;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledAssignmentService;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledCodeResolver;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledImportService;
import dk.trustworks.intranet.aggregates.invoice.selfbilled.services.SelfBilledSettlementService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Self-billed settlement workbench API (spec §5.2). All reads invoices:read, all
 * writes invoices:write. The acting user comes from X-Requested-By (R3) and is
 * recorded on every assignment, link, and settle (AC7). Consultant identity is
 * masked when the caller lacks users:read (mirrors InvoiceResource.maskSettlementPreview).
 */
@Path("/invoices/cross-company/selfbilled")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SelfBilledResource {

    @Inject SelfBilledImportService importService;
    @Inject SelfBilledAssignmentService assignmentService;
    @Inject SelfBilledSettlementService settlementService;
    @Inject HistoryReconciliationService historyService;
    @Inject SelfBilledCodeResolver codeResolver;
    @Inject RequestHeaderHolder requestHeaderHolder;
    @Inject ScopeContext scopeContext;

    // ── roster + capture ─────────────────────────────────────────────

    /** Workbench roster: the configured self-billing sources. */
    @GET @Path("/sources")
    @RolesAllowed({"invoices:read"})
    public List<SelfBilledSourceDTO> sources() {
        return assignmentService.sources();
    }

    /** Capture e-conomic self-billing docs for the window. Idempotent on entry_number; preserves human state. */
    @POST @Path("/capture")
    @RolesAllowed({"invoices:write"})
    public Map<String, Integer> capture(@QueryParam("from") String from, @QueryParam("to") String to) {
        return importService.capture(parseDate(from, "from"), parseDate(to, "to"));
    }

    // ── documents (Invoices tab) ─────────────────────────────────────

    /** Worklist: voucher-netted documents + status + suggestions + coverage (AC3) + tie-out (AC9). */
    @GET @Path("/documents")
    @RolesAllowed({"invoices:read"})
    public SelfBilledDocumentsResponse documents(@QueryParam("client") String client,
                                                 @QueryParam("from") String from,
                                                 @QueryParam("to") String to) {
        requireClient(client);
        SelfBilledDocumentsResponse r = assignmentService.documents(client, parseDate(from, "from"), parseDate(to, "to"));
        return maskDocuments(r);
    }

    /** Human assignment (replace semantics; N entries = split). The human gate of AC2. */
    @POST @Path("/documents/{lineUuid}/assign")
    @RolesAllowed({"invoices:write"})
    public void assign(@PathParam("lineUuid") String lineUuid, AssignRequest req) {
        assignmentService.assignVoucher(lineUuid, req == null ? null : req.assignments(),
                requireActor(), AssignmentSourceType.HUMAN);
    }

    @DELETE @Path("/documents/{lineUuid}/assignments")
    @RolesAllowed({"invoices:write"})
    public void clearAssignments(@PathParam("lineUuid") String lineUuid) {
        assignmentService.clearAssignments(lineUuid, requireActor());
    }

    @POST @Path("/documents/{lineUuid}/same-company")
    @RolesAllowed({"invoices:write"})
    public void sameCompany(@PathParam("lineUuid") String lineUuid) {
        assignmentService.markSameCompany(lineUuid, requireActor());
    }

    @POST @Path("/documents/{lineUuid}/ignore")
    @RolesAllowed({"invoices:write"})
    public void ignore(@PathParam("lineUuid") String lineUuid) {
        assignmentService.markIgnored(lineUuid, requireActor());
    }

    @POST @Path("/documents/{lineUuid}/unmark")
    @RolesAllowed({"invoices:write"})
    public void unmark(@PathParam("lineUuid") String lineUuid) {
        assignmentService.unmark(lineUuid, requireActor());
    }

    /** Bulk-accept high-confidence SAME-COMPANY suggestions only (AC2). Returns {accepted: n}. */
    @POST @Path("/assignments/accept-suggested")
    @RolesAllowed({"invoices:write"})
    public Map<String, Integer> acceptSuggested(@QueryParam("client") String client,
                                                @QueryParam("from") String from,
                                                @QueryParam("to") String to) {
        requireClient(client);
        int n = assignmentService.acceptSuggestedSameCompany(client, parseDate(from, "from"),
                parseDate(to, "to"), requireActor());
        return Map.of("accepted", n);
    }

    /** Confirm one code→consultant mapping (kept from the capture stack). */
    @POST @Path("/code-map")
    @RolesAllowed({"invoices:write"})
    public void confirmMapping(@QueryParam("agreement") String agreement, @QueryParam("account") int account,
                               @QueryParam("code") String code, @QueryParam("consultant") String consultant) {
        codeResolver.confirmMapping(agreement, account, code, consultant, requireActor());
    }

    // ── consultants (review lens) + settle ───────────────────────────

    /** Per cross-company (consultant, work-period): assigned / settled / delta / work cross-check. */
    @GET @Path("/consultants")
    @RolesAllowed({"invoices:read"})
    public List<ConsultantPeriodRow> consultants(@QueryParam("client") String client,
                                                 @QueryParam("fromYm") int fromYm,
                                                 @QueryParam("toYm") int toYm) {
        requireClient(client);
        requireYmWindow(fromYm, toYm);
        return maskConsultants(settlementService.consultantRows(client, fromYm, toYm));
    }

    /** Book the pass-through internal / credit note for one (consultant, work-period). */
    @POST @Path("/settle")
    @RolesAllowed({"invoices:write"})
    public List<String> settle(SettleRequest req) {
        if (req == null || req.clientUuid() == null || req.consultantUuid() == null) {
            throw new WebApplicationException("clientUuid and consultantUuid are required",
                    Response.Status.BAD_REQUEST);
        }
        requireWorkPeriod(req.workYear(), req.workMonth());
        return settlementService.settleConsultantPeriod(req, requireActor());
    }

    // ── history + link queue ─────────────────────────────────────────

    @GET @Path("/history")
    @RolesAllowed({"invoices:read"})
    public List<HistoryRow> history(@QueryParam("client") String client,
                                    @QueryParam("fromYm") int fromYm, @QueryParam("toYm") int toYm) {
        requireClient(client);
        requireYmWindow(fromYm, toYm);
        return maskHistory(historyService.historyRows(client, fromYm, toYm));
    }

    @GET @Path("/internals/unlinked")
    @RolesAllowed({"invoices:read"})
    public List<UnlinkedInternalRow> unlinked() {
        return maskUnlinked(historyService.unlinkedInternals());
    }

    @POST @Path("/internals/{invoiceUuid}/link")
    @RolesAllowed({"invoices:write"})
    public void link(@PathParam("invoiceUuid") String invoiceUuid, LinkRequest req) {
        if (req == null || req.clientUuid() == null || req.consultantUuid() == null) {
            throw new WebApplicationException("clientUuid and consultantUuid are required",
                    Response.Status.BAD_REQUEST);
        }
        requireWorkPeriod(req.workYear(), req.workMonth());
        historyService.linkInternal(invoiceUuid, req, requireActor());
    }

    // ── helpers ──────────────────────────────────────────────────────

    private String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        return actor;
    }

    private static void requireClient(String client) {
        if (client == null || client.isBlank()) {
            throw new WebApplicationException("client is required", Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Validate a yyyyMM settlement window. fromYm/toYm are primitive query params, so an omitted or
     * non-numeric value arrives as 0; without this guard a malformed value would silently widen or
     * empty the BETWEEN filter (information disclosure / silent-empty results), and a reversed window
     * would return nothing. Mirrors requireWorkPeriod's plausibility-check idiom.
     */
    private static void requireYmWindow(int fromYm, int toYm) {
        requireYm(fromYm, "fromYm");
        requireYm(toYm, "toYm");
        if (fromYm > toYm) {
            throw new WebApplicationException("fromYm must be <= toYm (yyyyMM)", Response.Status.BAD_REQUEST);
        }
    }

    private static void requireYm(int ym, String name) {
        int month = ym % 100;
        if (ym < 197001 || ym > 209912 || month < 1 || month > 12) {
            throw new WebApplicationException(name + " is required (yyyyMM, 197001-209912)",
                    Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Validate a body-supplied work period. workYear/workMonth are primitive ints, so a JSON
     * body omitting them deserializes to 0; without this guard settle would build LocalDate.of(0,0,1)
     * (500) and link would stamp settlement_year=0/month=0 — orphaning the internal from any real
     * period's settled(). Mirrors InvoiceResource.settlementKeyOrBadRequest's plausibility check.
     */
    private static void requireWorkPeriod(int workYear, int workMonth) {
        if (workYear < 1 || workYear > 9999 || workMonth < 1 || workMonth > 12) {
            throw new WebApplicationException("workYear and workMonth are required (workMonth 1-12)",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static LocalDate parseDate(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException(name + " is required (yyyy-MM-dd)", Response.Status.BAD_REQUEST);
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new WebApplicationException("Invalid " + name + " — use yyyy-MM-dd", Response.Status.BAD_REQUEST);
        }
    }

    // Data boundary: strip consultant identity without users:read (mirrors maskSettlementPreview).
    // sourceText/fakturaNumber/suggestedCode are raw e-conomic free text and may inherently carry
    // initials — that is the issuer's own booking text, not our resolved identity (accepted boundary).
    private SelfBilledDocumentsResponse maskDocuments(SelfBilledDocumentsResponse r) {
        if (scopeContext.hasScope("users:read")) return r;
        List<SelfBilledDocumentDTO> docs = r.documents().stream().map(d -> new SelfBilledDocumentDTO(
                d.lineUuid(), d.voucherNumber(), d.bookingDate(), d.amount(), d.sourceText(), d.fakturaNumber(),
                d.suggestedCode(), d.suggestedWorkYear(), d.suggestedWorkMonth(),
                null, null, d.suggestionConfidence(), d.suggestionReason(),
                d.status(), d.crossCompany(), d.entryCount(),
                d.assignments().stream().map(a -> new SelfBilledAssignmentDTO(a.uuid(), null, null,
                        a.workYear(), a.workMonth(), a.shareAmount(), a.source(), null, a.assignedAt())).toList()))
                .toList();
        return new SelfBilledDocumentsResponse(docs, r.coverage(), r.tieOut());
    }

    private List<ConsultantPeriodRow> maskConsultants(List<ConsultantPeriodRow> rows) {
        if (scopeContext.hasScope("users:read")) return rows;
        return rows.stream().map(c -> new ConsultantPeriodRow(null, null, c.workYear(), c.workMonth(),
                c.issuerCompanyUuid(), c.issuerCompanyName(), c.assigned(), c.settled(), c.delta(),
                c.workValue(), c.canSettle(), c.unlinkedCandidates())).toList();
    }

    private List<HistoryRow> maskHistory(List<HistoryRow> rows) {
        if (scopeContext.hasScope("users:read")) return rows;
        return rows.stream().map(h -> new HistoryRow(null, null, h.workYear(), h.workMonth(),
                h.booked(), h.assigned(), h.proposedDelta(), h.internalUuids())).toList();
    }

    // itemNames carry the attributed consultant's FULL NAME (InternalInvoiceLineGenerator labels
    // each line with attribution.consultantName); description (specificdescription) is free text
    // that may also name the consultant. Strip both without users:read; keep every other component.
    private List<UnlinkedInternalRow> maskUnlinked(List<UnlinkedInternalRow> rows) {
        if (scopeContext.hasScope("users:read")) return rows;
        return rows.stream().map(u -> new UnlinkedInternalRow(u.invoiceUuid(), u.invoicenumber(),
                u.type(), u.status(), u.issuerCompanyName(), u.debtorCompanyName(), u.invoicedate(),
                null, u.total(), List.of())).toList();
    }
}
