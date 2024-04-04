package dk.trustworks.intranet.aggregates.invoice.network;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("")
@RegisterRestClient
public interface InvoiceAPI {

    @POST
    @Produces(APPLICATION_JSON)
    byte[] createInvoicePDF(String invoiceDTO);

}
