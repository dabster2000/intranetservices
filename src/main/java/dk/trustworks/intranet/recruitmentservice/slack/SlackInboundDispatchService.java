package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import io.quarkus.panache.common.Sort;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * The inbound-Slack dispatch pipeline (P13, Slack spec §4.2): master
 * gate → actor resolution → dedupe claim → allowlist dispatch. The BFF
 * has already verified the request signature against the raw body; this
 * service owns everything identity- and idempotency-shaped.
 *
 * <h3>Pipeline, in order</h3>
 * <ol>
 *   <li><b>Master gate</b> ({@code recruitment.slack.interactivity.enabled},
 *       read per call, no cache): off ⇒ {@code DISABLED} + the standard
 *       "currently disabled" ephemeral. Defense in depth — the BFF
 *       middleware checks the same flag before forwarding.</li>
 *   <li><b>Actor resolution, fail-closed</b>: Slack user id →
 *       {@code users.slackusername} → active intranet user. No match,
 *       or a user whose current status is TERMINATED/PREBOARDING ⇒
 *       {@code NOT_LINKED} + the "not linked" ephemeral, and <b>zero
 *       side effects</b>. Deliberately no email-matching fallback at
 *       interaction time — {@code SlackUserSyncJob} owns the mapping
 *       (Slack spec §7).</li>
 *   <li><b>Dedupe claim</b> (the V441 atomic-claim idiom): INSERT
 *       IGNORE on the {@code payload_key} PK — Slack's Events API
 *       retries deliveries up to 3× and interactive payloads can
 *       double-fire; only the transaction whose insert affected a row
 *       dispatches. Claim + handler share this transaction, so a crash
 *       mid-handler rolls the claim back and the Slack retry
 *       re-executes cleanly. Rows older than 24 h are pruned
 *       opportunistically (Slack's retry horizon is minutes).</li>
 *   <li><b>Allowlist dispatch</b>: handler beans register by
 *       {@link SlackInboundHandler#key()}; unknown keys are logged
 *       (key only, never payload content) and dropped — no dynamic
 *       dispatch. The allowlist is <b>empty in P13</b>; the first
 *       handlers arrive in P14.</li>
 * </ol>
 *
 * <p>PII discipline: nothing user-supplied is logged — surface, kind,
 * handler key and disposition only. Slack user ids appear at debug
 * level only.
 */
@JBossLog
@ApplicationScoped
public class SlackInboundDispatchService {

    /** Spec-locked ephemeral (Slack spec §3.3). */
    static final String DISABLED_TEXT = "This feature is currently disabled.";
    /** Spec-locked ephemeral (Slack spec §4.2 step 1). */
    static final String NOT_LINKED_TEXT = "Your Slack account isn't linked to an intranet user";

    /** Claims older than this are pruned on the next dispatch. */
    static final int DEDUPE_TTL_HOURS = 24;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    EntityManager em;

    @Inject
    @Any
    Instance<SlackInboundHandler> handlers;

    private Map<String, SlackInboundHandler> allowlist;

    @PostConstruct
    void buildAllowlist() {
        Map<String, SlackInboundHandler> byKey = new HashMap<>();
        for (SlackInboundHandler handler : handlers) {
            SlackInboundHandler previous = byKey.put(handler.key(), handler);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate SlackInboundHandler key: " + handler.key());
            }
        }
        this.allowlist = Map.copyOf(byKey);
    }

    @Transactional
    public SlackInboundResponse dispatch(SlackInboundRequest request) {
        // 1. Master gate — read per call, no cache. The BFF already
        //    checked; re-checking here keeps the backend safe against any
        //    other system-token caller.
        if (!slackFlags.isInteractivityEnabled()) {
            log.infof("Slack inbound dropped: interactivity disabled (surface=%s kind=%s)",
                    request.surface(), request.kind());
            return SlackInboundResponse.disabled(DISABLED_TEXT);
        }

        // 2. Actor resolution, fail-closed.
        User actor = resolveActiveUser(request.slackUserId());
        if (actor == null) {
            log.infof("Slack inbound dropped: unlinked or inactive Slack user (surface=%s kind=%s)",
                    request.surface(), request.kind());
            return SlackInboundResponse.notLinked(NOT_LINKED_TEXT);
        }

        // 3. Dedupe claim — atomic on the payload_key PK.
        if (!claim(request)) {
            log.infof("Slack inbound dropped: duplicate payload (surface=%s kind=%s key=%s)",
                    request.surface(), request.kind(), request.handlerKey());
            return SlackInboundResponse.duplicate();
        }

        // 4. Allowlist dispatch — unknown ids logged and dropped.
        SlackInboundHandler handler = allowlist.get(request.handlerKey());
        if (handler == null) {
            log.warnf("Slack inbound dropped: no handler on the allowlist (surface=%s kind=%s key=%s)",
                    request.surface(), request.kind(), request.handlerKey());
            return SlackInboundResponse.unknown();
        }
        return handler.handle(actor, request);
    }

    /**
     * Slack user id → {@code user.slackusername} → active user.
     * {@code User.slackusername} carries the Slack member id
     * ({@code U…}) maintained by {@code SlackUserSyncJob} — the same
     * column {@code SlackService.sendMessage(User, …)} DMs to.
     * Returns null (fail-closed) for no match, ambiguous matches, or a
     * user whose current status is TERMINATED/PREBOARDING.
     * <p>
     * The status is queried directly on {@code userstatus} —
     * {@code User.statuses} is {@code @Transient} (populated by
     * {@code UserService}, never loaded by Panache), so
     * {@code user.getUserStatus(...)} on a freshly loaded entity would
     * report every user as TERMINATED.
     */
    private User resolveActiveUser(String slackUserId) {
        if (slackUserId == null || slackUserId.isBlank()) {
            return null;
        }
        var matches = User.<User>list("slackusername", slackUserId);
        if (matches.size() != 1) {
            if (matches.size() > 1) {
                log.warnf("Slack inbound: %d users share one slackusername — failing closed", matches.size());
            }
            return null;
        }
        User user = matches.getFirst();
        UserStatus latest = UserStatus.<UserStatus>find(
                        "useruuid = ?1 and statusdate <= ?2",
                        Sort.descending("statusdate"),
                        user.getUuid(), LocalDate.now())
                .firstResult();
        if (latest == null) {
            return null;
        }
        StatusType status = latest.getStatus();
        if (status == StatusType.TERMINATED || status == StatusType.PREBOARDING) {
            return null;
        }
        return user;
    }

    /** INSERT IGNORE claim; true = this transaction owns the payload. */
    private boolean claim(SlackInboundRequest request) {
        pruneExpired();
        String payloadKey = request.surface() + ":" + request.payloadId();
        int claimed = em.createNativeQuery(
                        "INSERT IGNORE INTO recruitment_slack_inbound_dedupe "
                                + "(payload_key, slack_team_id) VALUES (:payloadKey, :teamId)")
                .setParameter("payloadKey", payloadKey)
                .setParameter("teamId", request.slackTeamId())
                .executeUpdate();
        return claimed == 1;
    }

    /**
     * Opportunistic TTL prune — the table only ever holds the last day's
     * payload ids (Slack retries within minutes), so this stays a cheap
     * indexed range delete on every dispatch.
     */
    private void pruneExpired() {
        em.createNativeQuery(
                        "DELETE FROM recruitment_slack_inbound_dedupe "
                                + "WHERE received_at < NOW(3) - INTERVAL " + DEDUPE_TTL_HOURS + " HOUR")
                .executeUpdate();
    }
}
