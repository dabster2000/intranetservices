package dk.trustworks.intranet.expenseservice.remote;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;

public class EconomicsErrorMapper implements ResponseExceptionMapper<RuntimeException> {

    @Override
    public boolean handles(int status, MultivaluedMap<String, Object> headers) {
        // Do not map 404 to exceptions; callers often treat 404 as "not found"
        return status >= 400 && status != 404;
    }

    @Override
    public RuntimeException toThrowable(Response response) {
        String body = null;
        try {
            body = response.readEntity(String.class);
        } catch (Exception ignore) { }
        return new RuntimeException("HTTP " + response.getStatus() + " from Economics: " + (body != null ? body : ""));
    }
}
