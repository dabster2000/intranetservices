package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for {@link IntercompanyClientResolver}.
 *
 * <p>Uses the live MariaDB test database. Each test seeds its own unique Company
 * + Client rows (keyed by a UUID prefix) and asserts the resolver returns the
 * right Client via the {@code client.cvr = companies.cvr} join.
 *
 * <p>SPEC: internal-invoice-billing-client-fix § FR-1, AC-11.
 */
@QuarkusTest
@TestProfile(IntercompanyClientResolverTest.DefaultProfile.class)
class IntercompanyClientResolverTest {

    public static class DefaultProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.s3.devservices.enabled", "false",
                    "cvtool.username", "test-placeholder",
                    "cvtool.password", "test-placeholder"
            );
        }
    }

    @Inject
    IntercompanyClientResolver resolver;

    @Inject
    EntityManager em;

    @Test
    @Transactional
    void resolveByDebtorCompanyUuid_hit_returnsClientWithMatchingCvr() {
        Fixture fx = seed("resolver-hit");

        Optional<Client> result = resolver.resolveByDebtorCompanyUuid(fx.companyUuid);

        assertTrue(result.isPresent(), "Expected a Client match for CVR " + fx.cvr);
        assertEquals(fx.clientUuid, result.get().getUuid(),
                "Resolver must return the Client whose CVR matches the debtor Company's CVR");
        assertEquals(fx.cvr, result.get().getCvr());
    }

    @Test
    @Transactional
    void resolveByDebtorCompanyUuid_miss_returnsEmpty() {
        // Seed only a Company — no Client with matching CVR.
        String companyUuid = "co-miss-" + UUID.randomUUID();
        String uniqueCvr = "cvr-miss-" + UUID.randomUUID().toString().substring(0, 8);
        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, 'Test Co Miss', :cvr, 'x', 'x', 'x', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", companyUuid)
                .setParameter("cvr", uniqueCvr)
                .executeUpdate();

        Optional<Client> result = resolver.resolveByDebtorCompanyUuid(companyUuid);

        assertFalse(result.isPresent(),
                "Resolver must return empty when no Client row has the debtor Company's CVR");
    }

    @Test
    @Transactional
    void resolveByDebtorCompanyUuid_missingCompany_returnsEmpty() {
        String nonExistentCompanyUuid = "co-does-not-exist-" + UUID.randomUUID();

        Optional<Client> result = resolver.resolveByDebtorCompanyUuid(nonExistentCompanyUuid);

        assertFalse(result.isPresent(),
                "Resolver must return empty (never throw) when the Company does not exist");
    }

    @Test
    void resolveByDebtorCompanyUuid_nullUuid_returnsEmpty() {
        assertFalse(resolver.resolveByDebtorCompanyUuid(null).isPresent());
        assertFalse(resolver.resolveByDebtorCompanyUuid("").isPresent());
        assertFalse(resolver.resolveByDebtorCompanyUuid("   ").isPresent());
    }

    // ── fixture helpers ────────────────────────────────────────────────────────

    private static final class Fixture {
        String companyUuid;
        String clientUuid;
        String cvr;
    }

    private Fixture seed(String prefix) {
        Fixture fx = new Fixture();
        fx.companyUuid = "co-" + prefix + "-" + UUID.randomUUID();
        fx.clientUuid = "cl-" + prefix + "-" + UUID.randomUUID();
        fx.cvr = "cvr-" + UUID.randomUUID().toString().substring(0, 8);

        em.createNativeQuery("""
                INSERT INTO companies (uuid, name, cvr, address, zipcode, city, country, regnr, account, phone, email)
                VALUES (:uuid, :name, :cvr, 'x', 'x', 'x', 'DK', '', '', '', '')
                """)
                .setParameter("uuid", fx.companyUuid)
                .setParameter("name", "Test Co " + prefix)
                .setParameter("cvr", fx.cvr)
                .executeUpdate();

        em.createNativeQuery("""
                INSERT INTO client (uuid, active, contactname, name, crmid, accountmanager,
                                    managed, type, cvr, billing_country, currency, created)
                VALUES (:uuid, 1, 'tester', :name, '', '', 'INTRA', 'CLIENT', :cvr, 'DK', 'DKK', NOW())
                """)
                .setParameter("uuid", fx.clientUuid)
                .setParameter("name", "Intercompany " + prefix)
                .setParameter("cvr", fx.cvr)
                .executeUpdate();

        return fx;
    }
}
