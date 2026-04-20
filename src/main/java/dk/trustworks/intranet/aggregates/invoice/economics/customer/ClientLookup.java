package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;

import java.util.List;
import java.util.Optional;

/**
 * Narrow read-only seam over the Trustworks {@code Client} aggregate used by
 * {@link EconomicsCustomerPairingService}. Kept as a separate interface so
 * unit tests can mock Panache's static API without booting Quarkus.
 */
public interface ClientLookup {

    /** All clients (CLIENT + PARTNER, active and inactive), sorted by name. */
    List<Client> listAll();

    Optional<Client> findByUuid(String uuid);
}
