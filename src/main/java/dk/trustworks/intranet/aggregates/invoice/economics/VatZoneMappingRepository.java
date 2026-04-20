package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Panache repository for VatZoneMapping. Strictly per-company: VAT-zone numbers
 * are IDs inside one specific e-conomic agreement. Null companyUuid is an
 * illegal argument.
 */
@ApplicationScoped
public class VatZoneMappingRepository implements PanacheRepositoryBase<VatZoneMapping, String> {

    public List<VatZoneMapping> listForCompany(String companyUuid) {
        if (companyUuid == null) throw new IllegalArgumentException("companyUuid is required");
        return list("company.uuid = ?1", companyUuid);
    }

    public Optional<VatZoneMapping> findByCurrency(String currency, String companyUuid) {
        if (currency == null) throw new IllegalArgumentException("currency is required");
        if (companyUuid == null) throw new IllegalArgumentException("companyUuid is required");
        return find("currency = ?1 and company.uuid = ?2", currency, companyUuid).firstResultOptional();
    }
}
