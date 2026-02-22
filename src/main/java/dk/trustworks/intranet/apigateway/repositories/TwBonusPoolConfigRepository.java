package dk.trustworks.intranet.apigateway.repositories;

import dk.trustworks.intranet.apigateway.model.TwBonusPoolConfig;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class TwBonusPoolConfigRepository implements PanacheRepository<TwBonusPoolConfig> {

    public List<TwBonusPoolConfig> findByFiscalYear(int fiscalYear) {
        return list("fiscalYear", fiscalYear);
    }

    public Optional<TwBonusPoolConfig> findByFiscalYearAndCompany(int fiscalYear, String companyUuid) {
        return find("fiscalYear = ?1 and companyuuid = ?2", fiscalYear, companyUuid).firstResultOptional();
    }
}
