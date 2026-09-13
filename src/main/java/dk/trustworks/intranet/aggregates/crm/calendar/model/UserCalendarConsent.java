package dk.trustworks.intranet.aggregates.crm.calendar.model;

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
 * One employee's decision about whether Intra may read their calendar's METADATA
 * (CRM spec §3.7).
 *
 * <p><b>An absent row is not "off".</b> It means nobody has decided, and the ROLE DEFAULT
 * applies: SALES, PARTNER and ADMIN are on — client contact is their job — and everyone
 * else is off. A present row is always an explicit decision, in either direction, and
 * always beats the default. That is why {@link #enabled} has no database default: there is
 * no such thing as an implicit row here.
 *
 * <p>{@link #decidedBy} is the person themselves in this cut. Nobody sets another
 * employee's consent, which is the only reading of the word that means anything.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "user_calendar_consent")
public class UserCalendarConsent extends PanacheEntityBase {

    @Id
    @Column(name = "user_uuid", length = 36)
    private String userUuid;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "decided_at", nullable = false)
    private LocalDateTime decidedAt;

    /** Always equal to {@link #userUuid} in this cut — consent is never granted for somebody. */
    @Column(name = "decided_by", length = 36, nullable = false)
    private String decidedBy;
}
