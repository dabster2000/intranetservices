package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentDiscussionThread;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.services.AppSettingService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.UUID;

/**
 * Slack notifications for candidate discussions (the timeline notes):
 * <ul>
 *   <li><b>Channel thread</b> — one root message per candidate in the
 *       recruitment channel; each new note lands as a thread reply. The
 *       reply carries author + candidate + deep link — NEVER the note
 *       body (opinions about people stay in the intranet, GDPR-lean by
 *       design).</li>
 *   <li><b>Mention DMs</b> — every mentioned colleague gets a direct
 *       message with the deep link.</li>
 *   <li><b>Confidential candidates</b> (partner-track application or a
 *       sponsoring partner) get NO channel posts — in-app + DMs only,
 *       mirroring the circle-visibility rules.</li>
 * </ul>
 * Dark by default: the {@code recruitment.slack.discussion.enabled} app
 * setting (13th toggle on the admin "Recruitment AI & Slack" tab) must be
 * 'true' (staging-first rollout for external
 * integrations). All Slack I/O is called AFTER the note transaction
 * committed and every failure is logged and swallowed — a Slack outage
 * must never fail a note.
 */
@JBossLog
@ApplicationScoped
public class CandidateDiscussionSlackNotifier {

    /** app_settings toggle — the surface stays dark until flipped. */
    public static final String ENABLED_SETTING_KEY = "recruitment.slack.discussion.enabled";
    /** app_settings override for the discussion channel; falls back to the HR channel. */
    public static final String CHANNEL_SETTING_KEY = "recruitment.slack.channel.discussion";

    @Inject
    SlackService slackService;

    @Inject
    AppSettingService appSettingService;

    @ConfigProperty(name = "recruitment.hr.slack.channel-id", defaultValue = "C0B1XUB3AEB")
    String fallbackChannelId;

    @ConfigProperty(name = "recruitment.hr.slack.dossier-base-url",
            defaultValue = "https://intra.trustworks.dk/recruitment/candidates")
    String dossierBaseUrl;

    /**
     * Notify about a new note. Call AFTER the note's transaction has
     * committed (the resource layer, not the service). Never throws.
     *
     * @param candidate the candidate the note is on
     * @param actor     the note's author
     * @param mentions  users to DM; may be null/empty
     * @param isPrivate private notes never reach the channel (author +
     *                  recruiter + admin only in-app); mentions are still
     *                  DMed — the author explicitly chose them
     */
    public void onNoteAdded(RecruitmentCandidate candidate, UUID actor,
                            List<String> mentions, boolean isPrivate) {
        try {
            if (!isEnabled()) {
                return;
            }
            String candidateName = (nullSafe(candidate.getFirstName()) + " "
                    + nullSafe(candidate.getLastName())).trim();
            String authorName = resolveUserName(actor);
            String link = stripTrailingSlash(dossierBaseUrl) + "/" + candidate.getUuid();

            if (mentions != null) {
                for (String mentionUuid : mentions) {
                    dmMention(mentionUuid, authorName, candidateName, link);
                }
            }

            if (!isPrivate && !isConfidential(candidate)) {
                postThreadReply(candidate, authorName, candidateName, link);
            }
        } catch (Exception e) {
            log.errorf(e, "Discussion Slack notification failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
    }

    // ---- Channel thread --------------------------------------------------------

    private void postThreadReply(RecruitmentCandidate candidate, String authorName,
                                 String candidateName, String link) {
        String channel = channelId();
        try {
            String rootTs = threadRootTs(candidate, channel, candidateName, link);
            slackService.sendThreadReply(channel, rootTs,
                    authorName + " commented on " + candidateName + " — " + link);
        } catch (Exception e) {
            log.errorf(e, "Discussion thread reply failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
    }

    /**
     * Find or create the candidate's thread root. The root post and its
     * bookkeeping row are created lazily on the first non-private note; a
     * new transaction wraps the insert because the caller runs outside
     * one (post-commit notification path).
     */
    private String threadRootTs(RecruitmentCandidate candidate, String channel,
                                String candidateName, String link) throws Exception {
        RecruitmentDiscussionThread existing = RecruitmentDiscussionThread
                .<RecruitmentDiscussionThread>find("candidateUuid = ?1 and channelId = ?2",
                        candidate.getUuid(), channel)
                .firstResult();
        if (existing != null) {
            return existing.getRootTs();
        }
        String rootTs = slackService.sendMessageReturningTs(channel,
                "Discussion: " + candidateName + " — " + link, null);
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentDiscussionThread thread = new RecruitmentDiscussionThread();
            thread.setCandidateUuid(candidate.getUuid());
            thread.setChannelId(channel);
            thread.setRootTs(rootTs);
            thread.persist();
        });
        return rootTs;
    }

    // ---- Mention DMs -----------------------------------------------------------

    private void dmMention(String mentionUuid, String authorName,
                           String candidateName, String link) {
        try {
            User user = User.findById(mentionUuid);
            if (user == null) {
                log.debugf("Discussion mention skipped — unknown user %s", mentionUuid);
                return;
            }
            slackService.sendMessage(user,
                    authorName + " mentioned you in the discussion on " + candidateName
                            + " — " + link);
        } catch (Exception e) {
            log.errorf(e, "Discussion mention DM failed for user=%s: %s", mentionUuid, e.getMessage());
        }
    }

    // ---- Confidentiality -------------------------------------------------------

    /**
     * Partner-track candidates never reach the shared channel: a
     * sponsoring partner on the candidate, or any application on a
     * PARTNER-track position, marks the candidate confidential
     * (mirroring the circle-visibility rules).
     */
    boolean isConfidential(RecruitmentCandidate candidate) {
        if (candidate.getSponsoringPartnerUuid() != null) {
            return true;
        }
        return RecruitmentApplication.count(
                "candidateUuid = ?1 and positionUuid in "
                        + "(select p.uuid from RecruitmentPosition p where p.hiringTrack = ?2)",
                candidate.getUuid(), RecruitmentHiringTrack.PARTNER) > 0;
    }

    // ---- Helpers ---------------------------------------------------------------

    private boolean isEnabled() {
        return appSettingService.findByKey(ENABLED_SETTING_KEY)
                .map(AppSetting::getSettingValue)
                .map("true"::equalsIgnoreCase)
                .orElse(false);
    }

    private String channelId() {
        return appSettingService.findByKey(CHANNEL_SETTING_KEY)
                .map(AppSetting::getSettingValue)
                .filter(value -> !value.isBlank())
                .orElse(fallbackChannelId);
    }

    private static String resolveUserName(UUID actor) {
        if (actor == null) {
            return "Someone";
        }
        User user = User.findById(actor.toString());
        if (user == null) {
            return "Someone";
        }
        String name = (nullSafe(user.getFirstname()) + " " + nullSafe(user.getLastname())).trim();
        return name.isEmpty() ? "Someone" : name;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
