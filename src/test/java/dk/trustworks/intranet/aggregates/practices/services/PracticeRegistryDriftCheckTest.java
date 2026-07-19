package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free unit tests for the {@link PracticeRegistryDriftCheck#diff} helper and
 * the invariant that the seeded registry core equals the hardcoded billable set
 * (so the startup check is silent in steady state). Same package for visibility.
 */
class PracticeRegistryDriftCheckTest {

    /** The five active core PRACTICE codes seeded by V418. */
    private static final Set<String> REGISTRY_CORE = Set.of("PM", "SA", "BA", "DEV", "CYB");

    @Test
    void identical_sets_report_no_drift() {
        PracticeRegistryDriftCheck.SetDrift drift =
                PracticeRegistryDriftCheck.diff("s", REGISTRY_CORE, Set.of("PM", "SA", "BA", "DEV", "CYB"));
        assertFalse(drift.hasDrift());
        assertTrue(drift.registryOnly().isEmpty());
        assertTrue(drift.hardcodedOnly().isEmpty());
    }

    @Test
    void code_missing_from_hardcoded_set_shows_up_as_registry_only() {
        PracticeRegistryDriftCheck.SetDrift drift =
                PracticeRegistryDriftCheck.diff("s", REGISTRY_CORE, Set.of("PM", "SA", "BA", "DEV"));
        assertTrue(drift.hasDrift());
        assertEquals(Set.of("CYB"), drift.registryOnly());
        assertTrue(drift.hardcodedOnly().isEmpty());
    }

    @Test
    void extra_code_in_hardcoded_set_shows_up_as_hardcoded_only() {
        PracticeRegistryDriftCheck.SetDrift drift =
                PracticeRegistryDriftCheck.diff("s", REGISTRY_CORE, Set.of("PM", "SA", "BA", "DEV", "CYB", "JK"));
        assertTrue(drift.hasDrift());
        assertTrue(drift.registryOnly().isEmpty());
        assertEquals(Set.of("JK"), drift.hardcodedOnly());
    }

    @Test
    void billable_practices_matches_the_seeded_registry_core() {
        // Steady-state invariant: the boot check must not fire against the V418 seed.
        PracticeRegistryDriftCheck.SetDrift drift =
                PracticeRegistryDriftCheck.diff("BILLABLE_PRACTICES", REGISTRY_CORE,
                        UtilizationCalculationHelper.BILLABLE_PRACTICES);
        assertFalse(drift.hasDrift(),
                "UtilizationCalculationHelper.BILLABLE_PRACTICES must equal the V418 core seed");
    }

    @Test
    void hardcoded_sets_are_registered_for_reconciliation() {
        assertTrue(PracticeRegistryDriftCheck.hardcodedSets()
                        .containsKey("UtilizationCalculationHelper.BILLABLE_PRACTICES"),
                "the billable-practices set must be reconciled against the registry");
    }
}
