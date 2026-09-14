package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.aggregates.crm.sector.services.SectorLeadService;
import dk.trustworks.intranet.aggregates.crm.sector.services.SectorService;
import dk.trustworks.intranet.aggregates.crm.signal.services.AccountSignalService;
import dk.trustworks.intranet.aggregates.crm.slack.model.AccountSlackMention;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * "This is not about this client" — the one thing a person can do to a mention row
 * (spec §6.2).
 *
 * <p>The lane reads general channels, so it is the one place in the CRM where a row can
 * land on an account that was never being discussed: a company name the model matched to
 * the wrong client, or a sentence about a competitor with a similar name. The account owner
 * has to be able to take it off their timeline, and the only honest way to do that is to
 * mark it rather than to delete it — an extraction that went wrong is exactly the evidence
 * somebody will want when asking why, and the sync must be able to see the dismissal to
 * avoid resurrecting the row on its next re-read of that day.
 *
 * <h2>Who may dismiss is the signal rule, not a second one</h2>
 * The owner; the sector lead when there is no owner; management anywhere — reached through
 * {@link AccountSignalService#mayDecide} rather than restated here, because two copies of
 * an authorization rule are two rules the day one of them is changed. The scope
 * ({@code accounts:write}) only says the caller is in the sales tier at all; WHICH accounts
 * they may act on is this check, and no scope can express "the owner of this particular
 * account".
 *
 * <h2>Idempotent on purpose</h2>
 * Dismissing an already-dismissed row is a no-op that answers success and keeps the first
 * dismissal's attribution. The button is body-less and a double-click is the ordinary way
 * this endpoint is called twice; rewriting {@code dismissed_by} on the second press would
 * quietly change who the audit says took the decision.
 *
 * <p>There is no restore endpoint (D2). Undoing a dismissal is rare enough to be a
 * conversation, and a row anybody can un-hide is not a record of a decision.
 */
@JBossLog
@ApplicationScoped
public class AccountSlackMentionService {

    @Inject
    ClientService clientService;

    @Inject
    SectorLeadService sectorLeadService;

    /**
     * Takes one mention off an account's timeline and off its relationship graph.
     *
     * <p>The client is part of the address and is verified against the row rather than
     * trusted: the uuid in the path is what the caller was authorized for, and a mention
     * belonging to a different account must not be reachable by guessing its uuid under an
     * account the caller happens to own. A row that is not this client's answers 404, the
     * same as one that does not exist — which of the two it is, is not a caller's business.
     *
     * @param management true when the caller holds ADMIN/PARTNER, resolved by the resource
     *                   from the PERSON's roles and never from the JWT, whose groups are
     *                   the BFF's scopes
     */
    @Transactional
    public AccountSlackMention dismiss(String clientUuid, String mentionUuid, String actor, boolean management) {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a dismissal records who took it", Response.Status.BAD_REQUEST);
        }
        AccountSlackMention row = AccountSlackMention.findById(mentionUuid);
        if (row == null || clientUuid == null || !clientUuid.trim().equals(row.getClientUuid())) {
            throw new WebApplicationException("Unknown mention", Response.Status.NOT_FOUND);
        }
        if (row.getDismissedAt() != null) {
            return row;
        }

        Client client = clientService.findByUuid(row.getClientUuid());
        boolean hasOwner = client != null && client.getAccountmanager() != null
                && !client.getAccountmanager().isBlank();
        boolean isOwner = hasOwner && client.getAccountmanager().equals(actor);
        boolean isSectorLead = !hasOwner && client != null
                && sectorLeadService.isCurrentLead(SectorService.segmentOf(client), actor);
        if (!AccountSignalService.mayDecide(management, isOwner, hasOwner, isSectorLead)) {
            throw new WebApplicationException(
                    hasOwner
                            ? "Only the account's owner can take a Slack mention off it"
                            : "This account has no owner — only its sector lead can take a Slack mention off it",
                    Response.Status.FORBIDDEN);
        }

        row.setDismissedBy(actor);
        row.setDismissedAt(LocalDateTime.now());
        row.persist();

        // Uuids and nothing else: the headline is a paraphrase of what a channel said.
        log.infof("Slack mention dismissed: uuid=%s client=%s actor=%s",
                row.getUuid(), row.getClientUuid(), actor);
        return row;
    }
}
