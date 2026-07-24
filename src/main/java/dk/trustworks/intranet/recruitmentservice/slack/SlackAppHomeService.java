package dk.trustworks.intranet.recruitmentservice.slack;

import com.slack.api.model.view.View;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.LandingResponse;
import dk.trustworks.intranet.recruitmentservice.dto.MyReferralRow;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentLandingService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentSlackFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.ReferralService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Builds and publishes one user's App Home dashboard (P23, Slack spec
 * §5.7). The single authoritative gate for every publish path — the
 * {@code app_home_opened} handler and the targeted-refresh reactor both
 * call {@link #publishFor(String)} and never publish on their own.
 *
 * <h3>Gating (all three, read per call)</h3>
 * <ul>
 *   <li>{@code recruitment.slack.interactivity.enabled} — the master
 *       gate. App Home is presented to admins as an inbound feature
 *       ("Needs master switch"), so the WHOLE surface is inert without
 *       it — including reactor-driven refreshes, which would otherwise
 *       keep painting a tab whose buttons answer "disabled". (For the
 *       {@code app_home_opened} path the dispatch already enforced this;
 *       re-checking keeps one truth for all callers.)</li>
 *   <li>{@code recruitment.pipeline.enabled} — the host-module flag (the
 *       P12/P22 reactor convention): while the module is dark, no
 *       side surface leaks its activity.</li>
 *   <li>{@code recruitment.slack.app-home.enabled} — the feature toggle.
 *       Off ⇒ no publish at all; Slack keeps showing the default empty
 *       Home (plan §P23 DoD).</li>
 * </ul>
 *
 * <h3>Posture</h3>
 * Best-effort by design: App Home is a convenience mirror that tolerates
 * staleness (spec §5.7), so every failure is logged and swallowed —
 * {@link #publishFor} never throws. The next {@code app_home_opened} or
 * task-set change repaints the tab.
 */
@JBossLog
@ApplicationScoped
public class SlackAppHomeService {

    /** Interview timestamps are wall-clock Europe/Copenhagen (the P11 model). */
    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentSlackFeatureFlag slackFlags;

    @Inject
    RecruitmentLandingService landingService;

    @Inject
    ReferralService referralService;

    @Inject
    SlackService slackService;

    @ConfigProperty(name = "dk.trustworks.recruitment.slack.base-url",
            defaultValue = "https://intra.trustworks.dk")
    String baseUrl;

    /** All three gates (class javadoc) — read per call, no cache. */
    public boolean enabled() {
        return slackFlags.isInteractivityEnabled()
                && featureFlag.isPipelineEnabled()
                && slackFlags.isAppHomeEnabled();
    }

    /**
     * Build and publish the Home view for one user. Returns true when a
     * view was actually published; false on any gate, an unlinked user or
     * a failure (logged, never thrown).
     */
    public boolean publishFor(String userUuid) {
        if (userUuid == null || !enabled()) {
            return false;
        }
        try {
            User user = User.findById(userUuid);
            if (user == null || user.getSlackusername() == null
                    || user.getSlackusername().isBlank()) {
                log.infof("App Home: user %s has no Slack link — skipping publish", userUuid);
                return false;
            }
            LandingResponse landing = landingService.build(userUuid);
            List<MyReferralRow> referrals =
                    referralService.listMine(UUID.fromString(userUuid)).referrals();
            View view = SlackAppHomeViews.appHomeView(landing, referrals,
                    slackFlags.isScorecardEnabled(), baseUrl, LocalDateTime.now(COPENHAGEN));
            slackService.publishView(user.getSlackusername(), view);
            return true;
        } catch (Exception e) {
            // Best-effort: a stale Home tab beats a blocked reactor or a
            // failed dispatch — the next open/refresh repaints it.
            log.warnf("App Home: publish for user %s failed — continuing: %s",
                    userUuid, e.getMessage());
            return false;
        }
    }
}
