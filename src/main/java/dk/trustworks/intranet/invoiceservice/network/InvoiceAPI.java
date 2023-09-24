package dk.trustworks.intranet.invoiceservice.network;

import dk.trustworks.intranet.invoiceservice.network.dto.InvoiceDTO;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

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
