package dk.trustworks.intranet.questionnaireservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
public class CreateSubmissionRequest {
    private String clientUuid;
    private List<SubmissionAnswerRequest> answers;
}
