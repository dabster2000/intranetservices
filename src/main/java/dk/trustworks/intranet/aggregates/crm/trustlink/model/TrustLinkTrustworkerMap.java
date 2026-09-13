package dk.trustworks.intranet.aggregates.crm.trustlink.model;

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
 * A person's ruling on which Intra user a TrustLink trustworker name means — the escape
 * hatch above the four-rung matcher (CRM spec §3.7).
 *
 * <h2>Why an override table rather than a better heuristic</h2>
 * Rungs 3 and 4 of the matcher are guesses that happen to be right for the 61 names
 * TrustLink has today. They will be wrong for somebody eventually — two colleagues who
 * share a first and last name, a name that changed after a marriage, a display name spelled
 * the way nobody spells it. Tuning the heuristic to fix one case silently re-decides the
 * other sixty. A row here fixes exactly the one name and leaves the ladder alone; it beats
 * all four rungs and is recorded as {@code MatchMethod.MANUAL} on the edge.
 *
 * <h2>A null {@link #userUuid} is a decision, not a gap</h2>
 * It means "this name is deliberately unmapped — stop trying". The edge is still written
 * and still shown with the raw trustworker name; what stops is the guessing. That matters
 * for names that belong to nobody in Intra at all (a former colleague, an advisor), where
 * the ladder would otherwise keep producing a plausible-looking wrong answer every night.
 *
 * <p>The primary key is the trustworker name verbatim, because that is the only identity
 * TrustLink gives us for a colleague.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "trustlink_trustworker_map")
public class TrustLinkTrustworkerMap extends PanacheEntityBase {

    @Id
    @Column(name = "trustworker_name", length = 255)
    private String trustworkerName;

    /** The Intra user, or null for "deliberately unmapped — stop trying". */
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36)
    private String createdBy;
}
