package dk.trustworks.intranet.cvtool.client;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import java.util.Optional;

/**
 * Sets the {@code Ocp-Apim-Subscription-Key} header on every CV Tool request.
 *
 * <p>The CV Tool backend is fronted by Azure API Management. The subscription
 * key is loaded from {@code cvtool.subscription-key} configuration (sourced
 * from the {@code CVTOOL_SUBSCRIPTION_KEY} environment variable, which in turn
 * comes from the {@code tw-quarkus-secrets-{env}} Secrets Manager entry in
 * deployed environments).
 *
 * <p>This replaced the previous cookie-based flow: the CV Tool backend was
 * rebuilt as the Trustworks AI Platform API and dropped {@code POST /auth/login}
 * entirely, which silently broke the nightly sync. The key is the whole
 * credential — there is no login round-trip and no token to refresh.
 *
 * @see CvToolClient
 */
@JBossLog
@ApplicationScoped
public class CvToolHeadersFactory implements ClientHeadersFactory {

    public static final String SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key";

    @ConfigProperty(name = "cvtool.subscription-key")
    Optional<String> subscriptionKey;

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders,
                                                 MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>(clientOutgoingHeaders);
        String key = subscriptionKey.map(String::trim).orElse("");
        if (key.isEmpty()) {
            log.warn("CV Tool subscription key is not configured — calls will fail authentication (set CVTOOL_SUBSCRIPTION_KEY).");
        }
        result.putSingle(SUBSCRIPTION_KEY_HEADER, key);
        return result;
    }
}
