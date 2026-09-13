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
 * The account layer over a client (CRM spec §3.1): which band it is in, which GTM team
 * runs it, the Slack space and the one-line next step.
 *
 * <p><b>A missing row is not missing data.</b> Only clients somebody has actually
 * triaged have a row here. Everything else reads as the defaults —
 * {@link AccountBand#BACKLOG}, no GTM team, no next step — which is the truth about the
 * several hundred clients nobody has looked at. {@code AccountService.readOrDefault}
 * never creates a row to answer a read; the row appears the first time a person changes
 * something.
 *
 * <p>The owner is deliberately NOT a column here. {@code client.accountmanager} remains
 * the source of truth (spec §9.7 left that migration open) and a
 * {@link ClientAccountRole} row of type {@code RESPONSIBLE} is kept in step with it, so
 * there is exactly one place to change an owner and no chance of the two drifting.
 *
 * <p>Never a REST body — the resource takes
 * {@code dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest}.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "client_account")
public class ClientAccount extends PanacheEntityBase {

    @Id
    @Column(name = "client_uuid", length = 36)
    private String clientUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "band", length = 20, nullable = false)
    private AccountBand band;

    /**
     * The GTM team: a {@code FOCUS} bubble ({@code bubbles.uuid}) such as Offentlig
     * Digitalisering or Grøn Omstilling. Not the client's own account-team bubble — that
     * is {@link #accountTeamBubbleUuid}. {@code AccountService} refuses any other bubble
     * type here (V591).
     */
    @Column(name = "gtm_bubble_uuid", length = 36)
    private String gtmBubbleUuid;

    /** The client's own {@code ACCOUNT_TEAM} bubble ({@code bubbles.uuid}), when it has one. */
    @Column(name = "account_team_bubble_uuid", length = 36)
    private String accountTeamBubbleUuid;

    /** Channel name without the leading {@code #}, e.g. {@code a_oersted}. */
    @Column(name = "slack_space", length = 80)
    private String slackSpace;

    /**
     * The Slack channel id ({@code C…}) the nightly {@code AccountSlackSyncJob} resolved
     * {@link #slackSpace} to (V594). Null until resolved; {@code AccountService.patch}
     * resets it whenever the name changes, so a renamed link is re-resolved rather than
     * read from the old channel forever.
     */
    @Column(name = "slack_channel_id", length = 32)
    private String slackChannelId;

    /**
     * Why the last resolution or read failed — {@code NOT_FOUND}, {@code NOT_IN_CHANNEL}
     * or {@code ARCHIVED} — or null when healthy. Shown next to the field in the header:
     * a typo must not fail silently, which is exactly what the lane did before V594.
     */
    @Column(name = "slack_link_error", length = 40)
    private String slackLinkError;

    /** Last successful read of the channel. */
    @Column(name = "slack_synced_at")
    private LocalDateTime slackSyncedAt;

    /** One line. Expected on Strategic and Active accounts; meaningless on Backlog. */
    @Column(name = "next_step", length = 200)
    private String nextStep;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 36, nullable = false)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 36, nullable = false)
    private String modifiedBy;
}
