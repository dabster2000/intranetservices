package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.resources.dto.EInvoicingListItem;
import dk.trustworks.intranet.aggregates.invoice.services.EInvoicingService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Lists invoices sent via EAN for the E-Invoicing tab.
 *
 * SPEC-INV-001 S8.10.
 */
@Tag(name = "accounting")
@Path("/accounting/e-invoicing")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"invoices:read"})
@SecurityRequirement(name = "jwt")
public class EInvoicingResource {

    @Inject
    EInvoicingService eInvoicingService;

    /**
     * Returns booked invoices that were delivered via EAN within the
     * given date range.
     *
     * @param from start of range (inclusive), required
     * @param to   end of range (inclusive), required
     */
    @GET
    public Response list(
            @QueryParam("from") LocalDate from,
            @QueryParam("to") LocalDate to) {

        if (from == null || to == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Both 'from' and 'to' query parameters are required"))
                    .build();
        }

        List<EInvoicingListItem> items = eInvoicingService.listEanInvoices(from, to);
        return Response.ok(items).build();
    }
}
