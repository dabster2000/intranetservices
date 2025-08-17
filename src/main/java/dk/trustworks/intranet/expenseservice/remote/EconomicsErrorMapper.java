package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class EconomicsErrorMapper implements ResponseExceptionMapper<RuntimeException> {
    @Override
    public RuntimeException toThrowable(Response response) {
        String body = response.readEntity(String.class);
        return new RuntimeException("HTTP " + response.getStatus() + " from Economics: " + body);
    }
}
