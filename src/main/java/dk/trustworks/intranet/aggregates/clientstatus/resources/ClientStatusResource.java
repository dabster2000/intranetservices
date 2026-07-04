package dk.trustworks.intranet.aggregates.clientstatus.resources;

import dk.trustworks.intranet.aggregates.clientstatus.dto.AccountManagerBriefRequest;
import dk.trustworks.intranet.aggregates.clientstatus.dto.AccountManagerBriefResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusDetailResponse;
import dk.trustworks.intranet.aggregates.clientstatus.dto.ClientStatusResponse;
import dk.trustworks.intranet.aggregates.clientstatus.services.AccountManagerBriefService;
import dk.trustworks.intranet.aggregates.clientstatus.services.ClientStatusService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
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

    @Inject
    ClientStatusService clientStatusService;

    @Inject
    AccountManagerBriefService accountManagerBriefService;

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

    private YearMonth parseEnd(String raw) {
        if (raw == null || raw.isBlank()) return YearMonth.now();
        try {
            return YearMonth.parse(raw.trim(), YYYYMM);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("end must be in YYYYMM format");
        }
    }
}
