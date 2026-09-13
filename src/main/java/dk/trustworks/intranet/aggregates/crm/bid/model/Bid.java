package dk.trustworks.intranet.aggregates.crm.bid.model;

import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidGoNoGo;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidOutcome;
import dk.trustworks.intranet.aggregates.crm.bid.model.enums.BidType;
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
 * One bid on an account (CRM spec §3.6).
 *
 * <p><b>A bid is not a lead.</b> A lead is work we might be asked to staff; a bid is a
 * document we chose to write, or chose not to. The go/no-go decision, the price we put in,
 * who we lost to and the post-mortem have no home on {@code sales_lead} and would distort
 * the pipeline if they were forced onto it. {@link #leadUuid} links the two when a bid did
 * produce a lead.
 *
 * <p>{@link #postMortem} is free text and is never shown in an aggregate — the spec is
 * explicit about that. It is written for the next person who bids at this client, not for
 * a dashboard.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "bid")
public class Bid extends PanacheEntityBase {

    @Id
    @Column(name = "uuid", length = 36)
    private String uuid;

    @Column(name = "client_uuid", length = 36, nullable = false)
    private String clientUuid;

    /** The lead this bid produced, when it produced one. */
    @Column(name = "lead_uuid", length = 36)
    private String leadUuid;

    @Column(name = "title", length = 300, nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 15, nullable = false)
    private BidType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "go_nogo", length = 10, nullable = false)
    private BidGoNoGo goNoGo;

    /** What we quoted. Null on a no-go, and on a bid nobody has priced yet. */
    @Column(name = "price")
    private Double price;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20, nullable = false)
    private BidOutcome outcome;

    /** Only ever set when the outcome is LOST — naming a competitor on a win is a claim we cannot support. */
    @Column(name = "competitor", length = 200)
    private String competitor;

    /** Free text. Never shown in an aggregate (spec §3.6). */
    @Column(name = "post_mortem", columnDefinition = "TEXT")
    private String postMortem;

    @Column(name = "owner_uuid", length = 36, nullable = false)
    private String ownerUuid;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
