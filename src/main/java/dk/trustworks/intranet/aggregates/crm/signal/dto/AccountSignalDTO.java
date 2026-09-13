package dk.trustworks.intranet.aggregates.crm.signal.dto;

import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;

import java.time.LocalDateTime;

/**
 * What {@code POST /account-signals} returns: the saved row, so the capture panel can
 * confirm what was filed and link to it once the read surfaces exist.
 *
 * <p>Carries no client or author NAME — resolving those is the reader's job and this cut
 * has no readers. It also never widens what the caller sent: the extracted fields come
 * back exactly as stored.
 */
public record AccountSignalDTO(
        String uuid,
        String clientUuid,
        String authorUuid,
        String source,
        String text,
        String personName,
        String personRole,
        String relationText,
        String signalType,
        String status,
        LocalDateTime createdAt) {

    public static AccountSignalDTO from(AccountSignal row) {
        return new AccountSignalDTO(
                row.getUuid(),
                row.getClientUuid(),
                row.getAuthorUuid(),
                row.getSource() == null ? null : row.getSource().name(),
                row.getText(),
                row.getPersonName(),
                row.getPersonRole(),
                row.getRelationText(),
                row.getSignalType() == null ? null : row.getSignalType().name(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.getCreatedAt());
    }
}
