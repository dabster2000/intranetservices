package dk.trustworks.intranet.questionnaireservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "questionnaire")
public class Questionnaire extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    private QuestionnaireStatus status;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "reminder_enabled")
    private boolean reminderEnabled;

    @Column(name = "reminder_cooldown_days")
    private int reminderCooldownDays;

    @Column(name = "target_practices", columnDefinition = "TEXT")
    private String targetPractices;

    /**
     * Uuid twin of {@link #targetPractices} (V425, Part 2 Phase 1): a JSON array
     * of practice registry uuids in the same element order. The targeting reader
     * matches this column since Phase 3 (§4.3); the code array stays populated
     * for compatibility until Phase 5 drops it. Both arrays are written together
     * by {@code QuestionnaireService.createQuestionnaire}.
     */
    @Column(name = "target_practice_uuids", columnDefinition = "TEXT")
    private String targetPracticeUuids;

    @Column(name = "target_teams", columnDefinition = "TEXT")
    private String targetTeams;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "questionnaire", fetch = FetchType.EAGER)
    @OrderBy("sortOrder ASC")
    private List<QuestionnaireQuestion> questions;

    public enum QuestionnaireStatus {
        ACTIVE, CLOSED
    }

    public static Questionnaire findByUuid(String uuid) {
        return findById(uuid);
    }

    public static List<Questionnaire> findAllActive() {
        return list("status", QuestionnaireStatus.ACTIVE);
    }

    public boolean isOpen() {
        LocalDate today = LocalDate.now();
        return status == QuestionnaireStatus.ACTIVE
                && (startDate == null || !today.isBefore(startDate))
                && (deadline == null || !today.isAfter(deadline));
    }
}
