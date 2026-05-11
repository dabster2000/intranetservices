package dk.trustworks.intranet.aggregates.invoice.economics.supplier;

import dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto.SupplierDto;
import dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto.SuppliersPage;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver.Tokens;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Resolves an issuer company's e-conomic supplier number in a debtor company's
 * e-conomic agreement by looking up the issuer's CVR via the legacy /suppliers
 * endpoint.
 *
 * <p>Used by {@code EconomicsInvoiceService} to enrich the debtor-side
 * {@code SupplierInvoice} voucher for INTERNAL and INTERNAL_SERVICE invoices.
 *
 * <p>This resolver never throws. All non-happy paths return
 * {@link Optional#empty()} so the caller can fall back to today's behavior of
 * posting the voucher without a supplier reference. The caller logs the
 * fallback so finance can act on the bookkeeping side.
 */
@JBossLog
@ApplicationScoped
public class EconomicsSupplierResolver {

    private static final String CVR_FILTER_PREFIX = "corporateIdentificationNumber$eq:";

    @Inject
    EconomicsAgreementResolver agreementResolver;

    @Inject
    @RestClient
    EconomicsSuppliersApiClient suppliersApi;

    /**
     * Returns the supplier number in the debtor company's e-conomic whose
     * {@code corporateIdentificationNumber} equals {@code issuerCvr}.
     *
     * @return the supplier number on exact-one match; {@link Optional#empty()}
     *         when the CVR is null/blank, zero suppliers match, multiple
     *         suppliers match (ambiguous), the debtor's integration keys are
     *         missing, or the /suppliers call fails for any reason.
     */
    public Optional<Integer> resolveByCvr(String debtorCompanyUuid, String issuerCvr) {
        if (issuerCvr == null || issuerCvr.isBlank()) {
            log.warnf("Supplier resolve skipped: issuer CVR is null/blank for debtor %s",
                    debtorCompanyUuid);
            return Optional.empty();
        }

        Tokens tokens;
        try {
            tokens = agreementResolver.tokens(debtorCompanyUuid);
        } catch (RuntimeException e) {
            log.errorf(e, "Supplier resolve failed: cannot load debtor %s integration keys",
                    debtorCompanyUuid);
            return Optional.empty();
        }

        SuppliersPage page;
        try {
            page = suppliersApi.findByFilter(
                    tokens.appSecret(),
                    tokens.agreementGrant(),
                    CVR_FILTER_PREFIX + issuerCvr);
        } catch (RuntimeException e) {
            log.errorf(e, "Supplier resolve failed for CVR %s in debtor %s: API error",
                    issuerCvr, debtorCompanyUuid);
            return Optional.empty();
        }

        List<SupplierDto> matches = page == null || page.getCollection() == null
                ? List.of()
                : page.getCollection();

        if (matches.isEmpty()) {
            log.warnf("Supplier resolve: zero matches for CVR %s in debtor %s",
                    issuerCvr, debtorCompanyUuid);
            return Optional.empty();
        }

        if (matches.size() > 1) {
            String numbers = matches.stream()
                    .map(s -> String.valueOf(s.getSupplierNumber()))
                    .collect(Collectors.joining(", "));
            log.warnf("Supplier resolve: ambiguous match for CVR %s in debtor %s -- candidates [%s]",
                    issuerCvr, debtorCompanyUuid, numbers);
            return Optional.empty();
        }

        int supplierNumber = matches.get(0).getSupplierNumber();
        log.infof("Resolved issuer CVR %s -> supplier number %d in debtor %s",
                issuerCvr, supplierNumber, debtorCompanyUuid);
        return Optional.of(supplierNumber);
    }
}
