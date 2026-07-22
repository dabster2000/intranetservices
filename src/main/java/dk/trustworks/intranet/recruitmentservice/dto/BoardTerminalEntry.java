package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentApplicationTerminal;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;

import java.time.LocalDateTime;

/**
 * One closed application in the board's terminal rail (P7 contract
 * {@code IBoardTerminalEntry}). {@code closedAt} is the timestamp of the
 * terminal move — {@code updated_at} of the application row, because
 * terminals never touch {@code stage_entered_at}. {@code rejectionReasonCode}
 * is present for {@code REJECTED} outcomes only (mandatory there, spec
 * §4.2 invariant 4).
 */
public record BoardTerminalEntry(
        String applicationUuid,
        String candidateUuid,
        String candidateName,
        RecruitmentApplicationTerminal outcome,
        RecruitmentRejectionReason rejectionReasonCode,
        LocalDateTime closedAt
) {
}
