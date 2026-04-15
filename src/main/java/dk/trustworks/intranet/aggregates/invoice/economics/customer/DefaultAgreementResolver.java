package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.financeservice.model.IntegrationKey.IntegrationKeyValue;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Default CDI implementation of {@link AgreementResolver} backed by the
 * existing {@link IntegrationKey} + {@link EconomicsCustomersApiClientFactory}
 * infrastructure: one agreement per Trustworks {@link Company}.
 */
@ApplicationScoped
public class DefaultAgreementResolver implements AgreementResolver {

    @Inject
    EconomicsCustomersApiClientFactory clientFactory;

    @Override
    public EconomicsCustomerApiClient apiFor(String companyUuid) {
        Company company = Company.findById(companyUuid);
        if (company == null) {
            throw new IllegalArgumentException("Unknown company: " + companyUuid);
        }
        IntegrationKeyValue keys = IntegrationKey.getIntegrationKeyValue(company);
        return clientFactory.build(keys);
    }
}
