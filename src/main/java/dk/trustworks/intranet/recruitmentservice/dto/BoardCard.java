package dk.trustworks.intranet.recruitmentservice.dto;

import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One open application rendered as a kanban card (P7 contract
 * {@code IBoardCard}). Idle detection is server-side so every client
 * agrees on the threshold:
 * <ul>
 *   <li>{@code daysInStage} = floor(now − {@code stageEnteredAt}) in
 *       whole days, UTC, clamped at 0;</li>
 *   <li>{@code idle} = {@code daysInStage >} the configured
 *       {@code recruitment.sla.candidate-idle-days} (default 7). The same
 *       number the SLA sweep and the landing page use — the board flags
 *       the wait, they claim someone must act on it.</li>
 * </ul>
 * {@code referredByName} is the referring employee's display name
 * ("First Last"), resolved from {@code referred_by_user_uuid} for
 * {@code REFERRAL}/{@code PARTNER_REFERRAL} candidates only — batched
 * per board, never per card.
 * <p>
 * {@code subStatus} is the server-derived position inside the stage
 * ({@link BoardCardSubStatus}); {@code null} for stages with no ladder
 * (SCREENING, HIRED) — old clients ignore the field, and the frontend
 * renders nothing when it is null.
 */
public record BoardCard(
        String applicationUuid,
        String candidateUuid,
        String candidateName,
        CandidateSource source,
        String referredByName,
        LocalDateTime stageEnteredAt,
        long daysInStage,
        boolean idle,
        LocalDate expectedStartDate,
        String assignedTeamUuid,
        BoardCardSubStatus subStatus
) {
}
