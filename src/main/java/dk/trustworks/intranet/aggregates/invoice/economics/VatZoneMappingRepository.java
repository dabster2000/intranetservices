package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Panache repository for VatZoneMapping. Per-company lookup falls back to
 * global defaults (rows where company_uuid IS NULL).
 */
@ApplicationScoped
public class VatZoneMappingRepository implements PanacheRepositoryBase<VatZoneMapping, String> {

    public List<VatZoneMapping> listForCompany(String companyUuid) {
        if (companyUuid == null) {
            return list("company is null");
        }
        return list("company.uuid = ?1 or company is null", companyUuid);
    }

    public Optional<VatZoneMapping> findByCurrency(String currency, String companyUuid) {
        if (companyUuid == null) {
            return find("currency = ?1 and company is null", currency).firstResultOptional();
        }
        // Prefer per-company row over global default when both exist.
        return find("currency = ?1 and (company.uuid = ?2 or company is null) order by case when company is null then 1 else 0 end",
                currency, companyUuid).firstResultOptional();
    }
}
