package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ClientEconomicsContactRepository implements PanacheRepositoryBase<ClientEconomicsContact, String> {

    public Optional<ClientEconomicsContact> findByClientCompanyAndName(String clientUuid,
                                                                      String companyUuid,
                                                                      String contactName) {
        return find("clientUuid = ?1 AND companyUuid = ?2 AND contactName = ?3",
                clientUuid, companyUuid, contactName).firstResultOptional();
    }

    public List<ClientEconomicsContact> listByClientAndCompany(String clientUuid, String companyUuid) {
        return find("clientUuid = ?1 AND companyUuid = ?2", clientUuid, companyUuid).list();
    }
}
