package dk.trustworks.intranet.aggregates.invoice.services;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link UserCompanyResolver}. Exercises the native SQL with
 * real MariaDB so we validate both the window function and the IN-clause binding.
 */
@QuarkusTest
@TestProfile(UserCompanyResolverTest.NoDevServicesProfile.class)
class UserCompanyResolverTest {

    public static class NoDevServicesProfile implements QuarkusTestProfile {
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
    UserCompanyResolver resolver;

    @Inject
    EntityManager em;

    @Test
    void resolveCompanies_emptyInput_returnsEmptyMap() {
        Map<String, String> result = resolver.resolveCompanies(Set.of(), LocalDate.now());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveCompanies_nullInput_returnsEmptyMap() {
        Map<String, String> result = resolver.resolveCompanies(null, LocalDate.now());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void resolveCompanies_nullAsOf_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveCompanies(Set.of("u"), null));
    }

    @Test
    @Transactional
    @SuppressWarnings("unchecked")
    void resolveCompanies_seededUser_returnsNonBlankCompanyUuid() {
        // Find a known user with a userstatus row in the test DB.
        List<Object[]> seed = em.createNativeQuery("""
                SELECT useruuid, companyuuid
                FROM userstatus
                WHERE statusdate <= :asOf
                  AND companyuuid IS NOT NULL
                ORDER BY statusdate DESC
                LIMIT 1
                """)
                .setParameter("asOf", LocalDate.now())
                .getResultList();
        if (seed.isEmpty()) return; // no test data — skip gracefully

        String userUuid = (String) seed.get(0)[0];
        String expectedCompany = (String) seed.get(0)[1];

        Map<String, String> result = resolver.resolveCompanies(Set.of(userUuid), LocalDate.now());
        assertFalse(result.isEmpty(), "Should resolve the seeded user");
        assertEquals(expectedCompany, result.get(userUuid));
        assertNotNull(result.get(userUuid));
        assertFalse(result.get(userUuid).isBlank());
    }
}
