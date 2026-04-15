package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Panache repository for PaymentTermsMapping. Per-company lookup falls back
 * to global defaults (rows where company_uuid IS NULL).
 */
@ApplicationScoped
public class PaymentTermsMappingRepository implements PanacheRepositoryBase<PaymentTermsMapping, String> {

    public List<PaymentTermsMapping> listForCompany(String companyUuid) {
        if (companyUuid == null) {
            return list("company is null");
        }
        return list("company.uuid = ?1 or company is null", companyUuid);
    }

    public Optional<PaymentTermsMapping> findByTypeAndDays(
            PaymentTermsType type, Integer days, String companyUuid) {
        if (companyUuid == null) {
            return find("paymentTermsType = ?1 and paymentDays = ?2 and company is null", type, days)
                    .firstResultOptional();
        }
        return find("paymentTermsType = ?1 and paymentDays = ?2 and company.uuid = ?3", type, days, companyUuid)
                .firstResultOptional();
    }
}
