package dk.trustworks.intranet.aggregates.crm.signal.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.signal.model.AccountSignal;
import dk.trustworks.intranet.domain.user.entity.User;

import java.time.LocalDate;
import java.util.List;

/**
 * A signal as the account page reads it — the capture DTO plus the names behind the uuids
 * and whatever the owner decided.
 *
 * <p>Distinct from {@link AccountSignalDTO}, which is what the capture endpoint echoes back
 * and deliberately carries no names: that response goes to the person who just typed the
 * line and has no reader to resolve for. This one has readers.
 *
 * <p>{@link #colleagues} are the Trustworks people the line named besides the author
 * (V593) — what turns <i>"jeg har mødt i KOMBIT sammen Tobias Kjølsen"</i> from a
 * sentence into two people who know Dorte. {@link #captureUuid} lets a reader say "also
 * filed on Rigspolitiet" by grouping rows that share it.
 *
 * <p>Mirrors {@code IPlanSignal} in {@code src/lib/crm/accountPlanTypes.ts}.
 */
public record AccountSignalViewDTO(
        String uuid,
        String captureUuid,
        String clientUuid,
        String text,
        String personName,
        String personRole,
        String relationText,
        String signalType,
        String status,
        String source,
        PersonDTO author,
        List<PersonDTO> colleagues,
        LocalDate createdAt,
        PersonDTO decidedBy,
        LocalDate decidedAt,
        String leadRef) {

    public static AccountSignalViewDTO from(AccountSignal row, List<PersonDTO> colleagues) {
        return new AccountSignalViewDTO(
                row.getUuid(),
                row.getCaptureUuid(),
                row.getClientUuid(),
                row.getText(),
                row.getPersonName(),
                row.getPersonRole(),
                row.getRelationText(),
                row.getSignalType() == null ? null : row.getSignalType().name(),
                row.getStatus() == null ? null : row.getStatus().name(),
                row.getSource() == null ? null : row.getSource().name(),
                PersonDTO.from(User.findById(row.getAuthorUuid())),
                colleagues == null ? List.of() : List.copyOf(colleagues),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toLocalDate(),
                row.getDecidedBy() == null ? null : PersonDTO.from(User.findById(row.getDecidedBy())),
                row.getDecidedAt() == null ? null : row.getDecidedAt().toLocalDate(),
                row.getLeadUuid());
    }
}
