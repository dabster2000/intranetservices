package dk.trustworks.intranet.financeservice.remote;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.core.MultivaluedMap;
import java.io.IOException;

public class DynamicHeaderFilter implements ClientRequestFilter {
    private final String appSecretToken;
    private final String agreementGrantToken;

    public DynamicHeaderFilter(String appSecretToken, String agreementGrantToken) {
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
