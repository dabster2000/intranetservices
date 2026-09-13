package dk.trustworks.intranet.aggregates.crm.trustlink.model;

import dk.trustworks.intranet.aggregates.crm.trustlink.model.enums.MatchMethod;
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
 * The edge itself: a named Trustworks person is connected to a {@link TrustLinkConnection}
 * (CRM spec §3.7).
 *
 * <h2>Why the name is stored and not only the user uuid</h2>
 * TrustLink knows colleagues by display name, not by our uuid, and the four-rung matcher
 * resolves all 61 of today's trustworkers but is not guaranteed to resolve the 62nd.
 * {@link #trustworkerName} is therefore the identity of the edge and {@link #userUuid} is
 * an annotation on it that may be missing. <b>An unresolved row is still shown.</b>
 * "Marie Dorthea · 242 tier-5 connections" is precisely the signal this feature exists to
 * surface, and dropping it because a name did not resolve would throw away the answer while
 * keeping the question.
 *
 * <h2>{@link #matchMethod} is there to make a wrong match diagnosable</h2>
 * Rungs 1 and 2 (e-mail, full name) are facts. Rungs 3 and 4 (first+last token, prefix
 * subsequence) are heuristics that each require a UNIQUE hit — {@code Ditte Hjorth} →
 * {@code Ditte Marie Hjorth} is right, and an ambiguous name yields no match rather than a
 * guess. When somebody reports that the wrong colleague is credited with a relationship,
 * this column says which rule to blame, and {@link TrustLinkTrustworkerMap} is how it gets
 * overruled permanently.
 *
 * <h2>Lifecycle</h2>
 * Keyed by {@code (connection_uuid, trustworker_name)} with the uuid derived from that pair,
 * so the nightly run updates rather than duplicates. Upsert only: {@link #firstSeenAt} on
 * insert, {@link #lastSeenAt} on every sighting. The row is deleted only when its connection
 * is, by the foreign key's {@code ON DELETE CASCADE} — the sync itself never deletes anything.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "trustlink_connection_trustworker")
public class TrustLinkConnectionTrustworker extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "connection_uuid", length = 36, nullable = false)
    private String connectionUuid;

    /** Verbatim from TrustLink. The half of the key that is stable across runs. */
    @Column(name = "trustworker_name", length = 255, nullable = false)
    private String trustworkerName;

    /** Null when no rung of the ladder produced a unique hit. The edge is kept regardless. */
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    /** Which rung resolved {@link #userUuid}, or null when nothing did. */
    @Enumerated(EnumType.STRING)
    @Column(name = "match_method", length = 16)
    private MatchMethod matchMethod;

    /**
     * The date the connection was made, as TrustLink has it. Stored as a date: the upstream
     * value is a local datetime whose time component is always midnight, and pretending it
     * carries a time would invite somebody to render one.
     */
    @Column(name = "connected_on")
    private LocalDate connectedOn;

    @Column(name = "first_seen_at", nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private LocalDateTime lastSeenAt;
}
