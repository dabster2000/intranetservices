package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsAgreementCapabilityService;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContact;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContactRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomer;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomerRepository;
import dk.trustworks.intranet.utils.EanValidator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates all 8 EAN send prerequisites (SPEC-INV-001 section 4.2) before an
 * invoice is booked through NemHandel. Returns {@code null} when every check
 * passes; otherwise returns a structured {@link EanPrerequisiteErrorDto} with
 * all failures so the UI can render actionable messages.
 *
 * <p>Check #8 (otherReference) is a soft warning only and never blocks.
 */
@ApplicationScoped
public class EanPrerequisiteChecker {

    private static final Set<String> EAN_COUNTRIES = Set.of("DK");

    private final EconomicsAgreementCapabilityService capability;
    private final ClientEconomicsCustomerRepository customers;
    private final ClientEconomicsContactRepository contacts;

    @Inject
    public EanPrerequisiteChecker(EconomicsAgreementCapabilityService capability,
                                  ClientEconomicsCustomerRepository customers,
                                  ClientEconomicsContactRepository contacts) {
        this.capability = capability;
        this.customers = customers;
        this.contacts = contacts;
    }

    /**
     * Runs all prerequisite checks against the given billing context.
     *
     * @param bc the resolved billing context (invoice + contract + billing client)
     * @return {@code null} if all checks pass, otherwise a structured error DTO
     */
    public EanPrerequisiteErrorDto check(BillingContext bc) {
        Map<String, String> fail = new LinkedHashMap<>();
        String companyUuid = bc.invoice().getCompany().getUuid();

        // 1. Country must be DK
        String country = bc.billingClient().getBillingCountry();
        if (country == null || !EAN_COUNTRIES.contains(country.toUpperCase())) {
            fail.put("AGREEMENT_NOT_DANISH",
                    "EAN sending is available only for Danish agreements (country was " + country + ")");
        }

        // 2. Agreement can send electronic invoices
        if (!fail.containsKey("AGREEMENT_NOT_DANISH")) {
            boolean canSend = capability.canSendElectronicInvoice(companyUuid);
            if (!canSend) {
                fail.put("AGREEMENT_CANNOT_SEND_EAN",
                        "The e-conomic agreement cannot send electronic invoices.");
            }
        }

        // 3+4. EAN set and valid
        String ean = bc.billingClient().getEan();
        if (ean == null || ean.isBlank()) {
            fail.put("EAN_MISSING", "Billing client has no EAN configured");
        } else if (!EanValidator.isValid(ean)) {
            fail.put("EAN_INVALID", "EAN '" + ean + "' fails GS1 Modulo 10 check");
        }

        // 5. Customer synced with objectVersion set
        Optional<ClientEconomicsCustomer> mapping = customers.findByClientAndCompany(
                bc.billingClient().getUuid(), companyUuid);
        if (mapping.isEmpty()) {
            fail.put("CUSTOMER_NOT_SYNCED",
                    "Billing client is not synced to e-conomic for this company");
        } else if (mapping.get().getObjectVersion() == null) {
            fail.put("CUSTOMER_NEMHANDEL_UNCONFIGURED",
                    "Customer is not yet configured for NemHandel in e-conomic");
        }

        // 6. Billing attention
        String attention = bc.contract() == null ? null : bc.contract().getBillingAttention();
        if (attention == null || attention.isBlank()) {
            fail.put("BILLING_ATTENTION_MISSING",
                    "Contract has no billing_attention — public sector invoices require a contact person");
        }

        // 7. Contact receiveEInvoices
        if (attention != null && !attention.isBlank()) {
            Optional<ClientEconomicsContact> contact = contacts.findByClientCompanyAndName(
                    bc.billingClient().getUuid(), companyUuid, attention);
            if (contact.isEmpty()) {
                fail.put("CONTACT_MISSING",
                        "Contact '" + attention + "' is not synced to e-conomic");
            } else if (!contact.get().isReceiveEInvoices()) {
                fail.put("CONTACT_RECEIVE_EINVOICES_FALSE",
                        "Contact '" + attention + "' does not have receiveEInvoices=true");
            }
        }

        // 8. otherReference — soft warning only, do NOT block

        if (fail.isEmpty()) return null;
        return new EanPrerequisiteErrorDto("EAN prerequisites not satisfied", fail);
    }
}
