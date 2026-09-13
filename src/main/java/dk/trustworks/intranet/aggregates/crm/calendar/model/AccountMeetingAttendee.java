package dk.trustworks.intranet.aggregates.crm.calendar.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One person at a client organisation who was in a meeting (CRM spec §3.7).
 *
 * <p><b>Third-party personal data.</b> These people never gave Trustworks anything; their
 * name and work e-mail arrive because they were invited to a meeting with us. Purpose is
 * commercial relationship management. The table is excluded from
 * {@code sp_sync_prod_to_staging} so the names are never copied into an environment with
 * wider access, and the agreed retention is 24 months after the account's last activity —
 * but the purge job is NOT built, so nothing enforces that yet. Do not read this as an
 * implemented control.
 *
 * <p>Only EXTERNAL attendees are stored, and only ones whose domain matches a
 * {@code client_domain} row. Trustworks colleagues are already on the meeting through
 * {@link AccountMeeting#getUserUuid()}, and attendees on unknown domains are dropped rather
 * than kept "just in case".
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "account_meeting_attendee")
public class AccountMeetingAttendee extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "meeting_uuid", length = 36, nullable = false)
    private String meetingUuid;

    @Column(name = "email", length = 320, nullable = false)
    private String email;

    /** What Outlook had for them. Null when the invitation carried only an address. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Lower-cased; the join back to {@code client_domain}. */
    @Column(name = "domain", length = 190, nullable = false)
    private String domain;
}
