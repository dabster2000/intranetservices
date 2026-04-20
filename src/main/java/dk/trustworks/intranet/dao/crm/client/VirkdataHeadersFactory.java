package dk.trustworks.intranet.dao.crm.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.util.Optional;

/**
 * Sets the {@code Authorization} header on every Virkdata API request.
 *
 * <p>The API key is loaded from {@code virkdata.api-key} configuration
 * (sourced from the {@code VIRKDATA_API_KEY} environment variable, which in
 * turn comes from the {@code tw-quarkus-secrets-{env}} Secrets Manager entry
 * in deployed environments).
 *
 * <p>The header value is the raw API key — Virkdata does not use a
 * {@code Bearer} prefix.
 *
 * @see <a href="https://virkdata.dk">Virkdata documentation</a>
 */
@JBossLog
@ApplicationScoped
public class VirkdataHeadersFactory implements ClientHeadersFactory {

    @ConfigProperty(name = "virkdata.api-key")
    Optional<String> apiKey;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
                                                 MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>(clientOutgoingHeaders);
        String key = apiKey.map(String::trim).orElse("");
        if (key.isEmpty()) {
            log.warn("Virkdata API key is not configured — calls will fail authentication (set VIRKDATA_API_KEY).");
        }
        result.putSingle("Authorization", key);
        return result;
    }
}
