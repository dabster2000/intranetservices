package dk.trustworks.intranet.questionnaireservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
public class CreateQuestionnaireRequest {
    private String title;
    private String description;
    private LocalDate startDate;
    private LocalDate deadline;
    private boolean reminderEnabled;
    private int reminderCooldownDays = 3;
    private List<String> targetPractices;
    private List<String> targetTeams;
    private List<CreateQuestionRequest> questions;
}
