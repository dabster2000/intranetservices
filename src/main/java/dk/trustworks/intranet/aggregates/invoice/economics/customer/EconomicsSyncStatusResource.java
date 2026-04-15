package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.dto.ClientSyncStatusDto;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * Exposes per-client e-conomic sync status. Returns one row per configured
 * Trustworks company / agreement so the frontend sync badge can render the
 * aggregate state (green = all OK, amber = some PENDING/UNPAIRED, red = any
 * ABANDONED).
 *
 * <p>Scope-guarded under {@code invoices:*} — consistent with
 * {@link EconomicsCustomerPairingResource}. Admin clients inherit access via
 * {@code AdminScopeAugmentor.ALL_SCOPES}. SPEC-INV-001 §7.1 Phase G2, §8.6.
 */
@Path("/economics/sync-status")
@Produces(MediaType.APPLICATION_JSON)
public class EconomicsSyncStatusResource {

    @Inject
    EconomicsCustomerSyncService syncService;

    @GET
    @RolesAllowed({"invoices:read", "invoices:write"})
    public List<ClientSyncStatusDto> status(@QueryParam("clientUuid") String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            throw new jakarta.ws.rs.BadRequestException("clientUuid query parameter is required");
        }
        return syncService.statusFor(clientUuid);
    }
}
