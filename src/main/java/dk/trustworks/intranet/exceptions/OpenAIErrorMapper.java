package dk.trustworks.intranet.exceptions;

import org.jboss.resteasy.microprofile.client.ExceptionMapping;
import org.eclipse.microprofile.rest.client.ext.ResponseExceptionMapper;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
public class OpenAIErrorMapper implements ResponseExceptionMapper<RuntimeException> {
    @Override public boolean handles(int status, jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
        return status >= 400;
    }
    @Override public RuntimeException toThrowable(Response response) {
        String body = "";
        try { body = response.readEntity(String.class); } catch (Exception ignore) {}
        return new RuntimeException("OpenAI API error " + response.getStatus() + ": " + body);
    }
}