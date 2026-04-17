package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMappingRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMappingRepository;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.financeservice.model.IntegrationKey;
import dk.trustworks.intranet.model.Company;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;

/**
 * Thin wrapper that resolves e-conomic agreement-level configuration for a given
 * company: tokens, layout/product numbers, payment terms, and VAT zone numbers.
 *
 * All lookups delegate to existing data-access components so the orchestrator
 * stays decoupled from persistence details.
 *
 * SPEC-INV-001 §5.
 */
@ApplicationScoped
public class EconomicsAgreementResolver {

    @Inject
    PaymentTermsMappingRepository paymentTermsRepo;

    @Inject
    VatZoneMappingRepository vatZoneRepo;

    @Inject
    EntityManager em;

    /**
     * Immutable token pair for an e-conomic agreement.
     */
    public record Tokens(String appSecret, String agreementGrant) {}

    /**
     * Returns the app-secret and agreement-grant tokens for the given company.
     *
     * @throws IllegalStateException when no integration keys are configured.
     */
    public Tokens tokens(String companyUuid) {
        Company company = em.find(Company.class, companyUuid);
        if (company == null) {
            throw new IllegalStateException("Company not found: " + companyUuid);
        }
        IntegrationKey.IntegrationKeyValue kv = IntegrationKey.getIntegrationKeyValue(company);
        return new Tokens(kv.appSecretToken(), kv.agreementGrantToken());
    }

    /**
     * Returns the e-conomic layout number for the given company.
     *
     * @throws IllegalStateException when the integration key is not configured.
     */
    public int layoutNumber(String companyUuid) {
        Company company = em.find(Company.class, companyUuid);
        if (company == null) {
            throw new IllegalStateException("Company not found: " + companyUuid);
        }
        IntegrationKey.IntegrationKeyValue kv = IntegrationKey.getIntegrationKeyValue(company);
        // invoice-layout-number is stored separately — look it up directly
        return IntegrationKey.<IntegrationKey>find(
                        "company = ?1 and key = ?2", company, "invoice-layout-number")
                .firstResultOptional()
                .map(k -> Integer.parseInt(k.getValue()))
                .orElseThrow(() -> new BadRequestException(
                        "e-conomic invoice layout is not configured for " + company.getName()
                        + ". Ask an administrator to add the 'invoice-layout-number' integration key "
                        + "for this company before creating invoices."));
    }

    /**
     * Returns the e-conomic product number for the given company.
     *
     * @throws IllegalStateException when the integration key is not configured.
     */
    public String productNumber(String companyUuid) {
        Company company = em.find(Company.class, companyUuid);
        if (company == null) {
            throw new IllegalStateException("Company not found: " + companyUuid);
        }
        IntegrationKey.IntegrationKeyValue kv = IntegrationKey.getIntegrationKeyValue(company);
        int productNum = kv.invoiceProductNumber();
        if (productNum == 0) {
            throw new BadRequestException(
                    "e-conomic invoice product number is not configured for " + company.getName()
                    + ". Ask an administrator to set the 'invoice-product-number' integration key "
                    + "for this company before creating invoices.");
        }
        return String.valueOf(productNum);
    }

    /**
     * Returns the e-conomic payment term number for the contract.
     * Looks up by the contract's paymentTermsUuid (the PK of PaymentTermsMapping).
     *
     * @throws IllegalStateException when the mapping is not found.
     */
    public int paymentTermFor(Contract contract) {
        if (contract.getPaymentTermsUuid() == null) {
            throw new BadRequestException(
                    "Contract '" + contract.getName() + "' has no payment terms. "
                    + "Open the contract, select a Payment Terms value, save, and try again.");
        }
        PaymentTermsMapping mapping = paymentTermsRepo.findById(contract.getPaymentTermsUuid());
        if (mapping == null) {
            throw new BadRequestException(
                    "Payment terms on contract '" + contract.getName() + "' reference a missing "
                    + "configuration. Open the contract and select a valid Payment Terms value, "
                    + "then try again.");
        }
        return mapping.getEconomicsPaymentTermsNumber();
    }

    /**
     * Returns the internal journal number used for inter-company (supplier) voucher posting
     * to the debtor company's e-conomic agreement.
     *
     * @param companyUuid the UUID of the debtor company
     * @throws IllegalStateException when no integration keys are configured for the company
     */
    public int internalJournalNumber(String companyUuid) {
        Company company = em.find(Company.class, companyUuid);
        if (company == null) {
            throw new IllegalStateException("Debtor company not found: " + companyUuid);
        }
        IntegrationKey.IntegrationKeyValue kv = IntegrationKey.getIntegrationKeyValue(company);
        return kv.internalJournalNumber();
    }

    /**
     * Returns the e-conomic VAT zone number for the given currency and company.
     * Falls back to the global default row when no company-specific row exists.
     *
     * @throws IllegalStateException when no mapping exists at all.
     */
    public int vatZoneFor(String currency, String companyUuid) {
        VatZoneMapping mapping = vatZoneRepo.findByCurrency(currency, companyUuid)
                .orElseThrow(() -> new BadRequestException(
                        "No VAT zone is configured for currency '" + currency + "'. "
                        + "Ask an administrator to add a VAT zone mapping for this currency "
                        + "before creating invoices in it."));
        return mapping.getEconomicsVatZoneNumber();
    }
}
