package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.DedupeCheckResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DedupeMatch;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 DoD (dedupe): same email → match; same LinkedIn slug with URL
 * variants → match; employee match flagged distinctly.
 */
@QuarkusTest
class CandidateDedupeServiceIntegrationTest {

    @Inject
    CandidateDedupeService dedupeService;

    @Inject
    EntityManager em;

    private String candidateUuid;
    private String employeeUuid;
    private String candidateEmail;
    private String employeeEmail;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        employeeUuid = UUID.randomUUID().toString();
        candidateEmail = "dedupe-" + candidateUuid.substring(0, 8) + "@example.com";
        employeeEmail = "dedupe-emp-" + employeeUuid.substring(0, 8) + "@example.com";
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, linkedin_url, status,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Dedupe', 'Fixture', :email,
                                    'https://www.linkedin.com/in/dedupe-fixture-1a2b3c/', 'ACTIVE',
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("email", candidateEmail)
                    .setParameter("actor", UUID.randomUUID().toString())
                    .executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                              created, cpr, birthday)
                            VALUES (:uuid, 'Employee', 'Fixture', :email, :username, 'x', 'CONSULTANT',
                                    NOW(), '0000000000', '2000-01-01')
                            """)
                    .setParameter("uuid", employeeUuid)
                    .setParameter("email", employeeEmail)
                    .setParameter("username", employeeUuid)
                    .executeUpdate();
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :uuid")
                    .setParameter("uuid", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid = :uuid")
                    .setParameter("uuid", employeeUuid).executeUpdate();
        });
    }

    @Test
    void sameEmail_matchesCandidate_caseInsensitively() {
        DedupeCheckResponse result = dedupeService.check(candidateEmail.toUpperCase(), null);

        List<DedupeMatch> candidateMatches = result.matches().stream()
                .filter(m -> m.type() == DedupeMatch.MatchType.CANDIDATE).toList();
        assertEquals(1, candidateMatches.size());
        assertEquals(candidateUuid, candidateMatches.get(0).uuid());
        assertEquals(DedupeMatch.MatchedOn.EMAIL, candidateMatches.get(0).matchedOn());
        assertEquals("Dedupe Fixture", candidateMatches.get(0).name());
    }

    @Test
    void linkedInUrlVariants_allMatchTheStoredProfile() {
        for (String variant : List.of(
                "https://www.linkedin.com/in/dedupe-fixture-1a2b3c",
                "linkedin.com/in/Dedupe-Fixture-1A2B3C?utm_source=share",
                "https://dk.linkedin.com/in/dedupe-fixture-1a2b3c/",
                "dedupe-fixture-1a2b3c")) {
            DedupeCheckResponse result = dedupeService.check(null, variant);
            assertTrue(result.matches().stream().anyMatch(m ->
                            m.type() == DedupeMatch.MatchType.CANDIDATE
                                    && m.uuid().equals(candidateUuid)
                                    && m.matchedOn() == DedupeMatch.MatchedOn.LINKEDIN),
                    "variant should match stored profile: " + variant);
        }
    }

    @Test
    void employeeEmailMatch_isFlaggedDistinctly() {
        DedupeCheckResponse result = dedupeService.check(employeeEmail, null);

        List<DedupeMatch> employeeMatches = result.matches().stream()
                .filter(m -> m.type() == DedupeMatch.MatchType.EMPLOYEE).toList();
        assertEquals(1, employeeMatches.size());
        assertEquals(employeeUuid, employeeMatches.get(0).uuid());
        assertEquals("Employee Fixture", employeeMatches.get(0).name());
        assertEquals(DedupeMatch.MatchedOn.EMAIL, employeeMatches.get(0).matchedOn());
    }

    @Test
    void matchingBothIdentifiers_returnsTheCandidateOnce() {
        DedupeCheckResponse result = dedupeService.check(
                candidateEmail, "https://www.linkedin.com/in/dedupe-fixture-1a2b3c/");
        long occurrences = result.matches().stream()
                .filter(m -> m.uuid().equals(candidateUuid)).count();
        assertEquals(1, occurrences, "a candidate matching on email AND LinkedIn appears once");
    }

    @Test
    void unknownIdentifiers_matchNothing() {
        DedupeCheckResponse result = dedupeService.check(
                "nobody-" + UUID.randomUUID() + "@example.com",
                "https://www.linkedin.com/in/no-such-profile-" + UUID.randomUUID());
        assertTrue(result.matches().isEmpty());
    }
}
