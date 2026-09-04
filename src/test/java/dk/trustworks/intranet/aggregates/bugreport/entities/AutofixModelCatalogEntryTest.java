package dk.trustworks.intranet.aggregates.bugreport.entities;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the effort CSV parsing on the auto-fix model catalogue.
 *
 * <p>Plain JUnit deliberately — no {@code @QuarkusTest}, so this runs in the DB-free
 * CI tier that gates deploys, and the class is not named {@code *IT} (which the
 * surefire config never picks up).
 */
class AutofixModelCatalogEntryTest {

    private static AutofixModelCatalogEntry withEfforts(String csv) {
        AutofixModelCatalogEntry e = new AutofixModelCatalogEntry();
        e.modelId = "claude-test";
        e.supportedEfforts = csv;
        return e;
    }

    @Test
    void efforts_parsesCsvInOrder() {
        // Order matters: it drives the rendering order of the effort dropdown.
        assertIterableEquals(
                List.of("low", "medium", "high", "xhigh", "max"),
                withEfforts("low,medium,high,xhigh,max").efforts());
    }

    @Test
    void efforts_toleratesWhitespaceAndEmptyEntries() {
        assertIterableEquals(
                List.of("low", "high"),
                withEfforts(" low , , high ,").efforts());
    }

    @Test
    void efforts_emptyStringMeansModelTakesNoEffortFlag() {
        // Empty must mean "no effort flag", NOT "every level allowed" -- the caller
        // rejects an effort for such a model rather than silently sending one.
        assertEquals(Set.of(), withEfforts("").efforts());
        assertEquals(Set.of(), withEfforts("   ").efforts());
    }

    @Test
    void efforts_nullIsTreatedAsEmpty() {
        assertEquals(Set.of(), withEfforts(null).efforts());
    }

    @Test
    void efforts_sonnet46HasNoXhigh() {
        // Regression guard for the seeded value: xhigh arrived with Opus 4.7, so
        // offering it for Sonnet 4.6 would send the worker an effort the model rejects.
        Set<String> efforts = withEfforts("low,medium,high,max").efforts();
        assertTrue(efforts.contains("max"));
        assertFalse(efforts.contains("xhigh"));
    }
}
