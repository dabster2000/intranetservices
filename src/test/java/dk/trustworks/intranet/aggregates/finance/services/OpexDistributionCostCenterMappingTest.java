package dk.trustworks.intranet.aggregates.finance.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * DB-free coverage for the groupname → cost-centre / expense-category mapping in
 * {@link OpexDistributionRefreshService}, which builds {@code fact_opex_distribution_mat}.
 *
 * <p>Why this exists: until Flyway V543 both this mapping and the twin CASE block in the
 * {@code fact_opex} view keyed administration cost on the literal
 * {@code "Øvrige administrationsomk. i alt"}. No such value has ever existed in
 * {@code accounting_categories} — the real groupname is {@code "Øvrige administrationsomk"},
 * with no {@code ". i alt"} suffix. The entry was dead, every administration account fell
 * through to the {@code getOrDefault} fallback, and the whole bucket was labelled
 * {@code GENERAL} instead of {@code ADMIN}. It went unnoticed for as long as it did precisely
 * because nothing asserted the keys against the values they are supposed to match.
 *
 * <p>The eight groupnames below are the complete contents of {@code accounting_categories}
 * in production as of 2026-08-30. If a category is renamed in the database, this test fails
 * — which is the point.
 */
class OpexDistributionCostCenterMappingTest {

    // Verbatim accounting_categories.groupname values (production, 2026-08-30).
    private static final String DELTE_SERVICES  = "Delte services";
    private static final String SALGSFREMMENDE  = "Salgsfremmende omkostninger";
    private static final String LOKALE          = "Lokaleomkostninger";
    private static final String VARIABLE        = "Variable omkostninger";
    private static final String OEVRIGE_ADMIN   = "Øvrige administrationsomk";

    @Test
    void administrationGroupnameResolvesToAdmin_notTheGeneralFallback() {
        // The V543 regression. "GENERAL" here means the arm is dead again.
        assertEquals("ADMIN", OpexDistributionRefreshService.resolveCostCenter(OEVRIGE_ADMIN),
                "administration OPEX must be labelled ADMIN, not swept into the GENERAL fallback");
    }

    @Test
    void administrationGroupnameResolvesToOtherOpexExpenseCategory() {
        assertEquals("OTHER_OPEX", OpexDistributionRefreshService.resolveExpenseCategory(OEVRIGE_ADMIN));
    }

    @Test
    void theSuffixedVariantIsNotTheKey() {
        // The exact string the dead entry used. It must NOT resolve — if it does, someone has
        // re-added the wrong key and the real groupname is falling through again.
        assertEquals("GENERAL",
                OpexDistributionRefreshService.resolveCostCenter("Øvrige administrationsomk. i alt"),
                "the '. i alt' variant does not exist in accounting_categories and must not be mapped");
    }

    @Test
    void everyProductionGroupnameMapsToItsOwnCostCentre() {
        assertEquals("HR_ADMIN",    OpexDistributionRefreshService.resolveCostCenter(DELTE_SERVICES));
        assertEquals("SALES",       OpexDistributionRefreshService.resolveCostCenter(SALGSFREMMENDE));
        assertEquals("FACILITIES",  OpexDistributionRefreshService.resolveCostCenter(LOKALE));
        assertEquals("INTERNAL_IT", OpexDistributionRefreshService.resolveCostCenter(VARIABLE));
        assertEquals("ADMIN",       OpexDistributionRefreshService.resolveCostCenter(OEVRIGE_ADMIN));
    }

    @Test
    void everyProductionGroupnameMapsToItsOwnExpenseCategory() {
        assertEquals("PEOPLE_NON_BILLABLE", OpexDistributionRefreshService.resolveExpenseCategory(DELTE_SERVICES));
        assertEquals("SALES_MARKETING",     OpexDistributionRefreshService.resolveExpenseCategory(SALGSFREMMENDE));
        assertEquals("OFFICE_FACILITIES",   OpexDistributionRefreshService.resolveExpenseCategory(LOKALE));
        assertEquals("TOOLS_SOFTWARE",      OpexDistributionRefreshService.resolveExpenseCategory(VARIABLE));
        assertEquals("OTHER_OPEX",          OpexDistributionRefreshService.resolveExpenseCategory(OEVRIGE_ADMIN));
    }

    @Test
    void noProductionGroupnameFallsThroughToGeneral() {
        // GENERAL is reserved for a category outside the taxonomy. If a real groupname lands
        // there, its arm is dead — the exact failure V543 fixed.
        for (String groupname : new String[]{DELTE_SERVICES, SALGSFREMMENDE, LOKALE, VARIABLE, OEVRIGE_ADMIN}) {
            assertNotEquals("GENERAL", OpexDistributionRefreshService.resolveCostCenter(groupname),
                    "groupname '" + groupname + "' must not fall through to the GENERAL fallback");
        }
    }

    @Test
    void unknownGroupnameStillFallsBackSafely() {
        // The fallback itself must survive — it is what catches a genuinely new category.
        assertEquals("GENERAL",    OpexDistributionRefreshService.resolveCostCenter("Some New Category"));
        assertEquals("OTHER_OPEX", OpexDistributionRefreshService.resolveExpenseCategory("Some New Category"));
        assertEquals("GENERAL",    OpexDistributionRefreshService.resolveCostCenter(null));
        assertEquals("OTHER_OPEX", OpexDistributionRefreshService.resolveExpenseCategory(null));
    }
}
