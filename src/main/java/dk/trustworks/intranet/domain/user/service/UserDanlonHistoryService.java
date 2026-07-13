package dk.trustworks.intranet.domain.user.service;

import dk.trustworks.intranet.domain.user.entity.UserDanlonHistory;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

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
 */
@JBossLog
@ApplicationScoped
public class UserDanlonHistoryService {

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
     * Historical delayed-pay lookup for a frozen earning company. Closed rows remain eligible because
     * termination after earning must not erase the Danløn identity that covered the earning period.
     * A legacy null-company row is accepted only when it is the sole candidate.
     */
    public Optional<UserDanlonHistory> getHistoricalDelayedPayAssignment(String useruuid,
                                                                         String earningCompanyUuid,
                                                                         LocalDate earningMonthEnd) {
        if (useruuid == null || earningCompanyUuid == null || earningMonthEnd == null) return Optional.empty();
        List<UserDanlonHistory> candidates = UserDanlonHistory.<UserDanlonHistory>find(
                "useruuid = ?1 and activeDate <= ?2 and (companyUuid = ?3 or companyUuid is null) "
                        + "order by activeDate desc, createdDate desc",
                useruuid, earningMonthEnd, earningCompanyUuid).list();
        List<UserDanlonHistory> companyMatches = candidates.stream()
                .filter(row -> earningCompanyUuid.equals(row.getCompanyUuid())).toList();
        if (!companyMatches.isEmpty()) {
            UserDanlonHistory first = companyMatches.get(0);
            boolean ambiguousTop = companyMatches.stream()
                    .filter(row -> row.getActiveDate().equals(first.getActiveDate())
                            && Objects.equals(row.getCreatedDate(), first.getCreatedDate()))
                    .map(UserDanlonHistory::getDanlon).distinct().count() > 1;
            return ambiguousTop ? Optional.empty() : Optional.of(first);
        }
        List<UserDanlonHistory> legacy = candidates.stream()
                .filter(row -> row.getCompanyUuid() == null).toList();
        return legacy.size() == 1 ? Optional.of(legacy.get(0)) : Optional.empty();
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
        return UserDanlonHistory.count("useruuid = ?1 AND activeDate = ?2", useruuid, normalizedMonth) > 0;
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
                "useruuid = ?1 AND activeDate = ?2 AND createdBy = ?3",
                useruuid, normalizedMonth, createdBy
        ) > 0;
    }

}
