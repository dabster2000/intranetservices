package dk.trustworks.intranet.questionnaireservice.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "questionnaire_submission")
public class QuestionnaireSubmission extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @Column(name = "questionnaire_uuid")
    private String questionnaireUuid;

    @Column(name = "client_uuid")
    private String clientUuid;

    @Column(name = "user_uuid")
    private String userUuid;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @OneToMany(mappedBy = "submission", fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<QuestionnaireAnswer> answers;

    public static List<QuestionnaireSubmission> findByQuestionnaire(String questionnaireUuid) {
        return list("questionnaireUuid", questionnaireUuid);
    }

    public static List<QuestionnaireSubmission> findByQuestionnaireAndUser(String questionnaireUuid, String userUuid) {
        return list("questionnaireUuid = ?1 and userUuid = ?2", questionnaireUuid, userUuid);
    }

    public static QuestionnaireSubmission findExisting(String questionnaireUuid, String clientUuid, String userUuid) {
        return find("questionnaireUuid = ?1 and clientUuid = ?2 and userUuid = ?3",
                questionnaireUuid, clientUuid, userUuid).firstResult();
    }
}
