package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.financeservice.model.IntegrationKey.IntegrationKeyValue;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Builds a per-agreement {@link EconomicsCustomerApiClient} bound to a
 * Trustworks company's e-conomic credentials.
 *
 * <p>Each company has its own {@code X-AgreementGrantToken} (see
 * {@link IntegrationKeyValue}); this factory wires the two required auth
 * headers through an {@link EconomicsDynamicHeaderFilter} so callers do not
 * have to pass tokens on every method invocation.
 *
 * <p>The Customers API v3.1.0 lives on a different base URL than the
 * legacy restapi used by {@code EconomicsService}, so we resolve the base
 * from config rather than from {@code IntegrationKeyValue.url()}.
 *
 * <h2>Client reuse</h2>
 * Built clients are cached per agreement and reused for the lifetime of the
 * application. Each {@link RestClientBuilder#build} call allocates its own
 * Apache HTTP engine ({@code ManualClosingApacheHttpClient43Engine}) holding a
 * connection pool; building one per call leaked an engine per invocation, which
 * RESTEasy only reclaimed via its {@code Cleaner} — logging
 * {@code RESTEASY004687 "Closing a ... instance for you. Please close clients
 * yourself."} once per collected engine (observed 31x in a single production
 * contact-sync run, 2026-07-28). Caching removes the churn entirely: the
 * engines are never orphaned, so the cleaner never fires.
 *
 * <p>The proxies are safe to share — a JAX-RS client is thread-safe and its
 * pool allows 50 concurrent connections per route (RESTEasy's
 * {@code connectionPoolSize} default, applied to {@code defaultMaxPerRoute}
 * because {@code maxPooledPerRoute} is left at 0).
 *
 * <p>The cache is keyed on a digest of the credentials rather than on the
 * company, because {@code IntegrationKey.getIntegrationKeyValue} re-reads the
 * tokens from the database on every call and an admin may rotate them at
 * runtime. Keying on the tokens means rotated credentials yield a fresh client
 * instead of silently reusing a client bound to the stale secret. The
 * superseded entry is retained until shutdown; with a handful of agreements and
 * rotation being a rare administrative action, that is bounded and immaterial.
 *
 * SPEC-INV-001 §6.1.
 */
@ApplicationScoped
public class EconomicsCustomersApiClientFactory {

    private static final Logger LOG = Logger.getLogger(EconomicsCustomersApiClientFactory.class);

    @ConfigProperty(
            name = "quarkus.rest-client.economics-customers-api.url",
            defaultValue = "https://apis.e-conomic.com/customersapi/v3.1.0"
    )
    String baseUrl;

    /**
     * Timeouts are read from the same {@code economics-customers-api} keys the
     * CDI rest-client path uses, but must be applied explicitly: Quarkus only
     * folds configured timeouts into the builder via
     * {@code RestClientBase.configureBuilder}, which runs solely on the
     * {@code @RestClient} injection path. A programmatic
     * {@link RestClientBuilder#newBuilder()} leaves both null, falling back to
     * RESTEasy's {@code -1} (wait forever) — so these values were previously
     * inert here. That is harmless while every call owns a private pool, but
     * once a client is shared a hung response would hold one of its 50
     * connections indefinitely; enough of them would stall every caller of the
     * agreement. Applying the timeouts keeps a stuck e-conomic call a bounded,
     * retryable failure.
     */
    @ConfigProperty(name = "quarkus.rest-client.economics-customers-api.connect-timeout", defaultValue = "10000")
    long connectTimeoutMillis;

    @ConfigProperty(name = "quarkus.rest-client.economics-customers-api.read-timeout", defaultValue = "30000")
    long readTimeoutMillis;

    private final ConcurrentMap<String, EconomicsCustomerApiClient> customerClients = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, EconomicsContactApiClient> contactClients = new ConcurrentHashMap<>();

    /**
     * Returns the Customers API client authenticated for the given agreement,
     * building it on first use and reusing it thereafter.
     *
     * @param keys non-null integration keys for the target Trustworks company
     * @return a configured {@link EconomicsCustomerApiClient}
     */
    public EconomicsCustomerApiClient build(IntegrationKeyValue keys) {
        Objects.requireNonNull(keys, "integration keys must not be null");
        return customerClients.computeIfAbsent(agreementKey(keys),
                k -> builderFor(keys).build(EconomicsCustomerApiClient.class));
    }

    /**
     * Returns the Contacts API client authenticated for the given agreement,
     * building it on first use and reusing it thereafter. Shares the Customers
     * v3.1.0 base URL and auth-header filter with {@link #build}.
     *
     * @param keys non-null integration keys for the target Trustworks company
     * @return a configured {@link EconomicsContactApiClient}
     */
    public EconomicsContactApiClient buildContactClient(IntegrationKeyValue keys) {
        Objects.requireNonNull(keys, "integration keys must not be null");
        return contactClients.computeIfAbsent(agreementKey(keys),
                k -> builderFor(keys).build(EconomicsContactApiClient.class));
    }

    // --------------------------------------------------- internals

    private RestClientBuilder builderFor(IntegrationKeyValue keys) {
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .connectTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()));
    }

    /**
     * Cache key for one agreement: a SHA-256 digest of the base URL plus both
     * e-conomic tokens. Hashed rather than concatenated so the live secrets are
     * never held as map keys — {@link IntegrationKeyValue#toString()} masks them
     * for the same reason after they previously reached CloudWatch.
     */
    private String agreementKey(IntegrationKeyValue keys) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(baseUrl.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(keys.appSecretToken()).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(String.valueOf(keys.agreementGrantToken()).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Closes the pooled engines on shutdown. The MicroProfile Rest Client proxy
     * implements {@link Closeable} even when the API interface does not declare
     * it, and closing it closes the underlying {@code ResteasyClient} — which
     * marks the engine closed so RESTEasy's cleaner stays quiet.
     */
    @PreDestroy
    void closeClients() {
        closeAll(customerClients.values());
        closeAll(contactClients.values());
        customerClients.clear();
        contactClients.clear();
    }

    private static void closeAll(Iterable<?> clients) {
        for (Object client : clients) {
            if (client instanceof Closeable closeable) {
                try {
                    closeable.close();
                } catch (Exception e) {
                    LOG.debugf(e, "Failed closing an e-conomic Customers API client on shutdown");
                }
            }
        }
    }
}
