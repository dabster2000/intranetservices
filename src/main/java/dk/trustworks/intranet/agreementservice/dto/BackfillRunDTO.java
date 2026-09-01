package dk.trustworks.intranet.agreementservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** One backfill corpus walk for the console (template-clauses spec §10). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BackfillRunDTO {
    private String uuid;
    private String status;
    private boolean dryRun;
    private String startedBy;
    private String startedByName;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private String corpusSummary;
    private int employeesTotal;
    private int foldersTotal;
    private int foldersWalked;
    private int filesSeen;
    private int filesSkipped;
    private int documentsNew;
    private int proposalsCreated;
    private int errorsCount;
    private String errorMessage;
}
