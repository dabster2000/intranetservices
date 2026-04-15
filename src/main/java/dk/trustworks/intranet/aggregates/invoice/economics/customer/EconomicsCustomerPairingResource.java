package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.AutoRunResultDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingCandidateDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRequestDto;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.PairingRowDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for Phase G1 client ↔ e-conomic customer pairing. All
 * endpoints are scope-guarded under {@code accountants:*} — reads require
 * {@code accountants:read}, writes require {@code accountants:write}.
 * SPEC-INV-001 §7.1, §7.4, §8.9.1.
 */
@Path("/economics/customers")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class EconomicsCustomerPairingResource {

    @Inject
    EconomicsCustomerPairingService service;

    @GET
    @Path("/pairing")
    @RolesAllowed({"accountants:read", "accountants:write"})
    public List<PairingRowDto> listPairing(@QueryParam("companyUuid") UUID companyUuid) {
        return service.listPairingRows(companyUuid);
    }

    @POST
    @Path("/pair")
    @RolesAllowed("accountants:write")
    public Response pairManually(@Valid PairingRequestDto request) {
        service.pairManually(request);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/pair")
    @RolesAllowed("accountants:write")
    public Response unpair(@QueryParam("clientUuid") UUID clientUuid,
                           @QueryParam("companyUuid") UUID companyUuid) {
        service.unpair(clientUuid, companyUuid);
        return Response.noContent().build();
    }

    @POST
    @Path("/pair/auto-run")
    @RolesAllowed("accountants:write")
    public AutoRunResultDto autoRun(@QueryParam("companyUuid") UUID companyUuid) {
        return service.autoRun(companyUuid);
    }

    @GET
    @Path("/search")
    @RolesAllowed({"accountants:read", "accountants:write"})
    public List<PairingCandidateDto> search(@QueryParam("companyUuid") UUID companyUuid,
                                            @QueryParam("q") String query) {
        return service.searchEconomicsCustomers(companyUuid, query);
    }

    @POST
    @Path("/pair/{clientUuid}/{companyUuid}/create")
    @RolesAllowed("accountants:write")
    public Response createAndPair(@PathParam("clientUuid") UUID clientUuid,
                                  @PathParam("companyUuid") UUID companyUuid) {
        PairingRowDto row = service.createAndPair(clientUuid, companyUuid);
        return Response.status(Response.Status.CREATED).entity(row).build();
    }
}
