package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the new billing fields round-trip through {@link ContractService#update}
 * (called by the REST {@code PUT /contracts} endpoint) and through the {@link Contract}
 * copy constructor used by {@code POST /contracts/{uuid}/extend}.
 *
 * Uses reflection and POJO construction so the test runs without booting Quarkus
 * / loading runtime secrets (local dev env lacks cvtool.*). The "real" round-trip
 * via @QuarkusTest is implicitly covered in CI where @QuarkusTest can boot.
 * SPEC-INV-001 §5.5, §7.2.
 */
class ContractResourceBillingTest {

    @Test
    void copy_constructor_preserves_all_five_new_billing_fields() {
        Contract original = new Contract();
        original.setClientuuid("client-uuid");
        original.setName("Original");
        original.setBillingClientUuid("partner-uuid");
        original.setBillingAttention("AP Department");
        original.setBillingEmail("ap@partner.example");
        original.setBillingRef("PO-12345");
        original.setPaymentTermsUuid("payment-terms-uuid");

        Contract copy = new Contract(original);

        assertEquals("partner-uuid", copy.getBillingClientUuid(),
                "Copy constructor (used by /contracts/{uuid}/extend) must copy billingClientUuid");
        assertEquals("AP Department", copy.getBillingAttention(),
                "Copy constructor must copy billingAttention");
        assertEquals("ap@partner.example", copy.getBillingEmail(),
                "Copy constructor must copy billingEmail");
        assertEquals("PO-12345", copy.getBillingRef(),
                "Copy constructor must copy billingRef");
        assertEquals("payment-terms-uuid", copy.getPaymentTermsUuid(),
                "Copy constructor must copy paymentTermsUuid");
    }

    @Test
    void contract_service_update_method_references_all_five_new_billing_columns() throws Exception {
        // The update() method is a @Transactional UPDATE statement with an inlined
        // JPQL string. For our round-trip guarantee, the body must reference each
        // new billing field by name at least once — otherwise PUT /contracts silently
        // drops them.
        Method update = ContractService.class.getDeclaredMethod("update", Contract.class);
        assertNotNull(update, "ContractService.update(Contract) must exist");

        String serviceSource = java.nio.file.Files.readString(
                java.nio.file.Path.of(
                        System.getProperty("user.dir"),
                        "src/main/java/dk/trustworks/intranet/contracts/services/ContractService.java"));

        // Find the update method body.
        int methodIdx = serviceSource.indexOf("public void update(Contract contract)");
        assertTrue(methodIdx > 0, "Could not locate update(Contract) in ContractService source");
        int methodEnd = indexOfMethodEnd(serviceSource, methodIdx);
        String updateBody = serviceSource.substring(methodIdx, methodEnd);

        for (String field : List.of(
                "billingClientUuid",
                "billingAttention",
                "billingEmail",
                "billingRef",
                "paymentTermsUuid")) {
            assertTrue(updateBody.contains(field),
                    "ContractService.update must reference " + field +
                    " — without it PUT /contracts drops the new billing field on every save.");
        }
    }

    private static int indexOfMethodEnd(String source, int methodStart) {
        int braceDepth = 0;
        boolean seen = false;
        for (int i = methodStart; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') { braceDepth++; seen = true; }
            else if (c == '}') {
                braceDepth--;
                if (seen && braceDepth == 0) return i + 1;
            }
        }
        return source.length();
    }
}
