package dk.trustworks.intranet.questionnaireservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class CreateQuestionRequest {
    private String questionText;
    private String questionType;
    private int sortOrder;
    private String configJson;
}
