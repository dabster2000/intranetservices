package dk.trustworks.intranet.aggregates.crm.note.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.note.model.ClientNote;
import dk.trustworks.intranet.domain.user.entity.User;

import java.time.LocalDate;

/**
 * A note as an API caller reads one: what was written, by whom, and when.
 *
 * <p><b>It mirrors no TypeScript interface, and that is not an oversight.</b> Every other
 * view DTO in this package names the interface it matches, because the frontend renders it
 * directly. The account page never renders this one: it POSTs a note and then re-reads the
 * timeline, where the note arrives as an {@code AccountActivityDTO} with source
 * {@code NOTE} like every other row. So this record is the create echo and the body of
 * {@code GET /client-notes?clientUuid=}, and the only frontend type it has to agree with is
 * {@code IAccountActivity}, which it does not overlap.
 *
 * <p>The author is a {@link PersonDTO}, never a {@code User}: serialising a whole user
 * ships salaries and bank details the moment the BFF's {@code admin:*} makes
 * {@code UserScopeResponseFilter} inert (see PersonDTO.java:9-13). There is no
 * {@code canDelete} flag either — who may remove a note is decided in the service, and a
 * caller that wants to offer the affordance compares {@code author.uuid()} against the
 * session user.
 */
public record ClientNoteDTO(
        String uuid,
        String clientUuid,
        String text,
        PersonDTO author,
        LocalDate createdAt) {

    public static ClientNoteDTO from(ClientNote row) {
        return new ClientNoteDTO(
                row.getUuid(),
                row.getClientUuid(),
                row.getText(),
                PersonDTO.from(User.findById(row.getAuthorUuid())),
                row.getCreatedAt() == null ? null : row.getCreatedAt().toLocalDate());
    }
}
