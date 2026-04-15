package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.financeservice.model.IntegrationKey.IntegrationKeyValue;
import dk.trustworks.intranet.financeservice.remote.EconomicsDynamicHeaderFilter;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.util.Objects;

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
 * SPEC-INV-001 §6.1.
 */
@ApplicationScoped
public class EconomicsCustomersApiClientFactory {

    @ConfigProperty(
            name = "quarkus.rest-client.economics-customers-api.url",
            defaultValue = "https://apis.e-conomic.com/customersapi/v3.1.0"
    )
    String baseUrl;

    /**
     * Builds a Customers API client authenticated for the given agreement.
     *
     * @param keys non-null integration keys for the target Trustworks company
     * @return a configured {@link EconomicsCustomerApiClient}
     */
    public EconomicsCustomerApiClient build(IntegrationKeyValue keys) {
        Objects.requireNonNull(keys, "integration keys must not be null");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsCustomerApiClient.class);
    }

    /**
     * Builds a Contacts API client authenticated for the given agreement. Shares
     * the Customers v3.1.0 base URL and auth-header filter with {@link #build}.
     *
     * @param keys non-null integration keys for the target Trustworks company
     * @return a configured {@link EconomicsContactApiClient}
     */
    public EconomicsContactApiClient buildContactClient(IntegrationKeyValue keys) {
        Objects.requireNonNull(keys, "integration keys must not be null");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create(baseUrl))
                .register(new EconomicsDynamicHeaderFilter(keys.appSecretToken(), keys.agreementGrantToken()))
                .build(EconomicsContactApiClient.class);
    }
}
