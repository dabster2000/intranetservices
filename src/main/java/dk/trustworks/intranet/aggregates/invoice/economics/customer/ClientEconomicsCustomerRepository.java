package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ClientEconomicsCustomerRepository implements PanacheRepositoryBase<ClientEconomicsCustomer, String> {

    public Optional<ClientEconomicsCustomer> findByClientAndCompany(String clientUuid, String companyUuid) {
        return find("clientUuid = ?1 AND companyUuid = ?2", clientUuid, companyUuid).firstResultOptional();
    }

    public List<ClientEconomicsCustomer> listByCompany(String companyUuid) {
        return find("companyUuid", companyUuid).list();
    }

    public List<ClientEconomicsCustomer> listByPairingSourceAndCompany(PairingSource source, String companyUuid) {
        return find("pairingSource = ?1 AND companyUuid = ?2", source, companyUuid).list();
    }
}
