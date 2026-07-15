package dk.trustworks.intranet.aggregates.invoice.pricing;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceItemOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PricingEngineProvenanceTest {

    @Test
    void calculatedLineCarriesImmutableInputAndOutputFingerprints(){
        RuleStep step=new RuleStep();
        step.id="discount";
        step.label="Discount";
        step.type=RuleStepType.FIXED_DEDUCTION;
        step.base=StepBase.CURRENT_SUM;
        step.amount=new BigDecimal("10.00");
        RuleSet rules=new RuleSet();
        rules.contractTypeCode="PERIOD";
        rules.steps=java.util.List.of(step);
        PricingEngine engine=new PricingEngine();
        engine.catalog=new PricingRuleCatalog(){
            @Override public RuleSet select(String contractTypeCode,LocalDate invoiceDate){return rules;}
        };
        engine.discountNormalizer=new InvoiceDiscountNormalizer();
        engine.registry=new SimpleMeterRegistry();
        Invoice invoice=new Invoice();
        invoice.uuid="invoice";
        invoice.invoicedate=LocalDate.parse("2026-02-17");
        invoice.contractType="PERIOD";
        InvoiceItem base=new InvoiceItem();
        base.setOrigin(InvoiceItemOrigin.BASE);
        base.setHours(1);
        base.setRate(100);
        invoice.invoiceitems.add(base);

        PriceResult result=engine.price(invoice, Map.of());
        InvoiceItem calculated=result.syntheticItems.getFirst();

        assertEquals("PRICING_PROVENANCE_V1",result.pricingPolicyVersion);
        assertEquals("PRICING_ENGINE_V1",result.calculationAlgorithmVersion);
        assertEquals(result.breakdown.getFirst().pricingInputFingerprint,
                calculated.getPricingInputFingerprint());
        assertEquals(result.breakdown.getFirst().pricingOutputFingerprint,
                calculated.getPricingOutputFingerprint());
        assertEquals(new BigDecimal("-10.000000000000"),calculated.getPricingOutputAmount());
        assertNotNull(calculated.getCalculationRef());
        assertTrue(calculated.getPricingInputFingerprint().matches("[0-9a-f]{64}"));
        assertTrue(calculated.getPricingOutputFingerprint().matches("[0-9a-f]{64}"));
    }
}
