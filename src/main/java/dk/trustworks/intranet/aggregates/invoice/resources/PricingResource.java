// src/main/java/dk/trustworks/intranet/aggregates/invoice/resources/PricingResource.java
package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;

import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;

@Path("/pricing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"SYSTEM"})
public class PricingResource {

    @Inject PricingEngine pricingEngine;

    @GET
    @Path("/preview/{invoiceuuid}")
    public Response preview(@PathParam("invoiceuuid") String invoiceuuid) {
        return preview(Invoice.findById(invoiceuuid));
    }


    @POST
    @Path("/preview")
    public Response preview(Invoice draft) {
        // Bypass for kreditnota
        if (draft.getType() != null && (draft.getType().name().equals("CREDIT_NOTE") || draft.getType().name().equals("INTERNAL"))) {
            return Response.ok(draft).build();
        }
        Map<String, String> cti = new HashMap<>();
        ContractTypeItem.<ContractTypeItem>find("contractuuid", draft.getContractuuid())
                .list().forEach(ct -> cti.put(ct.getKey(), ct.getValue()));

        var pr = pricingEngine.price(draft, cti);

        // Byg et svar-objekt som Invoice + transient totals + syntetiske linjer
        draft.sumBeforeDiscounts = pr.sumBeforeDiscounts.doubleValue();
        draft.sumAfterDiscounts  = pr.sumAfterDiscounts.doubleValue();
        draft.vatAmount          = pr.vatAmount.doubleValue();
        draft.grandTotal         = pr.grandTotal.doubleValue();
        draft.calculationBreakdown = pr.breakdown;

        // Til UI preview: vis base + syntetiske (men persistér ikke)
        var combined = new ArrayList<>(draft.getInvoiceitems());
        combined.addAll(pr.syntheticItems);
        draft.invoiceitems = combined;

        return Response.ok(draft).build();
    }
}
