package dk.trustworks.intranet.aggregates.crm.enrichment.model;

import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.CvrEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * What the nightly enrichment jobs know about one client (V610): the state of its CVR
 * verification, its logo, and its sector check.
 *
 * <p>One row per client, created on first touch. A client with no row is simply one no
 * job has reached yet — every reader treats the absence as {@code PENDING} everywhere,
 * which is also what the column defaults say.
 *
 * <p>The three status columns are stored as strings and read through each enum's
 * {@code parse}, so a value written by a newer code version degrades to {@code PENDING}
 * on an older one instead of failing the row (the V609 posture).
 */
@Getter
@Setter
@Entity
@Table(name = "client_enrichment")
public class ClientEnrichment extends PanacheEntityBase {

    @Id
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    // ---- CVR ----------------------------------------------------------------

    @Column(name = "cvr_status", length = 20, nullable = false)
    private String cvrStatus = CvrEnrichmentStatus.PENDING.name();

    @Column(name = "cvr_checked_at")
    private LocalDateTime cvrCheckedAt;

    @Column(name = "cvr_verified_at")
    private LocalDateTime cvrVerifiedAt;

    /** The 8-digit CVR the AI proposed for a row that had none, awaiting a person. */
    @Column(name = "cvr_candidate", length = 8)
    private String cvrCandidate;

    /** The registry's legal name for {@link #cvrCandidate} — what the person compares against. */
    @Column(name = "cvr_candidate_name", length = 255)
    private String cvrCandidateName;

    /** Where the AI says it found the candidate, for the person to check. */
    @Column(name = "cvr_candidate_source", length = 500)
    private String cvrCandidateSource;

    @Column(name = "cvr_error", length = 255)
    private String cvrError;

    @Column(name = "cvr_attempts", nullable = false)
    private int cvrAttempts;

    // ---- Logo ---------------------------------------------------------------

    @Column(name = "logo_status", length = 20, nullable = false)
    private String logoStatus = LogoEnrichmentStatus.PENDING.name();

    @Column(name = "logo_checked_at")
    private LocalDateTime logoCheckedAt;

    /** The page or image the logo was taken from; null for a generated one. */
    @Column(name = "logo_source_url", length = 1000)
    private String logoSourceUrl;

    @Column(name = "logo_error", length = 255)
    private String logoError;

    @Column(name = "logo_attempts", nullable = false)
    private int logoAttempts;

    // ---- Sector -------------------------------------------------------------

    @Column(name = "sector_status", length = 20, nullable = false)
    private String sectorStatus = SectorEnrichmentStatus.PENDING.name();

    @Column(name = "sector_checked_at")
    private LocalDateTime sectorCheckedAt;

    /** The segment the model answered, whatever was done with it. */
    @Column(name = "sector_ai_segment", length = 20)
    private String sectorAiSegment;

    @Column(name = "sector_confidence", precision = 4, scale = 3)
    private BigDecimal sectorConfidence;

    @Column(name = "sector_reason", length = 500)
    private String sectorReason;

    @Column(name = "sector_attempts", nullable = false)
    private int sectorAttempts;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    public ClientEnrichment() {
    }

    public ClientEnrichment(String clientUuid) {
        this.clientUuid = clientUuid;
    }

    public CvrEnrichmentStatus cvrStatus() {
        return CvrEnrichmentStatus.parse(cvrStatus);
    }

    public LogoEnrichmentStatus logoStatus() {
        return LogoEnrichmentStatus.parse(logoStatus);
    }

    public SectorEnrichmentStatus sectorStatus() {
        return SectorEnrichmentStatus.parse(sectorStatus);
    }

    public void setCvr(CvrEnrichmentStatus status) {
        this.cvrStatus = status.name();
    }

    public void setLogo(LogoEnrichmentStatus status) {
        this.logoStatus = status.name();
    }

    public void setSector(SectorEnrichmentStatus status) {
        this.sectorStatus = status.name();
    }

    /** Clears every candidate field — done whenever the CVR question is re-opened or closed. */
    public void clearCvrCandidate() {
        this.cvrCandidate = null;
        this.cvrCandidateName = null;
        this.cvrCandidateSource = null;
    }
}
