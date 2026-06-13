package dk.trustworks.intranet.domain.user.entity;

import dk.trustworks.intranet.aggregates.users.danlon.DanlonEventType;
import dk.trustworks.intranet.aggregates.users.danlon.ProposalIntent;
import dk.trustworks.intranet.aggregates.users.danlon.ProposalStatus;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * A PENDING/resolved proposal to mint, reopen, or close a Danløn number
 * (spec §4.1). This table NEVER stores a minted number — suggestedNumber
 * is advisory; the real number is HR's confirmedNumber at approval.
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "danlon_assignment_proposal")
public class DanlonAssignmentProposal extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", nullable = false, length = 36)
    private String uuid;

    @Column(name = "useruuid", nullable = false, length = 36)
    private String useruuid;

    @Column(name = "company_uuid", nullable = false, length = 36)
    private String companyUuid;

    @Column(name = "effective_month", nullable = false)
    private LocalDate effectiveMonth;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private DanlonEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", nullable = false, length = 20)
    private ProposalIntent intent;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProposalStatus status;

    @Column(name = "suggested_number", length = 36)
    private String suggestedNumber;

    @Column(name = "target_history_uuid", length = 36)
    private String targetHistoryUuid;

    @Column(name = "minted_history_uuid", length = 36)
    private String mintedHistoryUuid;

    @Column(name = "detected_date", nullable = false)
    private LocalDateTime detectedDate;

    @Column(name = "detected_by", length = 255)
    private String detectedBy;

    @Column(name = "resolved_date")
    private LocalDateTime resolvedDate;

    @Column(name = "resolved_by", length = 255)
    private String resolvedBy;

    @Column(name = "resolution_note", length = 1024)
    private String resolutionNote;

    /** The single live (PENDING) proposal for a slot, or null. */
    public static DanlonAssignmentProposal findPendingForSlot(
            String useruuid, String companyUuid, LocalDate effectiveMonth, DanlonEventType eventType) {
        return find("useruuid = ?1 AND companyUuid = ?2 AND effectiveMonth = ?3 AND eventType = ?4 AND status = ?5",
                useruuid, companyUuid, effectiveMonth, eventType, ProposalStatus.PENDING)
                .firstResult();
    }

    /** PENDING proposals for the salary-payment panel, oldest first. */
    public static List<DanlonAssignmentProposal> findPendingByCompanyMonth(String companyUuid, LocalDate month) {
        return find("companyUuid = ?1 AND effectiveMonth = ?2 AND status = ?3 ORDER BY detectedDate ASC",
                companyUuid, month, ProposalStatus.PENDING).list();
    }

    /** Any PENDING CLOSE proposal targeting the given history row, or null. */
    public static DanlonAssignmentProposal findPendingCloseForTarget(String targetHistoryUuid) {
        return find("targetHistoryUuid = ?1 AND intent = ?2 AND status = ?3",
                targetHistoryUuid, ProposalIntent.CLOSE, ProposalStatus.PENDING).firstResult();
    }
}
