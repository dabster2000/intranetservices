package dk.trustworks.intranet.questionnaireservice.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
@Entity
@Table(name = "questionnaire_answer")
public class QuestionnaireAnswer extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_uuid")
    @JsonIgnore
    private QuestionnaireSubmission submission;

    @Column(name = "submission_uuid", insertable = false, updatable = false)
    private String submissionUuid;

    @Column(name = "question_uuid")
    private String questionUuid;

    @Column(name = "answer_text", columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "answer_json", columnDefinition = "TEXT")
    private String answerJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
