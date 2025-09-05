package dk.trustworks.intranet.financeservice.remote;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import java.io.IOException;

public class EconomicsDynamicHeaderFilter implements ClientRequestFilter {
    private final String appSecretToken;
    private final String agreementGrantToken;

    public EconomicsDynamicHeaderFilter(String appSecretToken, String agreementGrantToken) {
        this.appSecretToken = appSecretToken;
        this.agreementGrantToken = agreementGrantToken;
    }

    @Override
    public void filter(ClientRequestContext ctx) {
        var h = ctx.getHeaders();
        h.putSingle("X-AppSecretToken", appSecretToken);
        h.putSingle("X-AgreementGrantToken", agreementGrantToken);
        h.putSingle("Accept", MediaType.APPLICATION_JSON); // ok for responses

        // Only set JSON for non-multipart requests and when not already set
        boolean isMultipartEntity = ctx.hasEntity() &&
                ctx.getEntity() instanceof org.jboss.resteasy.plugins.providers.multipart.MultipartFormDataOutput;
        if (!h.containsKey("Content-Type") && !isMultipartEntity) {
            h.putSingle("Content-Type", MediaType.APPLICATION_JSON);
        }
    }
}