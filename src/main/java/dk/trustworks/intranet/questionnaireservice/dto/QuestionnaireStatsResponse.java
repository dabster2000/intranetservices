package dk.trustworks.intranet.questionnaireservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QuestionnaireStatsResponse {
    private long totalSubmissions;
    private long uniqueClientsSubmitted;
    private long totalActiveClients;
    private int coveragePercent;
    private long daysRemaining;
}
