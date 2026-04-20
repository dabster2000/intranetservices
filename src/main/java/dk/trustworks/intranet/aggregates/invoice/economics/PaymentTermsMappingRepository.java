package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Panache repository for PaymentTermsMapping. Mappings are strictly scoped
 * to one e-conomic agreement — there are no global rows. Null companyUuid
 * is an illegal argument.
 */
@ApplicationScoped
public class PaymentTermsMappingRepository implements PanacheRepositoryBase<PaymentTermsMapping, String> {

    public List<PaymentTermsMapping> listForCompany(String companyUuid) {
        Objects.requireNonNull(companyUuid, "companyUuid is required");
        return list("company.uuid = ?1", companyUuid);
    }

    public Optional<PaymentTermsMapping> findByTypeAndDays(
            PaymentTermsType type, Integer days, String companyUuid) {
        Objects.requireNonNull(companyUuid, "companyUuid is required");
        return find("paymentTermsType = ?1 and paymentDays = ?2 and company.uuid = ?3",
                type, days, companyUuid).firstResultOptional();
    }
}
