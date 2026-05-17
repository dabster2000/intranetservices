package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.expenseservice.dto.AddAllowListEntryRequest;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the allow-list bypass via {@link ExpenseAIValidationService#isAllowListed}.
 *
 * The full validation pipeline integration is not tested here because it requires
 * mocking OpenAI. The bypass logic is verified at the helper-method level — call
 * sites that consult this method are tested separately.
 */
@QuarkusTest
class MerchantAllowListBypassTest {

    @Inject ExpenseAIValidationService validation;
    @Inject MerchantAllowListService allowList;

    @Test
    @Transactional
    void allow_listed_merchant_is_recognized() {
        allowList.add(new AddAllowListEntryRequest(
            "R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK",
            "Café Det Sker",
            "CONTAINS",
            null
        ));
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", "Café Det Sker, Kbh");
        assertTrue(bypassed, "Allow-listed merchant should be recognized");
    }

    @Test
    @Transactional
    void non_allow_listed_merchant_is_not_recognized() {
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", "Random Restaurant XYZ");
        assertTrue(!bypassed, "Non-allow-listed merchant should not be recognized");
    }

    @Test
    @Transactional
    void null_merchant_is_not_allow_listed() {
        boolean bypassed = validation.isAllowListed("R_MERCHANT_ALLOW_OFFICE_FOOD_DRINK", null);
        assertTrue(!bypassed, "Null merchant should not be allow-listed");
    }
}
