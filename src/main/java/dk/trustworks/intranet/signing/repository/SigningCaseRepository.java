package dk.trustworks.intranet.signing.repository;

import dk.trustworks.intranet.signing.domain.SigningCase;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    // ========================================================================
    // ASYNC STATUS FETCHING (Batch Job Support)
    // ========================================================================

    /**
     * Find cases that need status fetching (for batch job).
     * Includes:
     * - Cases with processing_status = PENDING_FETCH
     * - Cases with processing_status = FAILED that haven't exceeded max retries
     *   and enough time has passed since last attempt (retry delay)
     *
     * Used by NextSignStatusSyncBatchlet to find cases to process.
     *
     * @param maxRetries Maximum retry count before giving up
     * @param retryDelayMinutes Wait time before retrying failed cases
     * @return List of cases needing status fetch, ordered by creation date
     */
    public List<SigningCase> findCasesNeedingStatusFetch(int maxRetries, int retryDelayMinutes) {
        LocalDateTime retryThreshold = LocalDateTime.now().minusMinutes(retryDelayMinutes);

        return find(
            "processingStatus = 'PENDING_FETCH' OR " +
            "(processingStatus = 'FAILED' AND retryCount < ?1 AND " +
            "(lastStatusFetch IS NULL OR lastStatusFetch < ?2)) " +
            "ORDER BY createdAt ASC",
            maxRetries, retryThreshold
        ).list();
    }

    /**
     * Count cases by processing status (for monitoring/debugging).
     * Returns a map where:
     * - Key: processing status (e.g., "PENDING_FETCH", "COMPLETED")
     * - Value: number of cases with that status
     *
     * Example output: {"PENDING_FETCH": 5, "COMPLETED": 142, "FAILED": 2}
     *
     * Used by monitoring endpoint to track async processing health.
     *
     * @return Map of processing status counts
     */
    public Map<String, Long> countByProcessingStatus() {
        Map<String, Long> result = new HashMap<>();

        // Query to group by processing status and count
        List<Object[]> rows = getEntityManager()
            .createQuery(
                "SELECT sc.processingStatus, COUNT(sc) " +
                "FROM SigningCase sc " +
                "GROUP BY sc.processingStatus",
                Object[].class
            )
            .getResultList();

        // Convert to map
        for (Object[] row : rows) {
            String status = (String) row[0];
            Long count = (Long) row[1];
            result.put(status, count);
        }

        return result;
    }
}
