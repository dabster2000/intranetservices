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
 */
@Getter
@Setter
@Entity
@Table(name = "consultant_profiles")
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false, onlyExplicitlyIncluded = true)
public class ConsultantProfile extends PanacheEntityBase {

    private static final long STALENESS_DAYS = 7;

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
    }
}
