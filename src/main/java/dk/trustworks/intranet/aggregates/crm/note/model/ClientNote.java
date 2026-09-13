package dk.trustworks.intranet.aggregates.crm.note.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One line somebody typed on an account (CRM spec §4.3) — the Timeline tab's manual note.
 *
 * <p><b>The one thing in the feed nobody's system saw.</b> Every other row the account
 * timeline unions is derived from something that already happened somewhere — a contract
 * changed, a lead moved stage, a meeting appeared in a calendar. A note exists for what no
 * system recorded. That is why it is the only writable source in that feed, and why
 * {@code AccountActivityService}'s class javadoc had to stop saying nothing in it is typed
 * by hand.
 *
 * <p><b>A note is not a signal.</b> A signal is something a colleague heard about a named
 * third party and that an account owner then decides on; a note is a line on a timeline
 * that nothing downstream consumes. Both rows are written once and never rewritten, for
 * opposite reasons: a signal because a decision hangs off it, a note because a rewritten
 * line would leave a dated timeline asserting that what stands there now is what was
 * written then. A note can be removed; it cannot be edited, which is why this class
 * carries a creation stamp and no {@code modifiedAt}.
 *
 * <p><b>Never a REST body.</b> The resource takes
 * {@code dk.trustworks.intranet.aggregates.crm.note.dto.ClientNoteRequest}.
 * {@link #authorUuid} and {@link #createdAt} are derived server-side and must never be
 * accepted from a caller.
 *
 * <p><b>Third-party personal data.</b> The free-text {@link #text} may name a real person
 * at a client organisation who never gave us anything, exactly like {@code AccountSignal}
 * (see AccountSignal.java:35-45). Purpose is commercial relationship management. The agreed
 * retention is 24 months after the account's last activity (spec §3.4) — but the purge job
 * is NOT built, so nothing enforces it yet. Do not read this as an implemented control.
 * This is the third table waiting on that job and the hardest of the three for it to serve:
 * a signal's name sits in a structured {@code person_name} column that one UPDATE can null,
 * and a meeting attendee's in {@code display_name}, while a note's name is inside free text
 * with no structure at all — the only erasure primitive that works here is deleting the
 * row. The table is excluded from {@code sp_sync_prod_to_staging} (V590) so these lines are
 * never copied into an environment with wider access, and the row is refused outright while
 * an admin is impersonating somebody.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_note")
public class ClientNote extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    /** The account the note is on. Required — a note with no account has nowhere to land. */
    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /**
     * The line, as typed. Bounded by {@code ClientNoteService.MAX_NOTE_CHARS}, which is the
     * privacy control rather than advice; the column is wider than that cap so the number
     * can move without an ALTER.
     */
    @Column(name = "note_text", length = 500, nullable = false)
    private String text;

    /** Who wrote it. Resolved from {@code X-Requested-By}, never from the body. */
    @Column(name = "author_uuid", length = 36, nullable = false)
    private String authorUuid;

    /** When it was written. This is the feed date, and a note has no other date. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
