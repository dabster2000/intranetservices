package dk.trustworks.intranet.aggregates.invoice.economics;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DB-backed test for the seeded intercompany_account_mapping rows (V375) — AC8.
 *
 * Requires a database. DEFERRED in CI (the CI runner has no DB); run locally
 * against a freshly-migrated dev DB with:
 *   ./mvnw test -Dtest=IntercompanyAccountMappingRepositoryTest
 * The mandatory staging e-conomic verification is the production gate.
 */
@QuarkusTest
class IntercompanyAccountMappingRepositoryTest {

    private static final String AS_UUID    = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String TECH_UUID  = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";
    private static final String CYBER_UUID = "e4b0a2a4-0963-4153-b0a2-a409637153a2";

    @Inject
    IntercompanyAccountMappingRepository repository;

    @Test
    void seeded_technology_pair_resolves_to_3050() {
        Optional<IntercompanyAccountMapping> m = repository.findByDebtorAndIssuer(AS_UUID, TECH_UUID);
        assertTrue(m.isPresent(), "Technology->A/S mapping must be seeded");
        assertEquals(3050, m.get().getEconomicsCostAccountNumber());
    }

    @Test
    void seeded_cyber_pair_resolves_to_3055() {
        Optional<IntercompanyAccountMapping> m = repository.findByDebtorAndIssuer(AS_UUID, CYBER_UUID);
        assertTrue(m.isPresent(), "Cyber->A/S mapping must be seeded");
        assertEquals(3055, m.get().getEconomicsCostAccountNumber());
    }

    @Test
    void exactly_two_rows_are_seeded_on_a_freshly_migrated_db() {
        // AC8 — on a freshly-migrated DB the migration seeds exactly two rows.
        assertEquals(2, repository.count());
    }
}
