package dk.trustworks.intranet.aggregates.crm.note.services;

import dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteDTO;
import dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteRequest;
import dk.trustworks.intranet.aggregates.crm.note.model.ClientNote;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Notes on an account (CRM spec §4.3).
 *
 * <p><b>The only writable source in the activity feed.</b> Every other row the timeline
 * shows is derived; this one is typed, which is the whole point of it — it is where a
 * colleague records what no system saw. The line goes into the feed verbatim, unlike a
 * signal's quote, and {@code AccountActivityService.noteRows} carries the reasoning for
 * that.
 *
 * <p><b>Write once, remove or keep.</b> There is no update path: a rewritten line would
 * leave a dated timeline asserting that what stands there now is what was written then. The
 * realistic failure — a line that named somebody it should not have — is answered by
 * removing the row, which is also the only erasure primitive that works on free text.
 *
 * <p><b>Only the author removes a line, or management.</b> Deliberately not the account
 * manager, unlike {@code AccountSignalService.isOwnerOf}, which checks
 * {@code client.accountmanager} because a signal's <em>decision</em> belongs to the
 * account. A note's words belong to whoever typed them; the account owner did not write it
 * and must not be able to erase it off their own page. The management override exists for
 * the case the author cannot act themselves — they have left the firm, and somebody still
 * has to be able to take a name down.
 *
 * <p>Validation is hand-rolled: bean validation is not active in this build, so every
 * {@code @NotBlank} in this codebase is inert decoration and checks that matter are plain
 * Java throwing 400.
 */
@JBossLog
@ApplicationScoped
public class ClientNoteService {

    /**
     * One line. The cap is the privacy control rather than advice — nobody pastes a meeting
     * transcript naming eight people into 280 characters. The column is {@code VARCHAR(500)}
     * (V590) so this number can move without an ALTER; the frontend's own
     * {@code MAX_NOTE_CHARS} must never exceed it.
     */
    public static final int MAX_NOTE_CHARS = 280;

    @Inject
    ClientService clientService;

    /** Every note on one account, newest first. */
    public List<ClientNoteDTO> forClient(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return List.of();
        }
        return ClientNote.<ClientNote>list("clientUuid = ?1 order by createdAt desc", clientUuid.trim())
                .stream().map(ClientNoteDTO::from).toList();
    }

    @Transactional
    public ClientNoteDTO create(ClientNoteRequest request, String authorUuid) {
        requireActor(authorUuid);
        if (request == null) {
            throw new WebApplicationException("A body is required", Response.Status.BAD_REQUEST);
        }
        if (clientService.findByUuid(nonBlank(request.clientUuid(), "clientUuid")) == null) {
            throw new WebApplicationException("Unknown client", Response.Status.BAD_REQUEST);
        }

        ClientNote note = new ClientNote();
        note.setUuid(UUID.randomUUID().toString());
        note.setClientUuid(request.clientUuid().trim());
        note.setText(requireNoteText(request.text()));
        note.setAuthorUuid(authorUuid);
        note.setCreatedAt(LocalDateTime.now());
        note.persist();

        // chars, never the line itself: this text may name a third party and CloudWatch log
        // groups in this account never expire. Same reason AccountSignalService logs
        // hasPerson=%s rather than the name.
        log.infof("Client note created: uuid=%s client=%s actor=%s chars=%d",
                note.getUuid(), note.getClientUuid(), authorUuid, note.getText().length());
        return ClientNoteDTO.from(note);
    }

    @Transactional
    public void delete(String noteUuid, String actorUuid, boolean management) {
        requireActor(actorUuid);
        ClientNote note = require(noteUuid);
        requireAuthorOrManagement(note.getAuthorUuid(), actorUuid, management);
        log.infof("Client note deleted: uuid=%s client=%s actor=%s management=%s",
                note.getUuid(), note.getClientUuid(), actorUuid, management);
        note.delete();
    }

    /**
     * The author's own words, or management's reach.
     *
     * <p>Package-private and static so the rule can be exercised without a container: it is
     * the one authorization decision in this domain, the fast tier is what gates a deploy,
     * and nothing in the UI exercises the delete path today.
     */
    static void requireAuthorOrManagement(String authorUuid, String actorUuid, boolean management) {
        if (management) {
            return;
        }
        if (authorUuid == null || !authorUuid.equals(actorUuid)) {
            throw new WebApplicationException(
                    "A note is somebody's own words — only its author can remove it",
                    Response.Status.FORBIDDEN);
        }
    }

    /**
     * The line, or 400.
     *
     * <p>Over-length is REFUSED rather than truncated, copying
     * {@code AccountSignalService.requireText} rather than {@code BidService.trimTo}: the
     * author is looking at the box, and silently publishing half their sentence on the
     * most-read surface of the account page is worse than a 400 that names the cap.
     */
    static String requireNoteText(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            throw new WebApplicationException("Nothing was typed", Response.Status.BAD_REQUEST);
        }
        if (text.length() > MAX_NOTE_CHARS) {
            throw new WebApplicationException(
                    "A note is one line — keep it under " + MAX_NOTE_CHARS + " characters",
                    Response.Status.BAD_REQUEST);
        }
        return text;
    }

    private ClientNote require(String noteUuid) {
        if (noteUuid == null || noteUuid.isBlank()) {
            throw new WebApplicationException("A note uuid is required", Response.Status.BAD_REQUEST);
        }
        ClientNote note = ClientNote.findById(noteUuid.trim());
        if (note == null) {
            throw new WebApplicationException("Unknown note", Response.Status.NOT_FOUND);
        }
        return note;
    }

    private static void requireActor(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a note says who wrote it",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static String nonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new WebApplicationException(field + " is required", Response.Status.BAD_REQUEST);
        }
        return value.trim();
    }
}
