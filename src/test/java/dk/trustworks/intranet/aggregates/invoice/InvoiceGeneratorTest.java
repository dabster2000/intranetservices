package dk.trustworks.intranet.aggregates.invoice;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.model.Company;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvoiceGenerator#buildInitialInvoice}.
 *
 * <p>Tests verify that currency comes from {@link Client#getCurrency()} (not a
 * hardcoded literal) and that the VAT rate is resolved from
 * {@link EconomicsAgreementResolver#vatZoneDetailsFor}.
 *
 * Plain JUnit 5 + Mockito — no Quarkus boot required.
 */
@ExtendWith(MockitoExtension.class)
class InvoiceGeneratorTest {

    private static final String COMPANY_UUID  = "company-uuid-1";
    private static final String CONTRACT_UUID = "contract-uuid-1";
    private static final String PROJECT_UUID  = "project-uuid-1";

    @InjectMocks
    InvoiceGenerator generator;

    // All services used by the full createDraftInvoiceFromProject path are not
    // needed here — we test the extracted helper directly.
    @Mock
    EconomicsAgreementResolver agreements;

    // The remaining injected services must be present as @Mock so that
    // @InjectMocks can wire InvoiceGenerator without NPEs on the field list.
    @Mock dk.trustworks.intranet.aggregates.users.services.UserService userService;
    @Mock dk.trustworks.intranet.aggregates.invoice.services.InvoiceService invoiceService;
    @Mock dk.trustworks.intranet.contracts.services.ContractService contractService;
    @Mock dk.trustworks.intranet.dao.crm.services.ClientService clientService;
    @Mock dk.trustworks.intranet.dao.crm.services.ProjectService projectService;
    @Mock dk.trustworks.intranet.dao.crm.services.TaskService taskService;
    @Mock dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService invoiceAttributionService;
    @Mock dk.trustworks.intranet.dao.workservice.services.WorkService workService;
    @Mock RegisteredDeliveryEvidenceResolver registeredDeliveryEvidenceResolver;
    @Mock EntityManager em;

    private Contract contract;
    private Project  project;
    private Client   billingClient;
    private Company  company;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setUuid(COMPANY_UUID);
        company.setName("Trustworks A/S");

        contract = new Contract();
        contract.setUuid(CONTRACT_UUID);
        contract.setClientuuid("client-uuid-1");
        contract.setCompany(company);

        project = new Project();
        project.setUuid(PROJECT_UUID);
        project.setName("Test Project");

        billingClient = new Client();
        billingClient.setUuid("client-uuid-1");
        billingClient.setName("Acme A/S");
        billingClient.setBillingAddress("Testvej 1");
        billingClient.setBillingZipcode("1234");
        billingClient.setBillingCity("Copenhagen");
    }

    // ── Test 1: currency comes from billingClient.getCurrency() ─────────────────

    @Test
    void buildInitialInvoice_sets_currency_from_billingClient() {
        billingClient.setCurrency("EUR");
        stubVatZone("EUR", 25.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 1));

        assertEquals("EUR", invoice.currency,
                "currency must come from billingClient.getCurrency(), not a hardcoded 'DKK'");
    }

    @Test
    void buildInitialInvoice_does_not_hardcode_DKK_for_non_DKK_client() {
        billingClient.setCurrency("SEK");
        stubVatZone("SEK", 25.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 3));

        assertEquals("SEK", invoice.currency,
                "SEK client must produce SEK invoice, not DKK");
    }

    @Test
    void buildInitialInvoice_DKK_client_still_produces_DKK_invoice() {
        billingClient.setCurrency("DKK");
        stubVatZone("DKK", 25.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 6));

        assertEquals("DKK", invoice.currency);
    }

    // ── Test 2: vat comes from vatZoneDetailsFor ─────────────────────────────────

    @Test
    void buildInitialInvoice_sets_vat_from_vatZoneDetailsFor() {
        billingClient.setCurrency("DKK");
        stubVatZone("DKK", 25.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 1));

        assertEquals(25.0, invoice.getVat(), 0.001,
                "vat must come from agreements.vatZoneDetailsFor(), not a hardcoded constant");
    }

    @Test
    void buildInitialInvoice_uses_zero_vat_for_zero_rated_zone() {
        billingClient.setCurrency("EUR");
        stubVatZone("EUR", 0.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 2));

        assertEquals(0.0, invoice.getVat(), 0.001,
                "Zero-rated zone must produce vat=0.0");
    }

    @Test
    void buildInitialInvoice_passes_correct_currency_and_companyUuid_to_vatZoneDetailsFor() {
        billingClient.setCurrency("EUR");
        stubVatZone("EUR", 21.0);

        generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2025, 4));

        // verify the resolver was called with the right args (Mockito would throw if not)
        org.mockito.Mockito.verify(agreements).vatZoneDetailsFor("EUR", COMPANY_UUID);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void stubVatZone(String currency, double ratePercent) {
        when(agreements.vatZoneDetailsFor(currency, COMPANY_UUID))
                .thenReturn(new EconomicsAgreementResolver.VatZoneDetails(
                        1, BigDecimal.valueOf(ratePercent)));
    }

    // ── Test 4: contractref snapshots from billing_ref (not the old refid) ───────

    /**
     * Regression guard for commit f5389154.
     *
     * <p>Before the invoice-refs redesign, {@code Invoice.contractref} was populated
     * from {@code contract.getRefid()} (a field that no longer exists). After the
     * redesign it must come from {@code contract.getBillingRef()}.  This test makes
     * that behavioral contract explicit so a future refactor cannot silently regress.
     *
     * <p>We also assert {@code projectref} to guard the parallel snapshot of the
     * project's customer reference.
     */
    @Test
    void buildInitialInvoice_snapshots_contractref_from_billingRef_not_refid() {
        contract.setBillingRef("PO-30000783");
        project.setCustomerreference("CUST-REF-99");
        billingClient.setCurrency("DKK");
        stubVatZone("DKK", 25.0);

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, YearMonth.of(2026, 4));

        assertEquals("PO-30000783", invoice.contractref,
                "contractref must snapshot from contract.getBillingRef() (billing_ref column), "
                + "not from the old refid field that was removed in the redesign");
        assertEquals("CUST-REF-99", invoice.projectref,
                "projectref must snapshot from project.getCustomerreference()");
    }

    // ── Test 3: invoice date defaults to today ───────────────────────────────────

    @Test
    void buildInitialInvoice_setsInvoicedateToToday() {
        // Use a month that is NOT the current month to prove the default is
        // "today" and no longer "last day of month".
        YearMonth someOtherMonth = YearMonth.now().minusMonths(3);

        when(agreements.vatZoneDetailsFor(any(), any()))
                .thenReturn(new EconomicsAgreementResolver.VatZoneDetails(1, BigDecimal.valueOf(25)));

        Invoice invoice = generator.buildInitialInvoice(contract, project, billingClient, someOtherMonth);

        assertEquals(LocalDate.now(), invoice.getInvoicedate(),
                "Invoice date should default to today, not last-day-of-invoiced-month");
        assertEquals(LocalDate.now().plusMonths(1), invoice.getDuedate(),
                "Due date should default to today + 1 month (will be overwritten by e-conomics)");
    }

    @Test
    void contributionLineageCaptureUsesTheSharedBuildGate() {
        Query query = org.mockito.Mockito.mock(Query.class);
        when(em.createNativeQuery(InvoiceGenerator.CONTRIBUTION_CAPTURE_ENABLED_SQL)).thenReturn(query);
        when(query.getSingleResult()).thenReturn(false, true);

        assertFalse(generator.contributionLineageCaptureEnabled());
        assertTrue(generator.contributionLineageCaptureEnabled());
    }

    @Test
    void disabledContributionLineageCaptureDoesNotValidateOrPersistDarkEvidence() {
        InvoiceGenerator.ContributionLineagePlan plan = generator.prepareContributionDeliveryLineage(
                false, List.of(baseItem("item-1")), Map.of());

        assertTrue(plan.rows().isEmpty());
    }

    @Test
    void contributionLineagePlanRejectsMissingGeneratedItemsBeforePersistence() {
        InvoiceItem item = baseItem("item-1");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> generator.planContributionDeliveryLineage(
                        List.of(item), Map.of("missing-item", List.of(delivery("work-1", "effective-1")))));

        assertEquals("generated delivery lineage does not match generated BASE items", failure.getMessage());
    }

    @Test
    void contributionLineagePlanRejectsOneWorkMappedToDifferentEvidenceBeforePersistence() {
        InvoiceItem item = baseItem("item-1");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> generator.planContributionDeliveryLineage(List.of(item), Map.of("item-1", List.of(
                        delivery("work-1", "effective-1"),
                        delivery("work-1", "effective-2")))));

        assertEquals("ambiguous generated delivery lineage for work work-1", failure.getMessage());
    }

    @Test
    void contributionLineagePlanIsCompleteImmutableAndTimestampedBeforePersistence() {
        InvoiceItem item = baseItem("item-1");

        InvoiceGenerator.ContributionLineagePlan plan = generator.planContributionDeliveryLineage(
                List.of(item), Map.of("item-1", List.of(delivery("work-1", "effective-1"))));

        assertNotNull(plan.createdAt());
        assertEquals(1, plan.rows().size());
        InvoiceGenerator.PlannedLineageRow row = plan.rows().getFirst();
        assertEquals("item-1", row.invoiceItemUuid());
        assertEquals("work-1", row.seed().workUuid());
        assertEquals(64, row.itemFingerprint().length());
        assertEquals(64, row.distributionFingerprint().length());
        assertThrows(UnsupportedOperationException.class, () -> plan.rows().clear());
    }

    @Test
    void createDraftInvoiceJoinsTheGeneratorTransaction() throws Exception {
        Transactional annotation = InvoiceService.class.getMethod("createDraftInvoice", Invoice.class)
                .getAnnotation(Transactional.class);

        assertNotNull(annotation);
        assertEquals(Transactional.TxType.REQUIRED, annotation.value());
    }

    private static InvoiceItem baseItem(String uuid) {
        return new InvoiceItem(uuid, "registrant-1", "Consulting", "Delivery", 1000.0, 8.0,
                1, "invoice-1", InvoiceItemOrigin.BASE);
    }

    private static RegisteredDeliveryEvidenceResolver.ResolvedDelivery delivery(
            String workUuid, String effectiveConsultantUuid) {
        return new RegisteredDeliveryEvidenceResolver.ResolvedDelivery(
                workUuid, "registrant-1", effectiveConsultantUuid, LocalDate.of(2026, 7, 1),
                "task-1", "project-1", "contract-1", "contract-project-1",
                "contract-consultant-1", new BigDecimal("8.000000"),
                new BigDecimal("1000.000000"), new BigDecimal("8000.000000000000"),
                RegisteredDeliveryEvidenceResolver.RateResolutionStatus.RESOLVED);
    }
}
