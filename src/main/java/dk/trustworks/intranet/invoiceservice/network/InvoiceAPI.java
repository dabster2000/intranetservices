package dk.trustworks.intranet.invoiceservice.network;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

@Path("")
@RegisterRestClient
//@ClientHeaderParam(name="X-AppSecretToken", value = "GCCmf2TIXfrY3D9jEiqss8gUPa59rvBFbYAEjF1h7zQ1") //value="{xAppSecretToken}")
//@ClientHeaderParam(name="X-AgreementGrantToken", value = "B03oSVDidmk53uOIdMV9ptnI2hlVQykGdTvmisrtFq01") //value="{xAgreementGrantToken}")
public interface InvoiceAPI {

    @POST
    @Produces(APPLICATION_JSON)
    byte[] createInvoicePDF(String invoiceDTO);

    /*
    public byte[] createInvoicePDF(Invoice invoice) {
        InvoiceDTO invoiceDTO = new InvoiceDTO(invoice);


        MappingJackson2HttpMessageConverter jsonHttpMessageConverter = new MappingJackson2HttpMessageConverter();
        jsonHttpMessageConverter.getObjectMapper().configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        restTemplate.getMessageConverters().add(jsonHttpMessageConverter);

        HttpHeaders requestHeaders = new HttpHeaders();
        requestHeaders.add("Content-Type", "application/json");

        HttpEntity<InvoiceDTO> entity = new HttpEntity<>(invoiceDTO, requestHeaders);

        ResponseEntity<byte[]> exchange = restTemplate.exchange("https://invoice-generator.com", HttpMethod.POST, entity, byte[].class);
        return exchange.getBody();
    }
    */

}
