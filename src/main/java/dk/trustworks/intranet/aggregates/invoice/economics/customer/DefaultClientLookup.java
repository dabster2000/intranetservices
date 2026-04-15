package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Default Panache-backed {@link ClientLookup}. Lists all active clients
 * regardless of {@code type} — the pairing UI shows CLIENT and PARTNER rows
 * side-by-side.
 */
@ApplicationScoped
public class DefaultClientLookup implements ClientLookup {

    @Override
    public List<Client> listActive() {
        return Client.list("active = ?1", Sort.ascending("name"), true);
    }

    @Override
    public Optional<Client> findByUuid(String uuid) {
        return Optional.ofNullable(Client.findById(uuid));
    }
}
