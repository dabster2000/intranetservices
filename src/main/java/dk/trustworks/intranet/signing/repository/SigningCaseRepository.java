package dk.trustworks.intranet.signing.repository;

import dk.trustworks.intranet.signing.domain.SigningCase;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * Repository for signing case persistence operations.
 * Uses Panache pattern for simplified JPA access.
 *
 * Provides query methods for:
 * - User-scoped case listing
 * - Case lookup by NextSign key
 * - Status-based filtering
 */
@ApplicationScoped
public class SigningCaseRepository implements PanacheRepository<SigningCase> {

    /**
     * Find all signing cases for a specific user.
     * Results ordered by creation date descending (newest first).
     *
     * @param userUuid User UUID to filter by
     * @return List of user's signing cases, may be empty
     */
    public List<SigningCase> findByUserUuid(String userUuid) {
        return find("userUuid = ?1 ORDER BY createdAt DESC", userUuid).list();
    }

    /**
     * Find a signing case by its NextSign case key.
     * Case key is unique, so returns Optional.
     *
     * @param caseKey NextSign case key (_id from API)
     * @return Optional containing the case if found
     */
    public Optional<SigningCase> findByCaseKey(String caseKey) {
        return find("caseKey", caseKey).firstResultOptional();
    }

    /**
     * Find signing cases for a specific user with a specific status.
     * Results ordered by creation date descending (newest first).
     *
     * Useful for filtering UI views (e.g., show only COMPLETED cases).
     *
     * @param userUuid User UUID to filter by
     * @param status Case status to filter by (e.g., "COMPLETED", "PENDING")
     * @return List of matching cases, may be empty
     */
    public List<SigningCase> findByUserAndStatus(String userUuid, String status) {
        return find("userUuid = ?1 AND status = ?2 ORDER BY createdAt DESC",
                   userUuid, status).list();
    }

    /**
     * Check if a case exists by its NextSign case key.
     * More efficient than findByCaseKey when only existence matters.
     *
     * @param caseKey NextSign case key
     * @return true if a case with this key exists
     */
    public boolean existsByCaseKey(String caseKey) {
        return count("caseKey", caseKey) > 0;
    }

    /**
     * Count total signing cases for a user.
     *
     * @param userUuid User UUID
     * @return Number of cases owned by this user
     */
    public long countByUserUuid(String userUuid) {
        return count("userUuid", userUuid);
    }
}
