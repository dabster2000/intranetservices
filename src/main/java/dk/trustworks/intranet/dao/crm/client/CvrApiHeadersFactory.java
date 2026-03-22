package dk.trustworks.intranet.dao.crm.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

/**
 * Sets the required User-Agent header on all CVR API requests.
 *
 * <p>The cvrapi.dk API rejects standard User-Agent strings with error code
 * {@code INVALID_UA}. A custom User-Agent identifying the application and
 * a contact email is required.
 *
 * @see <a href="https://cvrapi.dk/documentation">CVR API Documentation</a>
 */
@ApplicationScoped
public class CvrApiHeadersFactory implements ClientHeadersFactory {

    private static final String USER_AGENT = "Trustworks - Intranet - contact@trustworks.dk";

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
                                                  MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>(clientOutgoingHeaders);
        result.putSingle("User-Agent", USER_AGENT);
        return result;
    }
}
