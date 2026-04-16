package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import dk.trustworks.intranet.aggregates.invoice.economics.book.EconomicsAgreementCapabilityService;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContact;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContactRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomer;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomerRepository;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EanPrerequisiteChecker}.
 * Plain JUnit + Mockito -- no Quarkus boot required.
 *
 * <p>Covers all 8 prerequisite checks from SPEC-INV-001 section 4.2.
 */
class EanPrerequisiteCheckerTest {

    private static final String COMPANY_UUID = "company-uuid-1";
    private static final String CLIENT_UUID = "client-uuid-1";
    // Valid EAN-13: 5790000000012 passes GS1 Modulo 10
    private static final String VALID_EAN = "5790000000012";
    private static final String ATTENTION_NAME = "Jane Doe";

    private EconomicsAgreementCapabilityService capability;
    private ClientEconomicsCustomerRepository customers;
    private ClientEconomicsContactRepository contacts;

    private EanPrerequisiteChecker checker;

    @BeforeEach
    void setUp() {
        capability = mock(EconomicsAgreementCapabilityService.class);
        customers = mock(ClientEconomicsCustomerRepository.class);
        contacts = mock(ClientEconomicsContactRepository.class);

        checker = new EanPrerequisiteChecker(capability, customers, contacts);

        // Default: all prerequisites satisfied
        when(capability.canSendElectronicInvoice(COMPANY_UUID)).thenReturn(true);
        when(customers.findByClientAndCompany(CLIENT_UUID, COMPANY_UUID))
                .thenReturn(Optional.of(syncedCustomer()));
        when(contacts.findByClientCompanyAndName(CLIENT_UUID, COMPANY_UUID, ATTENTION_NAME))
                .thenReturn(Optional.of(eInvoiceContact()));
    }

    // ----------------------- happy path -----------------------

    @Test
    void ok_when_all_prerequisites_satisfied() {
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNull(result, "Expected null when all prerequisites are satisfied");
    }

    // ----------------------- check 1: country -----------------------

    @Test
    void fails_when_country_is_not_DK() {
        BillingContext bc = billingContext(VALID_EAN, "SE", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("AGREEMENT_NOT_DANISH"));
    }

    @Test
    void fails_when_country_is_null() {
        BillingContext bc = billingContext(VALID_EAN, null, ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("AGREEMENT_NOT_DANISH"));
    }

    @Test
    void country_check_is_case_insensitive() {
        BillingContext bc = billingContext(VALID_EAN, "dk", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNull(result, "Country check should accept lowercase 'dk'");
    }

    // ----------------------- check 2: agreement capability -----------------------

    @Test
    void fails_when_canSendElectronicInvoice_false() {
        when(capability.canSendElectronicInvoice(COMPANY_UUID)).thenReturn(false);
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("AGREEMENT_CANNOT_SEND_EAN"));
    }

    @Test
    void skips_capability_check_when_country_not_DK() {
        // When country is not DK, the capability check is skipped entirely.
        when(capability.canSendElectronicInvoice(COMPANY_UUID)).thenReturn(false);
        BillingContext bc = billingContext(VALID_EAN, "SE", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("AGREEMENT_NOT_DANISH"));
        assertFalse(result.getFailures().containsKey("AGREEMENT_CANNOT_SEND_EAN"),
                "Capability check should be skipped when country is not DK");
    }

    // ----------------------- check 3+4: EAN -----------------------

    @Test
    void fails_when_ean_missing() {
        BillingContext bc = billingContext(null, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("EAN_MISSING"));
    }

    @Test
    void fails_when_ean_blank() {
        BillingContext bc = billingContext("   ", "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("EAN_MISSING"));
    }

    @Test
    void fails_when_ean_fails_check_digit() {
        // 5790000000019 has wrong check digit (should be 2, not 9)
        BillingContext bc = billingContext("5790000000019", "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("EAN_INVALID"));
    }

    // ----------------------- check 5: customer synced -----------------------

    @Test
    void fails_when_customer_not_synced() {
        when(customers.findByClientAndCompany(CLIENT_UUID, COMPANY_UUID))
                .thenReturn(Optional.empty());
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("CUSTOMER_NOT_SYNCED"));
    }

    @Test
    void fails_when_customer_missing_objectVersion() {
        ClientEconomicsCustomer customer = syncedCustomer();
        customer.setObjectVersion(null);
        when(customers.findByClientAndCompany(CLIENT_UUID, COMPANY_UUID))
                .thenReturn(Optional.of(customer));
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("CUSTOMER_NEMHANDEL_UNCONFIGURED"));
    }

    // ----------------------- check 6: billing attention -----------------------

    @Test
    void fails_when_contract_billing_attention_missing() {
        BillingContext bc = billingContext(VALID_EAN, "DK", null);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("BILLING_ATTENTION_MISSING"));
    }

    @Test
    void fails_when_contract_billing_attention_blank() {
        BillingContext bc = billingContext(VALID_EAN, "DK", "  ");

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("BILLING_ATTENTION_MISSING"));
    }

    @Test
    void fails_when_contract_is_null() {
        // BillingContext with null contract
        BillingContext bc = new BillingContext(makeInvoice(), null, makeClient(VALID_EAN, "DK"));

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("BILLING_ATTENTION_MISSING"));
    }

    // ----------------------- check 7: contact receiveEInvoices -----------------------

    @Test
    void fails_when_contact_missing() {
        when(contacts.findByClientCompanyAndName(CLIENT_UUID, COMPANY_UUID, ATTENTION_NAME))
                .thenReturn(Optional.empty());
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("CONTACT_MISSING"));
    }

    @Test
    void fails_when_contact_missing_receiveEInvoices() {
        ClientEconomicsContact contact = eInvoiceContact();
        contact.setReceiveEInvoices(false);
        when(contacts.findByClientCompanyAndName(CLIENT_UUID, COMPANY_UUID, ATTENTION_NAME))
                .thenReturn(Optional.of(contact));
        BillingContext bc = billingContext(VALID_EAN, "DK", ATTENTION_NAME);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("CONTACT_RECEIVE_EINVOICES_FALSE"));
    }

    @Test
    void skips_contact_check_when_attention_missing() {
        // When billing attention is null, contact check is not executed -- only
        // BILLING_ATTENTION_MISSING should appear.
        when(contacts.findByClientCompanyAndName(any(), any(), any()))
                .thenReturn(Optional.empty());
        BillingContext bc = billingContext(VALID_EAN, "DK", null);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertTrue(result.getFailures().containsKey("BILLING_ATTENTION_MISSING"));
        assertFalse(result.getFailures().containsKey("CONTACT_MISSING"),
                "Contact check should be skipped when attention is missing");
    }

    // ----------------------- multiple failures -----------------------

    @Test
    void collects_multiple_failures_in_single_response() {
        // EAN missing + customer not synced + attention missing
        when(customers.findByClientAndCompany(CLIENT_UUID, COMPANY_UUID))
                .thenReturn(Optional.empty());
        BillingContext bc = billingContext(null, "DK", null);

        EanPrerequisiteErrorDto result = checker.check(bc);

        assertNotNull(result);
        assertEquals("EAN prerequisites not satisfied", result.getReason());
        assertTrue(result.getFailures().size() >= 3,
                "Expected at least 3 failures, got " + result.getFailures().size());
        assertTrue(result.getFailures().containsKey("EAN_MISSING"));
        assertTrue(result.getFailures().containsKey("CUSTOMER_NOT_SYNCED"));
        assertTrue(result.getFailures().containsKey("BILLING_ATTENTION_MISSING"));
    }

    // ----------------------- helpers -----------------------

    private BillingContext billingContext(String ean, String country, String attention) {
        Invoice invoice = makeInvoice();
        Client client = makeClient(ean, country);
        Contract contract = makeContract(attention);
        return new BillingContext(invoice, contract, client);
    }

    private Invoice makeInvoice() {
        Invoice invoice = new Invoice();
        invoice.uuid = UUID.randomUUID().toString();
        Company company = new Company();
        company.setUuid(COMPANY_UUID);
        company.setName("TestCo");
        invoice.company = company;
        return invoice;
    }

    private Client makeClient(String ean, String country) {
        Client client = new Client();
        client.setUuid(CLIENT_UUID);
        client.setName("Test Client");
        client.setEan(ean);
        client.setBillingCountry(country);
        return client;
    }

    private Contract makeContract(String attention) {
        Contract contract = new Contract();
        contract.setBillingAttention(attention);
        return contract;
    }

    private ClientEconomicsCustomer syncedCustomer() {
        ClientEconomicsCustomer customer = new ClientEconomicsCustomer();
        customer.setUuid(UUID.randomUUID().toString());
        customer.setClientUuid(CLIENT_UUID);
        customer.setCompanyUuid(COMPANY_UUID);
        customer.setCustomerNumber(1001);
        customer.setObjectVersion("obj-version-1");
        return customer;
    }

    private ClientEconomicsContact eInvoiceContact() {
        ClientEconomicsContact contact = new ClientEconomicsContact();
        contact.setUuid(UUID.randomUUID().toString());
        contact.setClientUuid(CLIENT_UUID);
        contact.setCompanyUuid(COMPANY_UUID);
        contact.setContactName(ATTENTION_NAME);
        contact.setReceiveEInvoices(true);
        return contact;
    }
}
