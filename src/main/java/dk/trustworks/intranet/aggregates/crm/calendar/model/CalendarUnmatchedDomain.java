package dk.trustworks.intranet.aggregates.crm.calendar.model;

import dk.trustworks.intranet.aggregates.crm.calendar.model.enums.UnmatchedDomainStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A meeting domain Intra could not attribute to any client (spec §2.5, V601).
 *
 * <p>Rule 1 of {@code AccountCalendarSyncService} used to drop these events "without being
 * written anywhere". Only 34 of 307 clients have a domain, so a company several colleagues
 * keep meeting was invisible — which is precisely the population the customers /
 * prospects / contacts split exists for. A domain that keeps appearing now floats up by
 * itself on the Contacts view, with three things somebody can do about it: add it as a
 * company, say it belongs to a client we already have, or never ask again.
 *
 * <p><b>A domain, a count and a date. Never a person, never a subject.</b> A domain
 * identifies a COMPANY, which is why this is a smaller privacy footprint than
 * {@code account_meeting_attendee} already has. The mailboxes read are the same consent
 * list as ever.
 *
 * <p>The counts are DERIVED from {@link CalendarUnmatchedMeeting} and recomputed, never
 * incremented: the sync re-reads the last 14 days every night, so a counter would report
 * one coffee as fourteen.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "calendar_unmatched_domain")
public class CalendarUnmatchedDomain extends PanacheEntityBase {

    @Id
    @Column(name = "domain", length = 190)
    private String domain;

    @Column(name = "meetings_90d", nullable = false)
    private int meetings90d;

    @Column(name = "meetings_total", nullable = false)
    private int meetingsTotal;

    /** Distinct Trustworks mailboxes that had a meeting with the domain. */
    @Column(name = "people_count", nullable = false)
    private int peopleCount;

    @Column(name = "first_seen")
    private LocalDate firstSeen;

    @Column(name = "last_seen")
    private LocalDate lastSeen;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    private UnmatchedDomainStatus status;

    /** Set when somebody said the domain belongs to a client Intra already has. */
    @Column(name = "linked_client_uuid", length = 36)
    private String linkedClientUuid;

    @Column(name = "decided_by", length = 36)
    private String decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
