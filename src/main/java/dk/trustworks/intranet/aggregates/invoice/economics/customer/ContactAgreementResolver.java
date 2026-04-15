package dk.trustworks.intranet.aggregates.invoice.economics.customer;

/**
 * Seam that resolves a per-agreement {@link EconomicsContactApiClient} from a
 * Trustworks company UUID. Analogous to {@link AgreementResolver} for the
 * Contacts endpoint, lifted out so unit tests can mock without Panache/Quarkus.
 */
public interface ContactAgreementResolver {

    EconomicsContactApiClient apiFor(String companyUuid);
}
