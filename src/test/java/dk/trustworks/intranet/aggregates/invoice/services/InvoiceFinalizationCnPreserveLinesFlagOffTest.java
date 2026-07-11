package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.CreatedResult;
import dk.trustworks.intranet.aggregates.invoice.economics.InvoiceToEconomicsDraftMapper;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoice;
import dk.trustworks.intranet.aggregates.invoice.economics.draft.EconomicsDraftInvoiceApiClient;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.model.Company;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DB-backed test for the credit-note finalization guard (framework-agreements redesign
 * §9.6) with {@code feature.invoice.cn-preserve-lines} left at its default ({@code false}).
 *
 * <p>Default OFF must be byte-identical to the pre-guard behavior:
 * {@link InvoiceFinalizationOrchestrator#createDraft(String)} re-runs
 * {@link InvoiceItemRecalculator} for CREDIT_NOTE invoices too, deleting the CALCULATED lines
 * copied from the source invoice and regenerating them from today's pricing rules.
 *
 * <p>The seeded CALCULATED line intentionally carries a rate (-500) that DISAGREES with what
 * the engine produces today (-1 000 = 10% of the 10 000 base), so regeneration is observable.
 *
 * <p><b>Environment note:</b> needs a MariaDB datasource and cannot boot in a DB-less sandbox.
 * Follows the {@code InvoiceServiceCreditNoteDraftEditTest} pattern: fixtures seeded in
 * explicit transactions, every uuid carries the {@link #TAG} prefix, cleanup in a
 * {@code finally} block so real data can never be touched.
 */
@QuarkusTest
@TestProfile(InvoiceFinalizationCnPreserveLinesFlagOffTest.DefaultFlagProfile.class)
class InvoiceFinalizationCnPreserveLinesFlagOffTest {

    /** Deliberately does NOT touch {@code feature.invoice.cn-preserve-lines} — tests the default. */
    public static class DefaultFlagProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder",
                    // Rest clients default to @Singleton; @InjectMock needs a normal scope
                    "quarkus.rest-client.economics-q2c-api.scope", "jakarta.enterprise.context.ApplicationScoped"
            );
        }
    }

    /** Unique marker so every fixture is isolated and cleanup is exact. */
    private static final String TAG = "cngrdoff-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    /** Unknown contract type → catalog resolves ONLY the injected invoice-discount fallback. */
    private static final String TEST_CONTRACT_TYPE = "CNGUARD_TEST_TYPE";

    @Inject InvoiceFinalizationOrchestrator orchestrator;
    @Inject EntityManager em;

    @InjectMock BillingContextResolver billingResolver;
    @InjectMock EconomicsAgreementResolver agreements;
    @InjectMock InvoiceToEconomicsDraftMapper mapper;
    @InjectMock BonusService bonus;
    @InjectMock InvoiceAttributionService attributionService;
    @InjectMock CreditNoteCoverageService creditCoverage;
    @InjectMock @RestClient EconomicsDraftInvoiceApiClient draftApi;

    @BeforeEach
    void stubEconomicsCollaborators() {
        Client billingClient = new Client();
        billingClient.setUuid(TAG + "client");
        when(billingResolver.resolve(any()))
                .thenAnswer(a -> new BillingContext(a.getArgument(0), new Contract(), billingClient));
        when(agreements.tokens(anyString()))
                .thenReturn(new EconomicsAgreementResolver.Tokens("APP", "GRANT"));
        when(agreements.paymentTermFor(any())).thenReturn(8);
        when(agreements.layoutNumber(anyString())).thenReturn(1);
        when(agreements.vatZoneFor(anyString(), anyString())).thenReturn(1);
        when(agreements.productNumber(anyString())).thenReturn("1");
        when(mapper.toDraft(any())).thenReturn(new EconomicsDraftInvoice());
        when(mapper.toLines(any())).thenReturn(List.of());
        CreatedResult created = new CreatedResult();
        created.setNumber(4521);
        when(draftApi.create(anyString(), anyString(), anyString(), any())).thenReturn(created);
    }

    @Test
    void creditNoteFinalization_flagOff_rerunsPricingEngine_currentBehavior() {
        String cnUuid = seedCreditNote();
        try {
            Invoice result = orchestrator.createDraft(cnUuid);

            assertEquals(InvoiceStatus.PENDING_REVIEW, result.status);
            assertEquals(4521, result.economicsDraftNumber);

            List<InvoiceItem> items = loadItems(cnUuid);
            assertEquals(2, items.size());

            // BASE line survives recalculation with its uuid preserved
            InvoiceItem base = itemByUuid(items, TAG + "base");
            assertEquals(1000.0, base.rate, 0.001);
            assertEquals(10.0, base.hours, 0.001);

            // The copied CALCULATED line (-500) is deleted and REGENERATED from today's rules (-1000)
            assertTrue(items.stream().noneMatch(ii -> (TAG + "calc").equals(ii.uuid)),
                    "with the flag OFF, the copied CALCULATED line must be replaced by the engine");
            InvoiceItem regenerated = items.stream()
                    .filter(ii -> ii.origin == InvoiceItemOrigin.CALCULATED)
                    .findFirst().orElseThrow();
            assertEquals("general-fallback", regenerated.ruleId);
            assertEquals(-1000.0, regenerated.rate, 0.001,
                    "engine must recompute the 10% invoice discount on the 10000 base");

            // Recalculation ran ⇒ the @Transient totals are populated
            assertNotNull(result.sumAfterDiscounts);
            assertEquals(10000.0, result.sumBeforeDiscounts, 0.001);
            assertEquals(9000.0, result.sumAfterDiscounts, 0.001);
            assertEquals(2250.0, result.vatAmount, 0.001);
            assertEquals(11250.0, result.grandTotal, 0.001);
        } finally {
            cleanup();
        }
    }

    // ============================ fixtures ============================

    /**
     * Seeds a CREDIT_NOTE draft carrying one BASE line (10h × 1000) and one "copied from
     * source" CALCULATED line (1 × -500, ruleId general-fallback) — the shape
     * {@code InvoiceService.createCreditNote} produces. Discount 10% ⇒ recalculation replaces
     * the CALCULATED line with a fresh -1000 line.
     */
    String seedCreditNote() {
        String cnUuid = TAG + "cn";
        QuarkusTransaction.requiringNew().run(() -> {
            Company company = Company.<Company>findAll().firstResult();
            assertNotNull(company, "test DB must contain at least one company row");

            // The orchestrator stamps billing_client_uuid, which carries an FK to client(uuid)
            Client billingClient = new Client();
            billingClient.setUuid(TAG + "client");
            billingClient.setName("CN guard billing client");
            billingClient.persist();

            Invoice inv = new Invoice();
            inv.uuid = cnUuid;
            inv.type = InvoiceType.CREDIT_NOTE;
            inv.status = InvoiceStatus.DRAFT;
            inv.contractuuid = TAG + "contract";
            inv.contractType = TEST_CONTRACT_TYPE;
            inv.year = 2026;
            inv.month = 5;
            inv.invoicedate = LocalDate.of(2026, 6, 30);
            inv.invoicenumber = 0;
            inv.currency = "DKK";
            inv.clientname = "CN guard test";
            inv.discount = 10.0;
            inv.vat = 25.0;
            inv.company = company;
            inv.creditnoteForUuid = TAG + "src";
            inv.invoiceitems = new LinkedList<>();
            inv.persist();

            InvoiceItem base = new InvoiceItem();
            base.uuid = TAG + "base";
            base.invoiceuuid = cnUuid;
            base.itemname = "Consulting";
            base.description = "Consulting";
            base.hours = 10.0;
            base.rate = 1000.0;
            base.position = 0;
            base.origin = InvoiceItemOrigin.BASE;
            base.sourceItemUuid = TAG + "srcitem1";
            base.persist();

            InvoiceItem calc = new InvoiceItem();
            calc.uuid = TAG + "calc";
            calc.invoiceuuid = cnUuid;
            calc.itemname = "Generel rabat (10%)";
            calc.description = "";
            calc.hours = 1.0;
            calc.rate = -500.0;   // deliberately NOT what today's engine computes (-1000)
            calc.position = 1;
            calc.origin = InvoiceItemOrigin.CALCULATED;
            calc.ruleId = "general-fallback";
            calc.label = "Generel rabat (10%)";
            calc.calculationRef = TAG + "ref";
            calc.sourceItemUuid = TAG + "srcitem2";
            calc.persist();
        });
        return cnUuid;
    }

    List<InvoiceItem> loadItems(String invoiceUuid) {
        return QuarkusTransaction.requiringNew().call(() ->
                InvoiceItem.<InvoiceItem>list("invoiceuuid", invoiceUuid));
    }

    static InvoiceItem itemByUuid(List<InvoiceItem> items, String uuid) {
        return items.stream().filter(ii -> uuid.equals(ii.uuid)).findFirst()
                .orElseThrow(() -> new AssertionError("expected invoice item to survive: " + uuid));
    }

    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            String like = TAG + "%";
            em.createNativeQuery("DELETE FROM invoiceitems WHERE uuid LIKE :p OR invoiceuuid LIKE :p")
                    .setParameter("p", like).executeUpdate();
            em.createNativeQuery("DELETE FROM invoices WHERE uuid LIKE :p")
                    .setParameter("p", like).executeUpdate();
            em.createNativeQuery("DELETE FROM client WHERE uuid LIKE :p")
                    .setParameter("p", like).executeUpdate();
        });
    }
}
