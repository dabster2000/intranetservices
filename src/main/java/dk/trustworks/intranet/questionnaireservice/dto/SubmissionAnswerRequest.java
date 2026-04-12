package dk.trustworks.intranet.questionnaireservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class SubmissionAnswerRequest {
    private String questionUuid;
    private String answerText;
    private String answerJson;
}
