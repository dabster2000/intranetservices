package dk.trustworks.intranet.financeservice.network;

import dk.trustworks.intranet.dto.InvoiceReference;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.*;
import java.util.List;

@Path("/invoices")
@RegisterRestClient
@Produces("application/json")
@Consumes("application/json")
public interface InvoiceAPI {

    @GET
    List<Invoice> findAll();

    @POST
    @Path("/{invoiceuuid}/reference")
    void updateInvoiceReference(@PathParam("invoiceuuid") String invoiceuuid, InvoiceReference invoiceReference);

}
