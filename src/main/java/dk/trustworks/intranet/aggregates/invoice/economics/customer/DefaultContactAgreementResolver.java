package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.IntegrationKey.IntegrationKeyValue;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Default CDI implementation of {@link ContactAgreementResolver} backed by
 * {@link IntegrationKey} + {@link EconomicsCustomersApiClientFactory} — one
 * agreement per Trustworks {@link Company}.
 */
@ApplicationScoped
public class DefaultContactAgreementResolver implements ContactAgreementResolver {

    @Inject
    EconomicsCustomersApiClientFactory clientFactory;

    @Override
    public EconomicsContactApiClient apiFor(String companyUuid) {
        Company company = Company.findById(companyUuid);
        if (company == null) {
            throw new IllegalArgumentException("Unknown company: " + companyUuid);
        }
        IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);
        return clientFactory.buildContactClient(keys);
    }
}
