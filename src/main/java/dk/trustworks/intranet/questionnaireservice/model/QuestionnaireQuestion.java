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
@Table(name = "questionnaire_question")
public class QuestionnaireQuestion extends PanacheEntityBase {

    @Id
    @EqualsAndHashCode.Include
    private String uuid;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "questionnaire_uuid")
    @JsonIgnore
    private Questionnaire questionnaire;

    @Column(name = "questionnaire_uuid", insertable = false, updatable = false)
    private String questionnaireUuid;

    @Column(name = "question_text", columnDefinition = "TEXT")
    private String questionText;

    @Enumerated(EnumType.STRING)
    @Column(name = "question_type")
    private QuestionType questionType;

    @Column(name = "sort_order")
    private int sortOrder;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum QuestionType {
        TEXT, CHECKBOX_TEXT
    }
}
