package dk.trustworks.intranet.aggregates.clientstatus.resources;

import dk.trustworks.intranet.aggregates.clientstatus.ClientStatusMath;
import dk.trustworks.intranet.aggregates.clientstatus.dto.*;
import dk.trustworks.intranet.aggregates.clientstatus.services.AccountManagerBriefService;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientMonthControlService;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientMonthControlService.Scope;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientStatusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "invoice-controlling")
@Path("/invoice-controlling/client-status")
@RequestScoped
@Produces(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"invoices:read"})
public class ClientStatusResource {

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Pattern CLIENT_UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final int MAX_NOTE_LENGTH = 2000;

    @Inject
    ClientStatusService clientStatusService;

    @Inject
    AccountManagerBriefService accountManagerBriefService;

    @Inject
    ClientMonthControlService clientMonthControlService;

    @GET
    public Response getGrid(@QueryParam("end") String end) {
        YearMonth endMonth = parseEnd(end);
        log.infof("GET /invoice-controlling/client-status: end=%s", endMonth);
        ClientStatusResponse result = clientStatusService.getClientStatus(endMonth);
        return Response.ok(result).build();
    }

    @GET
    @Path("/detail")
    public Response getDetail(@QueryParam("client") String client,
                              @QueryParam("year") int year,
                              @QueryParam("month") int month) {
        if (client == null || client.isBlank()) throw new BadRequestException("client is required");
        if (!CLIENT_UUID.matcher(client).matches()) throw new BadRequestException("client must be a valid UUID");
        if (month < 1 || month > 12) throw new BadRequestException("month must be 1..12");
        if (year < 2000 || year > 2100) throw new BadRequestException("year out of range");
        log.infof("GET /invoice-controlling/client-status/detail: client=%s year=%d month=%d", client, year, month);
        ClientStatusDetailResponse result = clientStatusService.getClientStatusDetail(client, year, month);
        return Response.ok(result).build();
    }

    @POST
    @Path("/account-manager-brief")
    @Consumes(APPLICATION_JSON)
    public Response accountManagerBrief(AccountManagerBriefRequest request) {
        if (request == null || request.accountManagerUuid() == null || request.accountManagerUuid().isBlank()) {
            throw new BadRequestException("accountManagerUuid is required");
        }
        if (!CLIENT_UUID.matcher(request.accountManagerUuid()).matches()) {
            throw new BadRequestException("accountManagerUuid must be a valid UUID");
        }
        int minorFloor = request.minorAnomalyFloorDkk() == null ? 0
                : Math.max(0, Math.min(500_000, request.minorAnomalyFloorDkk()));
        int reportMonths = request.reportMonths() == null ? 12
                : Math.max(1, Math.min(12, request.reportMonths()));
        AccountManagerBriefResponse result = accountManagerBriefService.generate(
                request.accountManagerUuid(), request.end(), request.framing(),
                minorFloor, reportMonths,
                Boolean.TRUE.equals(request.hideShiftedInvoicing()));
        return Response.ok(result).build();
    }

    /**
     * Upsert the controlling state (approval snapshot and/or editable note) of one client-month
     * cell. Approving freezes the current expected/invoiced values so drift can be detected later.
     * Requires the {@code X-Requested-By} header (user UUID) for the audit trail.
     */
    @PUT
    @Path("/control")
    @RolesAllowed({"invoices:write"})
    @Consumes(APPLICATION_JSON)
    public Response upsertControl(@HeaderParam("X-Requested-By") String userUuid,
                                  ClientMonthControlRequest request) {
        requireActorUuid(userUuid);
        if (request == null) throw new BadRequestException("Request body is required");
        if (request.clientUuid() == null || !CLIENT_UUID.matcher(request.clientUuid()).matches()) {
            throw new BadRequestException("clientUuid must be a valid UUID");
        }
        if (request.approved() == null && request.note() == null) {
            throw new BadRequestException("At least one of 'approved' or 'note' must be provided");
        }
        if (request.note() != null && request.note().length() > MAX_NOTE_LENGTH) {
            throw new BadRequestException("note must be at most " + MAX_NOTE_LENGTH + " characters");
        }
        YearMonth month = parseMonthKey(request.monthKey());
        rejectProvisionalOrFuture(month);

        int year = month.getYear();
        int monthValue = month.getMonthValue();
        double[] ei = clientStatusService.currentExpectedInvoiced(request.clientUuid(), year, monthValue);

        log.infof("PUT /invoice-controlling/client-status/control: client=%s month=%s approved=%s noteSet=%s by=%s",
                request.clientUuid(), request.monthKey(), request.approved(), request.note() != null, userUuid);

        ClientStatusControlDto dto = clientMonthControlService.upsert(
                request.clientUuid(), month.atDay(1), request.approved(), request.note(),
                ei[0], ei[1], userUuid);
        return Response.ok(dto).build();
    }

    /**
     * Approve every eligible client-month cell in a month in one call. {@code FULL_ONLY} approves
     * only fully-billed cells; {@code ALL_REMAINING} approves every non-approved cell with activity.
     * Requires the {@code X-Requested-By} header (user UUID) for the audit trail.
     */
    @POST
    @Path("/control/bulk-approve")
    @RolesAllowed({"invoices:write"})
    @Consumes(APPLICATION_JSON)
    public Response bulkApprove(@HeaderParam("X-Requested-By") String userUuid,
                                ClientMonthBulkApproveRequest request) {
        requireActorUuid(userUuid);
        if (request == null) throw new BadRequestException("Request body is required");
        YearMonth month = parseMonthKey(request.month());
        rejectProvisionalOrFuture(month);
        Scope scope = parseScope(request.scope());

        log.infof("POST /invoice-controlling/client-status/control/bulk-approve: month=%s scope=%s by=%s",
                request.month(), scope, userUuid);

        ClientMonthControlService.BulkResult result =
                clientMonthControlService.bulkApprove(month, scope, userUuid);
        List<String> names = result.clientNames();
        return Response.ok(new ClientMonthBulkApproveResponse(
                request.month(), scope.name(), result.approvedCount(), names)).build();
    }

    /** The audit actor is persisted verbatim into approval/history rows — it must be a real user UUID. */
    private void requireActorUuid(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("X-Requested-By header is required");
        }
        if (!CLIENT_UUID.matcher(userUuid).matches()) {
            throw new BadRequestException("X-Requested-By must be a valid user UUID");
        }
    }

    private YearMonth parseEnd(String raw) {
        if (raw == null || raw.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(raw.trim(), YYYYMM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("end must be in YYYYMM format");
        }
    }

    private YearMonth parseMonthKey(String raw) {
        if (raw == null || raw.isBlank()) throw new BadRequestException("month is required (YYYYMM)");
        try {
            return YearMonth.parse(raw.trim(), YYYYMM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("month must be in YYYYMM format");
        }
    }

    /** Reject controlling actions on a month that is still provisional (which includes the future). */
    private void rejectProvisionalOrFuture(YearMonth month) {
        String monthKey = String.format("%04d%02d", month.getYear(), month.getMonthValue());
        if (ClientStatusMath.isProvisional(monthKey, LocalDate.now())) {
            throw new BadRequestException(
                    "month " + monthKey + " is not yet final (provisional or in the future) and cannot be controlled");
        }
    }

    private Scope parseScope(String raw) {
        if (raw == null || raw.isBlank()) throw new BadRequestException("scope is required (FULL_ONLY|ALL_REMAINING)");
        try {
            return Scope.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("scope must be FULL_ONLY or ALL_REMAINING");
        }
    }
}
