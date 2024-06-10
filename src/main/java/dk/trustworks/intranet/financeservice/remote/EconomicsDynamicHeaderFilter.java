package dk.trustworks.intranet.financeservice.remote;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
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
    public void filter(ClientRequestContext requestContext) throws IOException {
        MultivaluedMap<String, Object> headers = requestContext.getHeaders();
        headers.add("X-AppSecretToken", appSecretToken);
        headers.add("X-AgreementGrantToken", agreementGrantToken);
    }
}
