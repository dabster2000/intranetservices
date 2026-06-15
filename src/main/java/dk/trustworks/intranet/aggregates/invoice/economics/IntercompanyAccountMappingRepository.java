package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * Panache repository for IntercompanyAccountMapping. Lookups are by the
 * (debtor, issuer) company pair, which is unique (see V375 UNIQUE KEY).
 */
@ApplicationScoped
public class IntercompanyAccountMappingRepository
        implements PanacheRepositoryBase<IntercompanyAccountMapping, String> {

    public Optional<IntercompanyAccountMapping> findByDebtorAndIssuer(
            String debtorCompanyUuid, String issuerCompanyUuid) {
        if (debtorCompanyUuid == null) throw new IllegalArgumentException("debtorCompanyUuid is required");
        if (issuerCompanyUuid == null) throw new IllegalArgumentException("issuerCompanyUuid is required");
        return find("debtorCompany.uuid = ?1 and issuerCompany.uuid = ?2",
                debtorCompanyUuid, issuerCompanyUuid).firstResultOptional();
    }
}
