package dk.trustworks.intranet.aggregates.invoice.economics;

import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContact;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsContactRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomer;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.ClientEconomicsCustomerRepository;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftLine;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.model.Company;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Failing TDD tests for {@link InvoiceToEconomicsDraftMapper} (H4).
 * These tests will not compile until H5 adds InvoiceToEconomicsDraftMapper and DraftContext.
 * SPEC-INV-001 §6.4, §6.5, §6.7.
 */
@QuarkusTest
class InvoiceToEconomicsDraftMapperTest {

    @Inject InvoiceToEconomicsDraftMapper mapper;

    @InjectMock ClientEconomicsCustomerRepository customerRepo;
    @InjectMock ClientEconomicsContactRepository contactRepo;

    // ── test 1: standard invoice maps all flat fields ──────────────────────────

    @Test
    void maps_standard_invoice_to_flat_Q2C_draft() {
        Client billing = makeClient("bc", "Banedanmark", "18632276", ClientType.CLIENT);
        billing.setBillingAddress("Carsten Niebuhrs Gade 43");
        billing.setBillingZipcode("1577");
        billing.setBillingCity("København V");
        billing.setBillingCountry("DK");

        Contract contract = makeContract("con1", "bc", "Thomas Vinther", "PO-30000783");

        Invoice inv = makeInvoice(InvoiceType.INVOICE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.of(2026, 4, 15));
        inv.setCurrency("DKK");
        inv.setSpecificdescription("April 2026 konsulenttimer");
        inv.setProjectref("PROJ-123");
        inv.setInvoiceitems(List.of(makeItem("consultant", "Developer hours",
                new BigDecimal("80"), new BigDecimal("1200"))));

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.of(makeMapping("bc", "co-1", 1042)));
        when(contactRepo.findByClientCompanyAndName("bc", "co-1", "Thomas Vinther"))
                .thenReturn(Optional.of(makeContactMapping("bc", "co-1", "Thomas Vinther", 67)));

        DraftContext ctx = new DraftContext(inv, contract, billing,
                /*layout*/ 22, /*paymentTerm*/ 5, /*vatZone*/ 1, /*productNumber*/ "1");
        EconomicsDraftInvoice draft = mapper.toDraft(ctx);

        assertEquals(1042, draft.getCustomerNumber());
        assertEquals("Banedanmark",                 draft.getCustomerName());
        assertEquals(LocalDate.of(2026, 4, 15),     draft.getDate());
        assertEquals("DKK",                         draft.getCurrencyCode());
        assertEquals(22,                            draft.getLayoutNumber());
        assertEquals(5,                             draft.getTermOfPaymentNumber());
        assertEquals(1,                             draft.getVatZoneNumber());
        assertEquals("Carsten Niebuhrs Gade 43",    draft.getCustomerAddress());
        assertEquals("1577",                        draft.getCustomerPostalCode());
        assertEquals("København V",                 draft.getCustomerCity());
        assertEquals("Denmark",                     draft.getCustomerCountry());
        assertEquals("PROJ-123",                    draft.getOtherReference());
        assertEquals("Faktura",                     draft.getHeading());
        assertEquals("April 2026 konsulenttimer",   draft.getTextLine1());
        assertEquals("PO-30000783",                 draft.getTextLine2());
        assertEquals(67,                            draft.getAttentionNumber());
    }

    // ── test 2: credit note heading ────────────────────────────────────────────

    @Test
    void heading_is_Kreditnota_for_credit_note() {
        Client billing = makeClient("bc", "X", "1", ClientType.CLIENT);
        Contract contract = makeContract("c", "bc", null, null);

        Invoice inv = makeInvoice(InvoiceType.CREDIT_NOTE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.now());
        inv.setCurrency("DKK");

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.of(makeMapping("bc", "co-1", 101)));

        DraftContext ctx = new DraftContext(inv, contract, billing, 22, 5, 1, "1");
        assertEquals("Kreditnota", mapper.toDraft(ctx).getHeading());
    }

    // ── test 3: no attention when billingAttention is null ─────────────────────

    @Test
    void omits_attentionNumber_when_contract_has_no_billing_attention() {
        Client billing = makeClient("bc", "X", "1", ClientType.CLIENT);
        Contract contract = makeContract("c", "bc", null, null);

        Invoice inv = makeInvoice(InvoiceType.INVOICE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.now());
        inv.setCurrency("DKK");

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.of(makeMapping("bc", "co-1", 101)));

        DraftContext ctx = new DraftContext(inv, contract, billing, 22, 5, 1, "1");
        assertNull(mapper.toDraft(ctx).getAttentionNumber());
    }

    // ── test 4: priced lines carry productNumber + quantity ────────────────────

    @Test
    void priced_lines_include_productNumber_and_quantity() {
        Client billing = makeClient("bc", "X", "1", ClientType.CLIENT);
        Contract contract = makeContract("c", "bc", null, null);

        Invoice inv = makeInvoice(InvoiceType.INVOICE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.now());
        inv.setCurrency("DKK");
        inv.setInvoiceitems(List.of(
                makeItem("Ole", "Dev hours", new BigDecimal("10"), new BigDecimal("1200"))));

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.of(makeMapping("bc", "co-1", 101)));

        DraftContext ctx = new DraftContext(inv, contract, billing, 22, 5, 1, "42");
        List<EconomicsDraftLine> lines = mapper.toLines(ctx);

        assertEquals(1, lines.size());
        assertEquals("42",     lines.get(0).getProductNumber());
        assertEquals(10.0,     lines.get(0).getQuantity());
        assertEquals(1200.0,   lines.get(0).getUnitNetPrice());
        assertTrue(lines.get(0).getDescription().contains("Ole"));
        assertTrue(lines.get(0).getDescription().contains("Dev hours"));
    }

    // ── test 5: credit note lines have negative unitNetPrice ──────────────────

    @Test
    void credit_note_lines_have_negative_unitNetPrice() {
        Client billing = makeClient("bc", "X", "1", ClientType.CLIENT);
        Contract contract = makeContract("c", "bc", null, null);

        Invoice inv = makeInvoice(InvoiceType.CREDIT_NOTE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.now());
        inv.setCurrency("DKK");
        inv.setInvoiceitems(List.of(
                makeItem("Ole", "Dev hours", new BigDecimal("10"), new BigDecimal("1200"))));

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.of(makeMapping("bc", "co-1", 101)));

        DraftContext ctx = new DraftContext(inv, contract, billing, 22, 5, 1, "42");
        List<EconomicsDraftLine> lines = mapper.toLines(ctx);

        assertEquals(-1200.0, lines.get(0).getUnitNetPrice());
    }

    // ── test 6: unpaired client throws IllegalStateException ──────────────────

    @Test
    void throws_IllegalStateException_when_customer_not_paired() {
        Client billing = makeClient("bc", "Unpaired", "1", ClientType.CLIENT);
        Contract contract = makeContract("c", "bc", null, null);

        Invoice inv = makeInvoice(InvoiceType.INVOICE, "co-1", "bc");
        inv.setInvoicedate(LocalDate.now());
        inv.setCurrency("DKK");

        when(customerRepo.findByClientAndCompany("bc", "co-1"))
                .thenReturn(Optional.empty());

        DraftContext ctx = new DraftContext(inv, contract, billing, 22, 5, 1, "1");
        assertThrows(IllegalStateException.class, () -> mapper.toDraft(ctx));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Creates a Client with the minimal fields used by the mapper.
     * billingCountry defaults to "DK" via Client no-arg constructor.
     */
    private Client makeClient(String uuid, String name, String cvr, ClientType type) {
        Client c = new Client();
        c.setUuid(uuid);
        c.setName(name);
        c.setCvr(cvr);
        c.setType(type);
        return c;
    }

    /**
     * Creates an Invoice linked to a Company UUID and a billingClientUuid.
     * company field is a Company entity — we set uuid only (no DB required in unit tests).
     */
    private Invoice makeInvoice(InvoiceType type, String companyUuid, String billingClientUuid) {
        Invoice inv = new Invoice();
        inv.setType(type);
        inv.setBillingClientUuid(billingClientUuid);
        Company company = new Company();
        company.setUuid(companyUuid);
        inv.setCompany(company);
        inv.setInvoiceitems(List.of()); // default empty; individual tests override
        return inv;
    }

    /**
     * Creates an InvoiceItem.
     * itemname → maps to "consultant/person" label in description.
     * hours and rate come from BigDecimal for test readability; InvoiceItem stores double.
     */
    private InvoiceItem makeItem(String itemname, String description,
                                  BigDecimal hours, BigDecimal rate) {
        InvoiceItem item = new InvoiceItem();
        item.setItemname(itemname);
        item.setDescription(description);
        item.setHours(hours.doubleValue());
        item.setRate(rate.doubleValue());
        return item;
    }

    /**
     * Creates a Contract with billingAttention and billingRef.
     * billingClientUuid is set to simulate the billing client linkage.
     */
    private Contract makeContract(String uuid, String billingClientUuid,
                                   String billingAttention, String billingRef) {
        Contract c = new Contract();
        c.setUuid(uuid);
        c.setBillingClientUuid(billingClientUuid);
        c.setBillingAttention(billingAttention);
        c.setBillingRef(billingRef);
        return c;
    }

    /**
     * Creates a ClientEconomicsCustomer pairing.
     */
    private ClientEconomicsCustomer makeMapping(String clientUuid, String companyUuid,
                                                 int customerNumber) {
        ClientEconomicsCustomer m = new ClientEconomicsCustomer();
        m.setUuid(UUID.randomUUID().toString());
        m.setClientUuid(clientUuid);
        m.setCompanyUuid(companyUuid);
        m.setCustomerNumber(customerNumber);
        return m;
    }

    /**
     * Creates a ClientEconomicsContact pairing.
     */
    private ClientEconomicsContact makeContactMapping(String clientUuid, String companyUuid,
                                                       String contactName,
                                                       int customerContactNumber) {
        ClientEconomicsContact ct = new ClientEconomicsContact();
        ct.setUuid(UUID.randomUUID().toString());
        ct.setClientUuid(clientUuid);
        ct.setCompanyUuid(companyUuid);
        ct.setContactName(contactName);
        ct.setCustomerContactNumber(customerContactNumber);
        return ct;
    }
}
