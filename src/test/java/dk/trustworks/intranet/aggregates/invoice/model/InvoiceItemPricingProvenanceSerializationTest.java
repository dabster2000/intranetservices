package dk.trustworks.intranet.aggregates.invoice.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;

class InvoiceItemPricingProvenanceSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void internalPricingAndCreditEvidenceNeverCrossesThePublicJsonBoundary() throws Exception {
        InvoiceItem item = new InvoiceItem();
        item.setPricingPolicyVersion("policy");
        item.setPricingInputFingerprint("input");
        item.setPricingOutputFingerprint("output");
        item.setPricingOutputAmount(new BigDecimal("1.000000000000"));
        item.setCreditCopyKind("SCALED");
        item.setCreditCopyFingerprint("credit");

        String json = mapper.writeValueAsString(item);

        assertFalse(json.contains("pricingPolicyVersion"));
        assertFalse(json.contains("pricingInputFingerprint"));
        assertFalse(json.contains("pricingOutputFingerprint"));
        assertFalse(json.contains("pricingOutputAmount"));
        assertFalse(json.contains("creditCopyKind"));
        assertFalse(json.contains("creditCopyFingerprint"));
    }

    @Test
    void copiedAttributionEvidenceNeverCrossesThePublicJsonBoundary() throws Exception {
        InvoiceItemAttribution attribution = new InvoiceItemAttribution();
        attribution.setSourceItemUuid("source-item");
        attribution.setSourceAttributionUuid("source-attribution");
        attribution.setCopyProvenance("copy");
        attribution.setSourceDistributionFingerprint("distribution");
        attribution.setAttributionDependencyFingerprint("dependency");

        String json = mapper.writeValueAsString(attribution);

        assertFalse(json.contains("sourceItemUuid"));
        assertFalse(json.contains("sourceAttributionUuid"));
        assertFalse(json.contains("copyProvenance"));
        assertFalse(json.contains("sourceDistributionFingerprint"));
        assertFalse(json.contains("attributionDependencyFingerprint"));
    }
}
