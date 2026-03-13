// src/main/java/dk/trustworks/intranet/aggregates/invoice/resources/PricingResource.java
package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;

import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.*;
import java.util.stream.Collectors;

@Path("/pricing")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"invoices:read"})
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
        // Bypass for kreditnota (credit notes keep their stored values as-is)
        if (draft.getType() != null && draft.getType().name().equals("CREDIT_NOTE")) {
            return Response.ok(draft).build();
        }
        // Internal invoices: compute basic totals from line items (no pricing rules)
        if (draft.getType() != null && draft.getType().name().equals("INTERNAL")) {
            double sum = draft.getInvoiceitems().stream()
                    .mapToDouble(item -> item.getHours() * item.getRate())
                    .sum();
            draft.sumBeforeDiscounts = sum;
            draft.sumAfterDiscounts = sum;
            double vatRate = draft.getVat() > 0 ? draft.getVat() : 25.0;
            draft.vatAmount = sum * (vatRate / 100.0);
            draft.grandTotal = sum + draft.vatAmount;
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
        // Filter to BASE items only to avoid duplicating persisted CALCULATED items
        var baseItems = draft.getInvoiceitems().stream()
                .filter(item -> item.origin == InvoiceItemOrigin.BASE)
                .collect(Collectors.toCollection(ArrayList::new));
        baseItems.addAll(pr.syntheticItems);
        var combined = baseItems;
        draft.invoiceitems = combined;

        return Response.ok(draft).build();
    }
}
