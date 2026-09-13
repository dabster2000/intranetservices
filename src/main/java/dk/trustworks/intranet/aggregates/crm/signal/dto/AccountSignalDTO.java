package dk.trustworks.intranet.aggregates.crm.signal.dto;

import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;

import java.time.LocalDateTime;
import java.util.List;

/**
 * What {@code POST /account-signals} returns: the saved rows, so the capture panel can
 * confirm what was filed and on which accounts.
 *
 * <p>Carries no client or author NAME — resolving those is the reader's job, and the
 * reader of this response is the person who just typed the line and already knows both.
 * It also never widens what the caller sent: the extracted fields come back exactly as
 * stored.
 *
 * <p>One of these per account (V593). A capture naming two accounts answers with two,
 * sharing {@link #captureUuid}.
 */
public record AccountSignalDTO(
        String uuid,
        String captureUuid,
        String clientUuid,
        String authorUuid,
        String source,
        String text,
        String personName,
        String personRole,
        String relationText,
        String signalType,
        String status,
        List<String> colleagueUuids,
        LocalDateTime createdAt) {

    public static AccountSignalDTO from(AccountSignal row, List<String> colleagueUuids) {
        return new AccountSignalDTO(
                row.getUuid(),
                row.getCaptureUuid(),
                row.getClientUuid(),
                row.getAuthorUuid(),
                row.getSource() == null ? null : row.getSource().name(),
                row.getText(),
                row.getPersonName(),
                row.getPersonRole(),
                row.getRelationText(),
                row.getSignalType() == null ? null : row.getSignalType().name(),
                row.getStatus() == null ? null : row.getStatus().name(),
                colleagueUuids == null ? List.of() : List.copyOf(colleagueUuids),
                row.getCreatedAt());
    }
}
