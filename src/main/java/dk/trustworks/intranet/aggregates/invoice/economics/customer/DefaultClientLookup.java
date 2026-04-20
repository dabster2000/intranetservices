package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.dao.crm.model.Client;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Default Panache-backed {@link ClientLookup}. Lists all clients regardless
 * of {@code type} or {@code active} — the pairing UI shows CLIENT and PARTNER
 * rows side-by-side and includes inactive clients so admins can pair/unpair
 * them too.
 */
@ApplicationScoped
public class DefaultClientLookup implements ClientLookup {

    @Override
    public List<Client> listAll() {
        return Client.listAll(Sort.ascending("name"));
    }

    @Override
    public Optional<Client> findByUuid(String uuid) {
        return Optional.ofNullable(Client.findById(uuid));
    }
}
