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
 *   <li>{@code idle} = {@code daysInStage > 7}.</li>
 * </ul>
 * {@code referredByName} is the referring employee's display name
 * ("First Last"), resolved from {@code referred_by_user_uuid} for
 * {@code REFERRAL}/{@code PARTNER_REFERRAL} candidates only — batched
 * per board, never per card.
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
        String assignedTeamUuid
) {
}
