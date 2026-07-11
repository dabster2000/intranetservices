// src/main/java/dk/trustworks/intranet/aggregates/invoice/resources/PricingResource.java
package dk.trustworks.intranet.aggregates.invoice.resources;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.pricing.PricingEngine;

import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.services.PricingPreviewService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
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
    @Inject PricingPreviewService pricingPreviewService;

    @GET
    @Path("/preview/{invoiceuuid}")
    @Transactional(Transactional.TxType.REQUIRED)
    public Response preview(@PathParam("invoiceuuid") String invoiceuuid) {
        return preview(Invoice.findById(invoiceuuid));
    }


    @POST
    @Path("/preview")
    @Transactional(Transactional.TxType.REQUIRED)
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
        // Finalized invoices: line items are frozen. Re-running the pricing engine
        // here can double-apply rules whose effect was already captured as a
        // BASE row (e.g. manually-entered SKI administrationsgebyr on legacy
        // invoices — origin=BASE with no engine metadata), so the preview just
        // sums the persisted items and derives totals from them. Drafts still
        // run the engine so the rule-preview behavior is preserved where it
        // actually matters (DRAFT / PENDING_REVIEW).
        if (draft.getStatus() != null
                && !draft.getStatus().name().equals("DRAFT")
                && !draft.getStatus().name().equals("PENDING_REVIEW")) {
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
        draft.pricingPreview = pricingPreviewService.preview(draft);

        // Til UI preview: vis base + syntetiske (men persistér ikke)
        // Filter out effectively-CALCULATED items (origin=CALCULATED OR engine
        // metadata populated) to avoid duplicating persisted discount/fee lines.
        // Legacy invoices stored CALCULATED rows with origin=BASE, so the enum
        // alone misclassifies them — see InvoiceItem.isEffectivelyCalculated().
        var baseItems = draft.getInvoiceitems().stream()
                .filter(item -> !item.isEffectivelyCalculated())
                .collect(Collectors.toCollection(ArrayList::new));
        baseItems.addAll(pr.syntheticItems);
        var combined = baseItems;
        draft.invoiceitems = combined;

        return Response.ok(draft).build();
    }
}
