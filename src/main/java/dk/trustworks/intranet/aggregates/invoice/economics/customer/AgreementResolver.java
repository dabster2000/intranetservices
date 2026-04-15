package dk.trustworks.intranet.aggregates.invoice.economics.customer;

/**
 * Seam that resolves the per-agreement {@link EconomicsCustomerApiClient}
 * from a Trustworks company UUID. Separated from
 * {@link EconomicsCustomerPairingService} so unit tests can provide a mock
 * without touching {@code IntegrationKey}/{@code Company} Panache statics.
 */
public interface AgreementResolver {

    EconomicsCustomerApiClient apiFor(String companyUuid);
}
