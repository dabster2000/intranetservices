package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.integration.GraphSubscription;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Daily-cron worker that renews Microsoft Graph webhook subscriptions before they
 * expire. If renewal fails non-retryably (e.g. the subscription has been GC'd by
 * Graph), the worker self-heals by deleting the local row and re-creating a fresh
 * subscription against the same resource.
 *
 * <p>Microsoft Graph caps event-subscription lifetime at 4230 minutes (~70.5h);
 * we always extend out to that maximum on every renewal.
 *
 * <p>Panache static seam: {@link #findExpiringBy(LocalDateTime)},
 * {@link #deleteSubscription(GraphSubscription)}, and
 * {@link #persistSubscription(GraphSubscription)} are package-private so unit tests
 * can override them — Mockito cannot stub statics inherited from
 * {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class GraphSubscriptionRenewalWorker {

    private static final Logger LOG = Logger.getLogger(GraphSubscriptionRenewalWorker.class);

    /** Microsoft Graph events-subscription max lifetime, in minutes. */
    static final long MAX_LIFE_MINUTES = 4230L;

    @Inject OutlookCalendarPort outlook;

    @ConfigProperty(name = "recruitment.graph.notification-url")
    String notificationUrl;

    @ConfigProperty(name = "recruitment.graph.client-state-secret")
    String clientStateSecret;

    @Scheduled(every = "24h")
    @Transactional
    public void renew() {
        LocalDateTime threshold = LocalDateTime.now().plusHours(48);
        List<GraphSubscription> expiring = findExpiringBy(threshold);
        LOG.debugf("GraphSubscriptionRenewalWorker: %d subscription(s) expiring within 48h", expiring.size());

        for (GraphSubscription g : expiring) {
            Instant newExpiry = Instant.now().plusSeconds(MAX_LIFE_MINUTES * 60);
            try {
                GraphSubscriptionInfo info = outlook.renewSubscription(g.subscriptionId, newExpiry);
                g.expiresAt = LocalDateTime.ofInstant(info.expiresAt(), ZoneOffset.UTC);
                LOG.debugf("Renewed Graph subscription %s, new expiresAt=%s", g.subscriptionId, g.expiresAt);
            } catch (OutlookCalendarException ex) {
                if (!ex.isRetryable()) {
                    LOG.warnf("Non-retryable failure renewing %s (%s); recreating from scratch.",
                            g.subscriptionId, ex.getErrorCode());
                    String resource = g.resource;
                    deleteSubscription(g);
                    GraphSubscriptionInfo created = outlook.createEventSubscription(
                            new SubscribeCommand(resource, notificationUrl, clientStateSecret, newExpiry));
                    persistSubscription(GraphSubscription.create(
                            created.subscriptionId(),
                            created.resource(),
                            LocalDateTime.ofInstant(created.expiresAt(), ZoneOffset.UTC),
                            sha256Hex(clientStateSecret)));
                } else {
                    LOG.infof("Retryable failure renewing %s (%s); leaving for next cycle.",
                            g.subscriptionId, ex.getErrorCode());
                }
            }
        }
    }

    List<GraphSubscription> findExpiringBy(LocalDateTime threshold) {
        return GraphSubscription.list("expiresAt <= ?1", threshold);
    }

    void deleteSubscription(GraphSubscription g) {
        g.delete();
    }

    void persistSubscription(GraphSubscription g) {
        g.persist();
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
