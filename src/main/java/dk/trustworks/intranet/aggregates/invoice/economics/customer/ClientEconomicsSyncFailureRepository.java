package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ClientEconomicsSyncFailureRepository
        implements PanacheRepositoryBase<ClientEconomicsSyncFailure, String> {

    public Optional<ClientEconomicsSyncFailure> findByClientAndCompany(String clientUuid, String companyUuid) {
        return find("clientUuid = ?1 AND companyUuid = ?2", clientUuid, companyUuid).firstResultOptional();
    }

    public List<ClientEconomicsSyncFailure> listDueForRetry(LocalDateTime now) {
        return find("status = 'PENDING' AND nextRetryAt <= ?1 ORDER BY nextRetryAt", now).list();
    }

    public List<ClientEconomicsSyncFailure> listPendingByCompany(String companyUuid) {
        return find("companyUuid = ?1 AND status = 'PENDING'", companyUuid).list();
    }
}
