package dk.trustworks.intranet.aggregates.crm.sector.model;

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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Who leads a sector, over time (sectors spec §4, V591).
 *
 * <p>The {@code practice_lead} idiom: several rows per segment across history, and the one
 * with {@code enddate = NULL} is the current lead. Starting a new lead ends the previous
 * row rather than overwriting it, so "who led Public in FY2025" stays answerable.
 *
 * <p>A sector lead is a PERSON, settled on 2026-09-13 against the alternative of "the GTM
 * bubble's owner": a GTM team can span two sectors and a sector can have two teams, so the
 * bubble owner does not identify one accountable person per sector.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "sector_lead")
public class SectorLead extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "segment", length = 20, nullable = false)
    private ClientSegment segment;

    @Column(name = "user_uuid", length = 36, nullable = false)
    private String userUuid;

    @Column(name = "startdate", nullable = false)
    private LocalDate startdate;

    /** Null while this is the current lead. */
    @Column(name = "enddate")
    private LocalDate enddate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
