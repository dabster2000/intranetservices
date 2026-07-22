package dk.trustworks.intranet.recruitmentservice.services;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 DoD: specialization options resolve from the per-practice catalog
 * (keyed by practice uuid); a practice without a catalog has an empty
 * catalog (the UI hides the picker); malformed JSON degrades to empty.
 */
@QuarkusTest
class RecruitmentSpecializationCatalogTest {

    @Inject
    RecruitmentSpecializationCatalog catalog;

    @Inject
    EntityManager em;

    private final String practiceUuid = UUID.randomUUID().toString();

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", RecruitmentSpecializationCatalog.KEY_PREFIX + practiceUuid)
                        .executeUpdate());
    }

    @Test
    void catalogResolvesFromSettings_keyedByPracticeUuid() {
        seedCatalog("[\"Projektleder\",\"Programleder\"]");
        assertEquals(List.of("Projektleder", "Programleder"), catalog.forPractice(practiceUuid));
    }

    @Test
    void practiceWithoutCatalog_isEmpty() {
        assertTrue(catalog.forPractice(UUID.randomUUID().toString()).isEmpty());
        assertTrue(catalog.forPractice(null).isEmpty());
        assertTrue(catalog.forPractice("  ").isEmpty());
    }

    @Test
    void malformedCatalog_degradesToEmpty() {
        seedCatalog("not json at all");
        assertTrue(catalog.forPractice(practiceUuid).isEmpty());
    }

    private void seedCatalog(String value) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, :value, 'recruitment')
                                """)
                        .setParameter("key", RecruitmentSpecializationCatalog.KEY_PREFIX + practiceUuid)
                        .setParameter("value", value)
                        .executeUpdate());
    }
}
