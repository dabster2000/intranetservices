package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMappingRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsType;
import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMappingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-agreement customer-create defaults.
 *
 * <p>Layered resolution:
 * <ol>
 *   <li><b>VAT zone + payment term:</b> resolved from {@code vat_zone_mapping}
 *       and {@code payment_terms_mapping} (V286) by (currency, company) and
 *       (type=NET, days=30, company) respectively. Falls back to hardcoded
 *       values from {@link #TEMPLATES} when the DB has no matching row, so
 *       existing staging/production environments that have not been fully
 *       seeded continue to work.</li>
 *   <li><b>CLIENT/PARTNER group numbers + currency:</b> taken from
 *       {@link #TEMPLATES} — these are not represented in the DB tables yet.
 *       A missing template ⇒ HTTP 409 so admins see a clear error.</li>
 * </ol>
 *
 * <p>Package-private — consumers are the pairing/sync services.
 *
 * SPEC-INV-001 §5.3, §5.4, §13.1.
 */
@ApplicationScoped
class AgreementDefaultsRegistry {

    private static final Logger LOG = Logger.getLogger(AgreementDefaultsRegistry.class);

    /**
     * Template per agreement. VAT zone + payment term values here are only
     * used when {@code vat_zone_mapping} / {@code payment_terms_mapping} rows
     * are absent for that company/currency.
     *
     * Package-visible so unit tests can assert the registry's shape.
     */
    static final Map<String, AgreementDefaults> TEMPLATES = Map.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691",   // Trustworks A/S
            new AgreementDefaults(1, 2, "DKK", 1, 2),
            "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3",   // Trustworks Technology ApS
            new AgreementDefaults(1, 2, "DKK", 1, 2),
            "e4b0a2a4-0963-4153-b0a2-a409637153a2",   // Trustworks Cyber Security ApS
            new AgreementDefaults(1, 2, "DKK", 1, 2)
    );

    @Inject
    VatZoneMappingRepository vatZoneRepo;

    @Inject
    PaymentTermsMappingRepository paymentTermsRepo;

    AgreementDefaults requireFor(String companyUuid) {
        AgreementDefaults template = TEMPLATES.get(companyUuid);
        if (template == null) {
            throw new WebApplicationException(
                    "Agreement for company " + companyUuid + " has no customer-create defaults "
                            + "configured. Create CLIENT/PARTNER customer groups in e-conomic and "
                            + "register them in economics-config.md before using the Create flow.",
                    Response.Status.CONFLICT);
        }

        int vatZoneNumber = resolveVatZone(template.currency(), companyUuid, template.vatZoneNumber());
        int paymentTermId = resolvePaymentTerm(companyUuid, template.paymentTermId());

        return new AgreementDefaults(
                template.clientGroupNumber(),
                template.partnerGroupNumber(),
                template.currency(),
                vatZoneNumber,
                paymentTermId);
    }

    private int resolveVatZone(String currency, String companyUuid, int fallback) {
        try {
            Optional<VatZoneMapping> row = vatZoneRepo.findByCurrency(currency, companyUuid);
            if (row.isPresent()) return row.get().getEconomicsVatZoneNumber();
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not resolve VAT zone for %s/%s; using template fallback", currency, companyUuid);
        }
        return fallback;
    }

    /**
     * Resolves the default payment-term number used on newly-created
     * customers. Convention: NET / 30 days — matches e-conomic's most common
     * "Lb. md. 30 dage" term.
     */
    private int resolvePaymentTerm(String companyUuid, int fallback) {
        try {
            Optional<PaymentTermsMapping> row =
                    paymentTermsRepo.findByTypeAndDays(PaymentTermsType.NET, 30, companyUuid);
            if (row.isPresent()) return row.get().getEconomicsPaymentTermsNumber();
        } catch (RuntimeException e) {
            LOG.debugf(e, "Could not resolve payment term for %s; using template fallback", companyUuid);
        }
        return fallback;
    }

    /** Returns the companies with configured e-conomic defaults (sync targets). */
    Set<String> listConfiguredCompanies() {
        return Collections.unmodifiableSet(TEMPLATES.keySet());
    }
}
