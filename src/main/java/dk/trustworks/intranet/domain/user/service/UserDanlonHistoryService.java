package dk.trustworks.intranet.domain.user.service;

import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import dk.trustworks.intranet.expenseservice.model.UserAccount;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing historical Danløn employee numbers.
 * <p>
 * This service handles:
 * <ul>
 *   <li>Creating new Danløn history records when numbers change</li>
 *   <li>Querying temporal history (current and historical Danløn numbers)</li>
 *   <li>Backward compatibility with denormalized UserAccount.danlon field</li>
 *   <li>Validation and normalization of active dates</li>
 * </ul>
 * </p>
 * <p>
 * <b>Temporal Query Pattern:</b><br>
 * To find which Danløn number was active on a specific date, use {@link #getDanlonAsOf(String, LocalDate)}.
 * This returns the most recent record where active_date <= query date.
 * </p>
 * <p>
 * <b>Backward Compatibility Note:</b><br>
 * Until migration V111 is deployed (3-6 months), this service maintains the denormalized
 * {@code UserAccount.danlon} field for zero-downtime deployment. All updates automatically
 * sync to both the history table and the denormalized field.
 * </p>
 *
 * @see UserDanlonHistory Entity with static finder methods
 * @see UserAccount Denormalized field (to be removed in V111)
 */
@JBossLog
@ApplicationScoped
public class UserDanlonHistoryService {

    /**
     * Add a new Danløn history record for a user.
     * <p>
     * This method:
     * <ol>
     *   <li>Normalizes active date to 1st of month</li>
     *   <li>Validates no duplicate exists for same user + month</li>
     *   <li>Creates new history record</li>
     *   <li>Updates denormalized UserAccount.danlon field for backward compatibility</li>
     * </ol>
     * </p>
     * <p>
     * <b>Important:</b> The active_date will be normalized to the 1st of the month.
     * For example, 2025-01-15 becomes 2025-01-01.
     * </p>
     *
     * @param useruuid   User UUID
     * @param activeDate Date when this Danløn number becomes active (will be normalized to 1st of month)
     * @param danlon     New Danløn employee number
     * @param createdBy  Username or system identifier creating this record
     * @return The created history record
     * @throws IllegalArgumentException if duplicate record exists for same user + month
     */
    @Transactional
    public UserDanlonHistory addDanlonHistory(String useruuid, LocalDate activeDate, String danlon, String createdBy) {
        log.infof("Adding Danløn history for user %s: danlon=%s, activeDate=%s, createdBy=%s",
                useruuid, danlon, activeDate, createdBy);

        // Normalize active date to 1st of month
        LocalDate normalizedDate = activeDate.withDayOfMonth(1);
        if (!normalizedDate.equals(activeDate)) {
            log.warnf("Active date normalized from %s to %s for user %s", activeDate, normalizedDate, useruuid);
        }

        // Check for duplicate
        long existingCount = UserDanlonHistory.count("useruuid = ?1 AND active_date = ?2", useruuid, normalizedDate);
        if (existingCount > 0) {
            String error = String.format(
                    "Danløn history already exists for user %s on date %s. Use updateDanlonHistory() to modify existing records.",
                    useruuid, normalizedDate
            );
            log.error(error);
            throw new IllegalArgumentException(error);
        }

        // Create new history record
        UserDanlonHistory history = new UserDanlonHistory(useruuid, normalizedDate, danlon, createdBy);
        history.persist();
        log.infof("Created Danløn history record: uuid=%s, useruuid=%s, activeDate=%s, danlon=%s",
                history.getUuid(), useruuid, normalizedDate, danlon);

        // Update denormalized field for backward compatibility
        // This ensures existing code reading UserAccount.getDanlon() continues to work
        // TODO: Remove this after V111 migration is deployed (3-6 months)
        updateDenormalizedField(useruuid, danlon);

        return history;
    }

    /**
     * Update an existing Danløn history record.
     * <p>
     * <b>Use Case:</b> Correcting data entry errors or updating audit fields.
     * To change the active_date, you must delete the old record and create a new one.
     * </p>
     *
     * @param historyUuid UUID of the history record to update
     * @param newDanlon   Updated Danløn number
     * @return Updated history record
     * @throws IllegalArgumentException if record not found
     */
    @Transactional
    public UserDanlonHistory updateDanlonHistory(String historyUuid, String newDanlon) {
        UserDanlonHistory history = UserDanlonHistory.findById(historyUuid);
        if (history == null) {
            throw new IllegalArgumentException("Danløn history record not found: " + historyUuid);
        }

        log.infof("Updating Danløn history %s: old=%s, new=%s", historyUuid, history.getDanlon(), newDanlon);
        history.setDanlon(newDanlon);
        history.persist();

        // Update denormalized field if this is the current record
        String currentDanlon = UserDanlonHistory.findCurrentDanlon(history.getUseruuid());
        if (newDanlon.equals(currentDanlon)) {
            updateDenormalizedField(history.getUseruuid(), newDanlon);
        }

        return history;
    }

    /**
     * Delete a Danløn history record.
     * <p>
     * <b>Warning:</b> This permanently removes the history record.
     * Use with caution - typically only for correcting data entry errors.
     * </p>
     *
     * @param historyUuid UUID of the history record to delete
     * @throws IllegalArgumentException if record not found
     */
    @Transactional
    public void deleteDanlonHistory(String historyUuid) {
        UserDanlonHistory history = UserDanlonHistory.findById(historyUuid);
        if (history == null) {
            throw new IllegalArgumentException("Danløn history record not found: " + historyUuid);
        }

        log.warnf("Deleting Danløn history record: uuid=%s, useruuid=%s, activeDate=%s, danlon=%s",
                historyUuid, history.getUseruuid(), history.getActiveDate(), history.getDanlon());

        String useruuid = history.getUseruuid();
        history.delete();

        // Update denormalized field to reflect new current value
        String newCurrentDanlon = UserDanlonHistory.findCurrentDanlon(useruuid);
        updateDenormalizedField(useruuid, newCurrentDanlon);
    }

    /**
     * Get all Danløn history for a user, ordered by active date descending (newest first).
     *
     * @param useruuid User UUID
     * @return List of history records, empty list if none exist
     */
    public List<UserDanlonHistory> getHistory(String useruuid) {
        return UserDanlonHistory.findByUseruuid(useruuid);
    }

    /**
     * Get the Danløn number that was active on a specific date.
     * <p>
     * <b>Temporal Query:</b> Returns the most recent record where active_date <= asOfDate.
     * </p>
     * <p>
     * Example: If history has records for 2024-01-01 and 2024-07-01, then:
     * <ul>
     *   <li>Query date 2024-03-15 returns the 2024-01-01 record</li>
     *   <li>Query date 2024-08-20 returns the 2024-07-01 record</li>
     *   <li>Query date 2023-12-01 returns empty (no record before that date)</li>
     * </ul>
     * </p>
     *
     * @param useruuid User UUID
     * @param asOfDate Date to query (e.g., 2025-01-15)
     * @return Danløn number active on that date, or empty if no record found
     */
    public Optional<String> getDanlonAsOf(String useruuid, LocalDate asOfDate) {
        return Optional.ofNullable(UserDanlonHistory.findDanlonAsOf(useruuid, asOfDate));
    }

    /**
     * Get the current Danløn number for a user (as of today).
     * <p>
     * This is a convenience method that calls {@link #getDanlonAsOf(String, LocalDate)} with today's date.
     * </p>
     * <p>
     * <b>Backward Compatibility Note:</b> This method provides the same functionality as
     * {@code UserAccount.getDanlon()} but reads from the history table instead of the
     * denormalized field. Once V111 is deployed, this becomes the only way to get Danløn numbers.
     * </p>
     *
     * @param useruuid User UUID
     * @return Current Danløn number, or empty if no history exists
     */
    public Optional<String> getCurrentDanlon(String useruuid) {
        return Optional.ofNullable(UserDanlonHistory.findCurrentDanlon(useruuid));
    }

    /**
     * Get the most recent Danløn history record for a user (regardless of active_date).
     * <p>
     * This is different from {@link #getCurrentDanlon(String)} which respects the active_date.
     * Use this when you need the latest record's metadata (created_date, created_by).
     * </p>
     *
     * @param useruuid User UUID
     * @return Most recent history record, or empty if none exists
     */
    public Optional<UserDanlonHistory> getLatestRecord(String useruuid) {
        return Optional.ofNullable(UserDanlonHistory.findLatestRecord(useruuid));
    }

    /**
     * Check if a user has any Danløn history records.
     *
     * @param useruuid User UUID
     * @return true if at least one history record exists
     */
    public boolean hasHistory(String useruuid) {
        return UserDanlonHistory.hasHistory(useruuid);
    }

    /**
     * Check if user has a Danløn history record for a specific month.
     * <p>
     * This is useful for determining if a user should appear in monthly
     * Danløn reports (e.g., new hires, salary type changes, etc.).
     * </p>
     * <p>
     * <b>Usage Example:</b>
     * <pre>
     * {@code
     * LocalDate month = LocalDate.of(2025, 11, 1);
     * if (danlonHistoryService.hasDanlonChangedInMonth(useruuid, month)) {
     *     // User has new Danløn number this month - include in report
     * }
     * }
     * </pre>
     * </p>
     *
     * @param useruuid User UUID
     * @param month    Month to check (will be normalized to 1st of month)
     * @return true if Danløn history record exists for this month
     */
    public boolean hasDanlonChangedInMonth(String useruuid, LocalDate month) {
        LocalDate normalizedMonth = month.withDayOfMonth(1);
        return UserDanlonHistory.count("useruuid = ?1 AND active_date = ?2", useruuid, normalizedMonth) > 0;
    }

    /**
     * Check if user has a Danløn history record for a specific month created by a specific system.
     * <p>
     * This is useful for detecting specific types of Danløn changes, such as:
     * <ul>
     *   <li>"system-salary-type-change" - Auto-generated due to HOURLY → NORMAL transition</li>
     *   <li>"system-migration" - Migrated from old user_ext_account table</li>
     *   <li>Username - Manually created by admin user</li>
     * </ul>
     * </p>
     *
     * @param useruuid  User UUID
     * @param month     Month to check (will be normalized to 1st of month)
     * @param createdBy Creator identifier to match (e.g., "system-salary-type-change")
     * @return true if matching Danløn history record exists
     */
    public boolean hasDanlonChangedInMonthBy(String useruuid, LocalDate month, String createdBy) {
        LocalDate normalizedMonth = month.withDayOfMonth(1);
        return UserDanlonHistory.count(
                "useruuid = ?1 AND active_date = ?2 AND created_by = ?3",
                useruuid, normalizedMonth, createdBy
        ) > 0;
    }

    /**
     * Update the denormalized UserAccount.danlon field for backward compatibility.
     * <p>
     * <b>Temporary Method:</b> This maintains the denormalized field until V111 migration is deployed.
     * Once V111 removes the danlon column from user_ext_account, this method will be removed.
     * </p>
     * <p>
     * <b>Zero-Downtime Strategy:</b> During the transition period, both the history table and
     * denormalized field are kept in sync. This allows gradual migration of code from
     * {@code UserAccount.getDanlon()} to {@code UserDanlonHistoryService.getCurrentDanlon()}.
     * </p>
     *
     * @param useruuid User UUID
     * @param danlon   Danløn number to set (or null to clear)
     */
    @Transactional
    protected void updateDenormalizedField(String useruuid, String danlon) {
        UserAccount userAccount = UserAccount.findById(useruuid);
        // NO-OP: V111 migration removed the danlon column from user_ext_account table
        // This method is preserved for backward compatibility but does nothing
        log.debugf("updateDenormalizedField() called for user %s - no-op after V111 migration", useruuid);
    }

    /**
     * Migrate a user from denormalized field to history table.
     * <p>
     * <b>Use Case:</b> Manual migration for users who were missed by V110 migration script.
     * </p>
     * <p>
     * This method:
     * <ol>
     *   <li>Checks if user already has history (skip if yes)</li>
     *   <li>Reads current danlon from UserAccount.danlon</li>
     *   <li>Uses user's hire date as active_date (or 2020-01-01 fallback)</li>
     *   <li>Creates initial history record</li>
     * </ol>
     * </p>
     *
     * @param useruuid  User UUID
     * @param createdBy Username or system identifier performing migration
     * @return Created history record, or empty if user already has history or no danlon set
     */
    @Transactional
    public Optional<UserDanlonHistory> migrateUserToHistory(String useruuid, String createdBy) {
        // NO-OP: V110 migration already completed the data migration
        // V111 migration removed the danlon column from user_ext_account table
        // This method is preserved for backward compatibility but does nothing
        log.infof("migrateUserToHistory() called for user %s - no-op after V110/V111 migrations completed", useruuid);
        return Optional.empty();
    }

    /**
     * Determine the initial active date for a user during migration.
     * Uses the same logic as V110 migration script:
     * 1. User's hire date (earliest ACTIVE status) normalized to 1st of month
     * 2. Fallback to 2020-01-01 if no hire date found
     *
     * @param useruuid User UUID
     * @return Active date for initial history record
     */
    private LocalDate determineInitialActiveDate(String useruuid) {
        // Query for earliest ACTIVE status date
        // Note: This requires user_status table access
        // TODO: Consider injecting UserStatusService if this becomes more complex
        try {
            LocalDate hireDate = (LocalDate) UserDanlonHistory.getEntityManager()
                    .createNativeQuery("SELECT MIN(statusdate) FROM user_status WHERE useruuid = ?1 AND status = 'ACTIVE'")
                    .setParameter(1, useruuid)
                    .getSingleResult();

            if (hireDate != null) {
                return hireDate.withDayOfMonth(1);
            }
        } catch (Exception e) {
            log.warnf(e, "Failed to query hire date for user %s - using fallback date", useruuid);
        }

        // Fallback to 2020-01-01 for legacy users
        log.infof("No hire date found for user %s - using fallback date 2020-01-01", useruuid);
        return LocalDate.of(2020, 1, 1);
    }

    /**
     * Generate the next available Danløn number.
     * <p>
     * <b>Algorithm:</b>
     * <ol>
     *   <li>Query database for highest numeric Danløn number matching pattern "T[0-9]+"</li>
     *   <li>Extract numeric part and increment by 1</li>
     *   <li>Return new number with "T" prefix (e.g., T237 → T238)</li>
     *   <li>Fallback to T1000 if no existing numbers found</li>
     * </ol>
     * </p>
     * <p>
     * <b>Edge Cases:</b>
     * - Non-numeric Danløn numbers (e.g., "TEMP1", "EXTERN2") are ignored
     * - Empty database returns T1000 as starting point
     * - SQL uses REGEXP for pattern matching and CAST for numeric sorting
     * </p>
     * <p>
     * <b>Thread Safety:</b>
     * This method should be called within a transaction to prevent race conditions
     * when multiple users are assigned Danløn numbers simultaneously.
     * </p>
     *
     * @return Next Danløn number (e.g., "T238")
     */
    public String generateNextDanlonNumber() {
        try {
            // Query highest existing Danløn number matching pattern T[number]
            // Uses REGEXP for filtering and CAST for proper numeric sorting
            String maxDanlon = (String) UserDanlonHistory.getEntityManager()
                    .createNativeQuery(
                            "SELECT danlon FROM user_danlon_history " +
                                    "WHERE danlon REGEXP '^T[0-9]+$' " +
                                    "ORDER BY CAST(SUBSTRING(danlon, 2) AS UNSIGNED) DESC " +
                                    "LIMIT 1"
                    )
                    .getSingleResult();

            if (maxDanlon != null && maxDanlon.startsWith("T")) {
                int currentNumber = Integer.parseInt(maxDanlon.substring(1));
                String nextNumber = "T" + (currentNumber + 1);
                log.infof("Generated next Danløn number: %s (previous max: %s)", nextNumber, maxDanlon);
                return nextNumber;
            }
        } catch (jakarta.persistence.NoResultException e) {
            // No existing Danløn numbers found - use fallback
            log.info("No existing Danløn numbers found - starting from T1000");
        } catch (Exception e) {
            // Unexpected error - log and use fallback
            log.errorf(e, "Error querying max Danløn number - using fallback");
        }

        // Fallback: Start from T1000 for new assignments
        log.info("Using fallback Danløn number: T1000");
        return "T1000";
    }
}
