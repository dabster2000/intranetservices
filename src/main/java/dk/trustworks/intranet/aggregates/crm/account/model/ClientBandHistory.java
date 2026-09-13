package dk.trustworks.intranet.aggregates.crm.account.model;

import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
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

import java.time.LocalDateTime;

/**
 * Every band change (CRM spec §3.1). Written by {@code AccountService} on each change and
 * read back as the {@code BAND} rows of the account timeline.
 *
 * <p>{@link #fromBand} is null on the first change, because "Backlog" before that was a
 * default rather than a decision and saying "Backlog → Active" would put words in
 * somebody's mouth.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_band_history")
public class ClientBandHistory extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** Null when the account had no row before — the previous band was a default, not a choice. */
    @Enumerated(EnumType.STRING)
    @Column(name = "from_band", length = 20)
    private AccountBand fromBand;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_band", length = 20, nullable = false)
    private AccountBand toBand;

    /** Why, when the person said why. Optional — the spec asks for nothing else. */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "changed_by", length = 36, nullable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;
}
