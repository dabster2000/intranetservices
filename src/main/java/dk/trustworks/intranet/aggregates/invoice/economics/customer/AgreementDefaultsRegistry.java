package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Hardcoded per-agreement customer-create defaults. Verified 2026-04-15 during
 * Phase G1.1. Lifted out of {@link EconomicsCustomerPairingService} so Phase G2
 * sync services can share the same lookup.
 *
 * <ul>
 *   <li>Trustworks A/S (companyuuid {@code d8894494-...}): CLIENT group 1,
 *       PARTNER group 2, currency DKK, VAT zone 1 (Domestic),
 *       paymentTermId 2 (Lb. md. 30 dage).</li>
 *   <li>Trustworks Technology (1675389) + Cyber Security (1785188) do NOT
 *       have CLIENT/PARTNER groups yet → {@link #requireFor} throws so the
 *       UI can render a clear error message.</li>
 * </ul>
 *
 * <p>Package-private — consumers are the pairing/sync services.
 *
 * SPEC-INV-001 §13.1.
 */
@ApplicationScoped
class AgreementDefaultsRegistry {

    /** Package-visible so unit tests can assert the registry's state. */
    static final Map<String, AgreementDefaults> AGREEMENT_DEFAULTS = Map.of(
            "d8894494-2fb4-4f72-9e05-e6032e6dd691",
            new AgreementDefaults(1, 2, "DKK", 1, 2)
    );

    /**
     * Returns the defaults bundle for the given company, or throws a 409 so
     * the UI / sync callers surface a clear "configure e-conomic groups first"
     * message.
     */
    AgreementDefaults requireFor(String companyUuid) {
        AgreementDefaults d = AGREEMENT_DEFAULTS.get(companyUuid);
        if (d == null) {
            throw new WebApplicationException(
                    "Agreement for company " + companyUuid + " has no customer-create defaults "
                            + "configured. Create CLIENT/PARTNER customer groups in e-conomic and "
                            + "register them in economics-config.md before using the Create flow.",
                    Response.Status.CONFLICT);
        }
        return d;
    }

    /** Returns the companies with configured e-conomic defaults (sync targets). */
    Set<String> listConfiguredCompanies() {
        return Collections.unmodifiableSet(AGREEMENT_DEFAULTS.keySet());
    }
}
