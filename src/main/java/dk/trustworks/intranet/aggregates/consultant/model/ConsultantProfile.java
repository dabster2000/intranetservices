package dk.trustworks.intranet.aggregates.consultant.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Aggregate root for cached AI-generated consultant sales profiles.
 *
 * <p>A profile is considered stale when:
 * <ul>
 *   <li>It has never been generated ({@code generatedAt} is null)</li>
 *   <li>It was generated more than 7 days ago</li>
 *   <li>The CV data has changed since generation ({@code cvUpdatedAt} differs)</li>
 * </ul>
 *
 * <p>Staleness alone is not enough to decide whether to call OpenAI again. A failed generation
 * deliberately leaves {@code generatedAt} NULL — a broken profile must never be served as fresh —
 * which makes {@link #isStale(LocalDateTime)} permanently true. {@link #shouldAttempt(int)} is
 * the second gate that turns that into a bounded retry instead of a request-rate model storm.
 */
@Getter
@Setter
@Entity
@Table(name = "consultant_profiles")
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class ConsultantProfile extends PanacheEntityBase {

    private static final long STALENESS_DAYS = 7;

    /** A usable pitch is cached. */
    public static final String STATUS_READY = "READY";
    /** Not generated yet, or a retry is due. */
    public static final String STATUS_PENDING = "PENDING";
    /** No CV row, or parked after max-attempts consecutive failures. */
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "useruuid", nullable = false, length = 36)
    private String useruuid;

    @Column(name = "pitch_text", columnDefinition = "TEXT")
    private String pitchText;

    @Column(name = "industries_json", columnDefinition = "JSON")
    private String industriesJson;

    @Column(name = "top_skills_json", columnDefinition = "JSON")
    private String topSkillsJson;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "cv_updated_at")
    private LocalDateTime cvUpdatedAt;

    /**
     * {@code PENDING} | {@code READY} | {@code UNAVAILABLE} (V461).
     *
     * <p>Initialised here, not left to the column DEFAULT: a column default does not apply to an
     * explicit NULL, so persisting a fresh entity through Panache would emit
     * {@code INSERT … status = NULL} and MariaDB in strict mode would raise error 1048 against the
     * {@code NOT NULL} column. Every production write currently goes through the native upsert in
     * {@code ConsultantProfileGenerationService}, so this is a guard for the next caller.
     */
    @Column(name = "status", nullable = false, length = 16)
    private String status = STATUS_PENDING;

    /** Consecutive failed generation attempts; reset to 0 on success. */
    @Column(name = "generation_attempts", nullable = false)
    private int generationAttempts;

    /** Last attempt (success OR failure) — drives the retry backoff and the pre-warm claim. */
    @Column(name = "last_attempt_at")
    private LocalDateTime lastAttemptAt;

    /** Short diagnostic failure code. Never rendered to users. */
    @Column(name = "last_error", length = 255)
    private String lastError;

    public ConsultantProfile(String useruuid) {
        this.useruuid = Objects.requireNonNull(useruuid, "useruuid must not be null");
    }

    /**
     * Determines whether this profile needs regeneration.
     *
     * @param currentCvUpdatedAt the current CV last-updated timestamp (may be null)
     * @return true if the profile should be regenerated
     */
    public boolean isStale(LocalDateTime currentCvUpdatedAt) {
        if (generatedAt == null) {
            return true;
        }
        if (ChronoUnit.DAYS.between(generatedAt, LocalDateTime.now()) >= STALENESS_DAYS) {
            return true;
        }
        if (currentCvUpdatedAt != null && !currentCvUpdatedAt.equals(this.cvUpdatedAt)) {
            return true;
        }
        return false;
    }

    /**
     * Updates this profile with freshly generated AI data.
     *
     * @param pitch        the AI-generated sales pitch
     * @param industriesJson JSON array string of industries
     * @param topSkillsJson  JSON array string of top skills
     * @param cvUpdatedAt  the CV's last-updated timestamp used for this generation
     */
    public void updateFrom(String pitch, String industriesJson, String topSkillsJson, LocalDateTime cvUpdatedAt) {
        this.pitchText = pitch;
        this.industriesJson = industriesJson;
        this.topSkillsJson = topSkillsJson;
        this.cvUpdatedAt = cvUpdatedAt;
        this.generatedAt = LocalDateTime.now();
        this.status = STATUS_READY;
        this.generationAttempts = 0;
        this.lastError = null;
    }

    /**
     * True when a (re)generation attempt is allowed right now: never attempted, or the backoff
     * has elapsed — and the profile is not parked as {@link #STATUS_UNAVAILABLE}.
     *
     * <p>Required because a failed generation deliberately leaves {@code generatedAt} NULL, which
     * makes {@link #isStale(LocalDateTime)} permanently true. Without this gate every dashboard
     * load would re-fire OpenAI for a consultant that cannot succeed.
     *
     * @param backoffMinutes minimum wait before a new attempt (see
     *                       {@code consultant-profile.generation.retry-backoff-minutes})
     */
    public boolean shouldAttempt(int backoffMinutes) {
        if (STATUS_UNAVAILABLE.equals(status)) {
            return false;
        }
        if (lastAttemptAt == null) {
            return true;
        }
        return lastAttemptAt.isBefore(LocalDateTime.now().minusMinutes(backoffMinutes));
    }
}
