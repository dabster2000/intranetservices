package dk.trustworks.intranet.aggregates.crm.merge.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row per merge (V615): who was merged into whom, by whom, and what moved.
 *
 * <p>INSERT-ONLY. The three JSON columns are what a person reads when asking "what did
 * that merge do" — and, for {@code orphanedEconomicsJson}, the list of e-conomic customer
 * numbers the merge deliberately left live for somebody to deactivate by hand (spec §5).
 * Nothing queries into them.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "client_merge_audit")
public class ClientMergeAudit extends PanacheEntityBase {

    @Id
    @Column(length = 36)
    private String uuid;

    @Column(name = "winner_uuid", nullable = false, length = 36)
    private String winnerUuid;

    @Column(name = "loser_uuid", nullable = false, length = 36)
    private String loserUuid;

    @Column(name = "loser_name", nullable = false, length = 255)
    private String loserName;

    @Column(name = "loser_type", nullable = false, length = 10)
    private String loserType;

    @Column(name = "loser_cvr", length = 20)
    private String loserCvr;

    /** WINNER or LOSER — whose client_account and owner the survivor kept. */
    @Column(name = "account_from", nullable = false, length = 10)
    private String accountFrom;

    @Column(name = "moved_json", nullable = false, columnDefinition = "LONGTEXT")
    private String movedJson;

    @Column(name = "dropped_json", nullable = false, columnDefinition = "LONGTEXT")
    private String droppedJson;

    @Column(name = "orphaned_economics_json", nullable = false, columnDefinition = "LONGTEXT")
    private String orphanedEconomicsJson;

    @Column(name = "actor_uuid", nullable = false, length = 36)
    private String actorUuid;

    @Column(name = "merged_at", nullable = false)
    private LocalDateTime mergedAt;

    /** Merges this client survived, newest first — the account page's "merged from" line. */
    public static List<ClientMergeAudit> findByWinner(String winnerUuid) {
        return list("winnerUuid = ?1 order by mergedAt desc", winnerUuid);
    }
}
