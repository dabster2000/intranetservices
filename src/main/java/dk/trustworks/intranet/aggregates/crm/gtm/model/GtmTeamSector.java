package dk.trustworks.intranet.aggregates.crm.gtm.model;

import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
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
 * One sector a GTM team covers (sectors spec §4, V591).
 *
 * <p>A GTM team is a {@code FOCUS} bubble — Offentlig Digitalisering, Grøn Omstilling,
 * Fremtidens Finansielle Sektor, Pharma &amp; Life Science. One team may cover two
 * segments and a segment may have two teams, which is why this is a link table and not a
 * column on {@code bubbles}. A soft reference: {@code bubbleUuid} is not a foreign key,
 * matching every other CRM table.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "gtm_team_sector")
public class GtmTeamSector extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "bubble_uuid", length = 36, nullable = false)
    private String bubbleUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20, nullable = false)
    private ClientSegment segment;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;
}
