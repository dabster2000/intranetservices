package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Panache repository for PaymentTermsMapping. Mappings are strictly scoped
 * to one e-conomic agreement — there are no global rows. Null companyUuid
 * is an illegal argument.
 */
@ApplicationScoped
public class PaymentTermsMappingRepository implements PanacheRepositoryBase<PaymentTermsMapping, String> {

    public List<PaymentTermsMapping> listForCompany(String companyUuid) {
        if (companyUuid == null) throw new IllegalArgumentException("companyUuid is required");
        return list("company.uuid = ?1", companyUuid);
    }

    public Optional<PaymentTermsMapping> findByTypeAndDays(
            PaymentTermsType type, Integer days, String companyUuid) {
        if (companyUuid == null) throw new IllegalArgumentException("companyUuid is required");
        return find("paymentTermsType = ?1 and paymentDays = ?2 and company.uuid = ?3",
                type, days, companyUuid).firstResultOptional();
    }

    /**
     * Returns the company's most-immediate NET payment term — the NET mapping
     * with the fewest days (0 = "Netto kontant" / "Til omgående betaling"),
     * tie-broken by the lowest e-conomic number. Used for INTERNAL /
     * INTERNAL_SERVICE invoices, which settle immediately and must use a term
     * that exists in the ISSUER's own agreement.
     */
    public Optional<PaymentTermsMapping> findMostImmediateNet(String companyUuid) {
        if (companyUuid == null) throw new IllegalArgumentException("companyUuid is required");
        return find("company.uuid = ?1 and paymentTermsType = ?2 and paymentDays is not null "
                        + "order by paymentDays asc, economicsPaymentTermsNumber asc",
                companyUuid, PaymentTermsType.NET).firstResultOptional();
    }
}
