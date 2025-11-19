package dk.trustworks.intranet.domain.user.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Stores historical Danløn employee numbers with effective dates.
 * <p>
 * This entity follows the temporal data pattern used by {@link UserBankInfo}.
 * Each record represents a Danløn number that was/is valid starting from the active_date.
 * </p>
 * <p>
 * Key features:
 * <ul>
 *   <li>active_date is normalized to the 1st of the month (e.g., 2025-01-01)</li>
 *   <li>Multiple historical records per user are supported</li>
 *   <li>Unique constraint on (useruuid, active_date) prevents duplicates</li>
 *   <li>Audit trail via created_date and created_by fields</li>
 * </ul>
 * </p>
 * <p>
 * Common query patterns:
 * <ul>
 *   <li>Get current Danløn number: {@code findCurrentDanlon(useruuid, LocalDate.now())}</li>
 *   <li>Get historical Danløn number: {@code findDanlonAsOf(useruuid, specificDate)}</li>
 *   <li>Get full history: {@code findByUseruuid(useruuid)}</li>
 * </ul>
 * </p>
 *
 * @see UserBankInfo Similar temporal pattern for bank account information
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "user_danlon_history")
public class UserDanlonHistory extends PanacheEntityBase {

    @Id
    @Size(max = 36)
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    @NotNull
    @Size(max = 36)
    @Column(name = "useruuid", nullable = false, length = 36)
    private String useruuid;

    /**
     * First day of month when this Danløn number became active.
     * Always normalized to the 1st of the month (e.g., 2025-01-01, not 2025-01-15).
     */
    @NotNull
    @Column(name = "active_date", nullable = false)
    private LocalDate activeDate;

    /**
     * Danløn employee number.
     * This is the payroll system identifier used in CSV exports.
     */
    @NotNull
    @Size(max = 36)
    @Column(name = "danlon", nullable = false, length = 36)
    private String danlon;

    /**
     * Audit timestamp: when this record was created in the system.
     * This is NOT the effective date - see {@link #activeDate} for that.
     */
    @NotNull
    @Column(name = "created_date", nullable = false)
    private LocalDateTime createdDate;

    /**
     * Audit: username or system identifier who created this record.
     * Examples: "admin@trustworks.dk", "system-migration", "system"
     */
    @Size(max = 255)
    @Column(name = "created_by", length = 255)
    private String createdBy;

    /**
     * Constructor for creating new history records.
     * Automatically generates UUID and sets created_date to now.
     *
     * @param useruuid   User UUID this Danløn number belongs to
     * @param activeDate First day of month when this Danløn number became active (must be 1st of month)
     * @param danlon     Danløn employee number
     * @param createdBy  Username or system identifier creating this record
     */
    public UserDanlonHistory(String useruuid, LocalDate activeDate, String danlon, String createdBy) {
        this.uuid = UUID.randomUUID().toString();
        this.useruuid = useruuid;
        this.activeDate = activeDate;
        this.danlon = danlon;
        this.createdDate = LocalDateTime.now();
        this.createdBy = createdBy;
    }

    /**
     * Find all Danløn history records for a user, ordered by active_date descending (newest first).
     *
     * @param useruuid User UUID
     * @return List of history records, newest first
     */
    public static List<UserDanlonHistory> findByUseruuid(String useruuid) {
        return UserDanlonHistory.find("useruuid = ?1 ORDER BY activeDate DESC", useruuid).list();
    }

    /**
     * Find the Danløn number that was active on a specific date.
     * Returns the most recent record where active_date <= asOfDate.
     *
     * @param useruuid User UUID
     * @param asOfDate Date to query (e.g., 2025-01-15 will find the Danløn active on that date)
     * @return Danløn number active on that date, or null if no record found
     */
    public static String findDanlonAsOf(String useruuid, LocalDate asOfDate) {
        return UserDanlonHistory.find(
            "useruuid = ?1 AND activeDate <= ?2 ORDER BY activeDate DESC",
            useruuid,
            asOfDate
        ).firstResultOptional()
         .map(entity -> ((UserDanlonHistory) entity).getDanlon())
         .orElse(null);
    }

    /**
     * Find the current Danløn number for a user (as of today).
     * Convenience method that calls {@link #findDanlonAsOf(String, LocalDate)} with today's date.
     *
     * @param useruuid User UUID
     * @return Current Danløn number, or null if no record found
     */
    public static String findCurrentDanlon(String useruuid) {
        return findDanlonAsOf(useruuid, LocalDate.now());
    }

    /**
     * Check if a user has any Danløn history records.
     *
     * @param useruuid User UUID
     * @return true if at least one history record exists
     */
    public static boolean hasHistory(String useruuid) {
        return UserDanlonHistory.count("useruuid", useruuid) > 0;
    }

    /**
     * Find the most recent Danløn history record for a user (regardless of active_date).
     * This is different from {@link #findCurrentDanlon(String)} which respects the active_date.
     *
     * @param useruuid User UUID
     * @return Most recent history record, or null if none exists
     */
    public static UserDanlonHistory findLatestRecord(String useruuid) {
        return UserDanlonHistory.find(
            "useruuid = ?1 ORDER BY activeDate DESC",
            useruuid
        ).firstResultOptional()
         .map(entity -> (UserDanlonHistory) entity)
         .orElse(null);
    }
}
