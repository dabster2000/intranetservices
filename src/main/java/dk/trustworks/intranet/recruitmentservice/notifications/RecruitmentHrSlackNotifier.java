package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts a single Slack notification to HR when a recruitment candidate's
 * document promotion completes successfully on Convert. Idempotent across
 * the JVM lifetime via an in-memory candidate-UUID dedup set, so the
 * promotion re-drive sweep cannot trigger a second notification.
 *
 * <h3>Channel resolution (offer split, 2026-08-25)</h3>
 * Every message resolves its channel per send:
 * {@code recruitment.slack.channel.offer} when configured (the HR-facing
 * offer-phase channel — settings-driven, effective without redeploy),
 * otherwise {@code recruitment.slack.channel.default}, otherwise the
 * {@code recruitment.hr.slack.channel-id} config property — which is
 * byte-identical to the pre-split behavior, so an unconfigured offer
 * channel changes nothing. {@link #notifyContractSent} is the exception:
 * it is a NEW moment that exists only for the offer channel, so it posts
 * nowhere when that channel is blank.
 *
 * <h3>PII boundary</h3>
 * The message body is restricted to non-sensitive data: candidate first/last
 * name, target company display name, recruiter username, dossier link
 * (candidate UUID only), and the list of signed-PDF filenames. The
 * candidate's email, CPR, NextSign caseKey, signer emails, and contract
 * amounts are explicitly excluded.
 *
 * <h3>Failure semantics</h3>
 * Any exception thrown by {@link SlackService} (or by data resolution) is
 * caught, logged at ERROR with the candidate UUID, and swallowed so the
 * caller's transactional flow (the promotion result apply) is not
 * affected. Sends use the STRICT variant, so a not-ok Slack response (e.g.
 * {@code channel_not_found} on a private offer channel the bot is not a
 * member of) reaches that catch block instead of being swallowed inside
 * {@link SlackService} — before 2026-08-27 such a loss logged a false
 * "posted" INFO here and one unattributable ERROR there.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentHrSlackNotifier {

    /**
     * In-memory dedup set, JVM-lifetime. Mirrors the pattern used by
     * {@link RecruitmentSignatureCompletionListener#NOTIFIED_CASE_KEYS} but
     * keyed on candidate UUID — the promotion re-drive sweep may cause the
     * Convert flow to re-enter on PARTIAL/FAILED→COMPLETED transitions.
     */
    private static final Set<String> NOTIFIED_CANDIDATE_UUIDS = ConcurrentHashMap.newKeySet();

    /**
     * Dedup set for the onboarding-upload-complete notification, keyed on
     * token UUID. Prevents a duplicate Slack post if the final upload's
     * persistence retries (e.g. constraint-violation re-throw under load).
     */
    private static final Set<String> NOTIFIED_ONBOARDING_TOKEN_UUIDS = ConcurrentHashMap.newKeySet();

    @Inject
    SlackService slackService;

    @Inject
    RecruitmentSlackChannelRouter router;

    /** Last-resort fallback when neither the offer nor the default channel is set. */
    @ConfigProperty(name = "recruitment.hr.slack.channel-id", defaultValue = "C0B1XUB3AEB")
    String channelId;

    /** The channel HR messages post to — see the class javadoc's resolution order. */
    private String hrChannel() {
        return router.offerChannel()
                .or(() -> router.defaultChannel())
                .orElse(channelId);
    }

    @ConfigProperty(name = "recruitment.hr.slack.bot-token-key", defaultValue = "mother")
    String botTokenKey;

    @ConfigProperty(name = "recruitment.hr.slack.dossier-base-url",
            defaultValue = "https://intra.trustworks.dk/recruitment/candidates")
    String dossierBaseUrl;

    /**
     * Notify HR that {@code candidate} has been hired and their signed
     * documents have landed in their employee file. No-ops if a notification was
     * already sent for this candidate UUID in the current JVM.
     *
     * @param candidate       the hired candidate (must not be null)
     * @param recruiterUuid   the actor (recruiter) who triggered Convert; may
     *                        be {@code null} if unknown
     * @param signedFilenames bullet list of {@code _signed.pdf} filenames
     *                        archived to the employee store; may be empty but not null
     */
    public void notifyHire(RecruitmentCandidate candidate,
                           UUID recruiterUuid,
                           List<String> signedFilenames) {
        if (candidate == null || candidate.getUuid() == null) {
            log.warn("notifyHire: candidate or candidate UUID is null, skipping");
            return;
        }
        // Atomic add-and-check — first caller wins, others no-op.
        if (!NOTIFIED_CANDIDATE_UUIDS.add(candidate.getUuid())) {
            log.debugf("notifyHire: already notified candidate=%s, skipping", candidate.getUuid());
            return;
        }

        try {
            String channel = hrChannel();
            String message = formatMessage(candidate, recruiterUuid,
                    signedFilenames == null ? List.of() : signedFilenames);
            slackService.sendMessageStrict(channel, message, botTokenKey);
            log.infof("HR Slack notification posted for candidate=%s channel=%s",
                    candidate.getUuid(), channel);
        } catch (Exception e) {
            // Never propagate — Slack failure must not affect Convert.
            log.errorf(e, "HR Slack notification failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
    }

    /**
     * Notify HR that {@code candidate} has been hired but that <b>no signed
     * document could be filed</b> — no dossier revision reached a completed
     * signing case, so the promotion had nothing binding to promote (e.g. the
     * contract was signed on paper outside the system).
     *
     * <p>This is the human-in-the-loop half of
     * {@code PromotionStatus.NO_BINDING_DOCUMENTS}: the status makes the state
     * queryable, this makes someone act on it. Shares the hire dedup set, so a
     * candidate is announced once either way.</p>
     *
     * @param candidate     the hired candidate (must not be null)
     * @param recruiterUuid the actor who triggered Convert; may be {@code null}
     */
    public void notifyHireWithoutSignedContract(RecruitmentCandidate candidate, UUID recruiterUuid) {
        if (candidate == null || candidate.getUuid() == null) {
            log.warn("notifyHireWithoutSignedContract: candidate or candidate UUID is null, skipping");
            return;
        }
        if (!NOTIFIED_CANDIDATE_UUIDS.add(candidate.getUuid())) {
            log.debugf("notifyHireWithoutSignedContract: already notified candidate=%s, skipping",
                    candidate.getUuid());
            return;
        }

        try {
            String channel = hrChannel();
            slackService.sendMessageStrict(channel, formatNoBindingDocumentsMessage(candidate, recruiterUuid),
                    botTokenKey);
            log.infof("HR Slack no-signed-contract notification posted for candidate=%s channel=%s",
                    candidate.getUuid(), channel);
        } catch (Exception e) {
            // Never propagate — Slack failure must not affect Convert.
            log.errorf(e, "HR Slack no-signed-contract notification failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
    }

    /**
     * Notify HR that {@code candidate}'s contract documents just went out
     * for signature (the send-signature flow, which appends no stream event
     * — hence a direct call rather than a reactor). A NEW moment created for
     * the offer channel: it posts ONLY when
     * {@code recruitment.slack.channel.offer} is configured, so a blank
     * channel changes nothing anywhere. Partner-track candidates are
     * suppressed by the caller (confidential track — never a shared
     * channel). Not deduped: every send is a real, distinct act (a re-send
     * after a decline is news, not noise).
     *
     * @param candidate     the candidate whose dossier documents were sent
     * @param documentCount how many documents the signing case carries
     * @param signerCount   how many signers the case waits on
     */
    public void notifyContractSent(RecruitmentCandidate candidate,
                                   int documentCount,
                                   int signerCount) {
        if (candidate == null || candidate.getUuid() == null) {
            log.warn("notifyContractSent: candidate or candidate UUID is null, skipping");
            return;
        }
        String channel = router.offerChannel().orElse(null);
        if (channel == null) {
            return; // offer split off — this moment only exists on the offer channel
        }
        try {
            slackService.sendMessageStrict(channel,
                    formatContractSentMessage(candidate, documentCount, signerCount), botTokenKey);
            log.infof("HR Slack contract-sent notification posted for candidate=%s channel=%s",
                    candidate.getUuid(), channel);
        } catch (Exception e) {
            // Never propagate — Slack failure must not affect send-signature.
            log.errorf(e, "HR Slack contract-sent notification failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
    }

    /**
     * Alert HR that a candidate's Outlook interview invitation is in a
     * state automation cannot fix: a permanent Graph error at scheduling
     * time, the repair sweep's retry cap, the interview time passing with
     * the invite still missing, or a cancellation that could not be
     * delivered. This is the operator-visible half of the calendar-retry
     * fix (production 2026-08-24: a Graph 504 dropped a candidate's ONLY
     * invitation with nothing but a WARN — the interview happened the next
     * day with the candidate never formally invited).
     * <p>
     * Not deduped: each call is a distinct terminal fact (the inline
     * permanent failure and a later dead-letter cannot both fire for the
     * same interview — the first never arms the retry marker).
     *
     * @param candidate      the affected candidate; may be null (message
     *                       degrades to the interview reference)
     * @param interview      the interview whose candidate event failed
     * @param problem        one human sentence: what is wrong and what to
     *                       do about it
     * @param reason         the classified Graph error (truncated here)
     * @param graphRequestId Graph's correlation id, when one came back —
     *                       the handle a Microsoft ticket needs
     */
    public void notifyCandidateInviteFailed(RecruitmentCandidate candidate,
                                            RecruitmentInterview interview,
                                            String problem,
                                            String reason,
                                            String graphRequestId) {
        if (interview == null) {
            log.warn("notifyCandidateInviteFailed: interview is null, skipping");
            return;
        }
        try {
            slackService.sendMessageStrict(hrChannel(),
                    formatCandidateInviteFailedMessage(candidate, interview, problem,
                            reason, graphRequestId),
                    botTokenKey);
            log.infof("HR Slack candidate-invite-failed notification posted for interview=%s",
                    interview.getUuid());
        } catch (Exception e) {
            // Never propagate — Slack failure must not affect scheduling.
            log.errorf(e, "HR Slack candidate-invite-failed notification failed for interview=%s: %s",
                    interview.getUuid(), e.getMessage());
        }
    }

    /** Visible for tests — the PII boundary applies (name yes, email no). */
    String formatCandidateInviteFailedMessage(RecruitmentCandidate candidate,
                                              RecruitmentInterview interview,
                                              String problem,
                                              String reason,
                                              String graphRequestId) {
        String candidateName = candidate == null ? "unknown candidate"
                : (nullSafe(candidate.getFirstName()) + " "
                        + nullSafe(candidate.getLastName())).trim();
        StringBuilder sb = new StringBuilder(320);
        sb.append(":warning: *Candidate interview invitation needs a hand* — ")
                .append(candidateName).append('\n');
        if (interview.getScheduledAt() != null) {
            sb.append("Interview: ").append(interview.getScheduledAt()).append('\n');
        }
        sb.append(problem).append('\n');
        if (reason != null && !reason.isBlank()) {
            sb.append("Error: ").append(reason.length() > 300
                    ? reason.substring(0, 300) + "…" : reason).append('\n');
        }
        if (graphRequestId != null && !graphRequestId.isBlank()) {
            sb.append("Graph request-id: ").append(graphRequestId).append('\n');
        }
        if (candidate != null && candidate.getUuid() != null) {
            sb.append(stripTrailingSlash(dossierBaseUrl)).append('/')
                    .append(candidate.getUuid());
        }
        return sb.toString();
    }

    /** Visible for tests — the PII boundary applies (no case key, no signer emails). */
    String formatContractSentMessage(RecruitmentCandidate candidate,
                                     int documentCount,
                                     int signerCount) {
        String candidateName = (nullSafe(candidate.getFirstName()) + " "
                + nullSafe(candidate.getLastName())).trim();
        String dossierUrl = stripTrailingSlash(dossierBaseUrl) + "/" + candidate.getUuid();
        return ":outbox_tray: *Contract sent for signature* — " + candidateName
                + " (" + documentCount + " document" + (documentCount == 1 ? "" : "s")
                + ", " + signerCount + " signer" + (signerCount == 1 ? "" : "s") + ")\n"
                + "Waiting on the candidate's signature — signing status is on the "
                + "Offer & Contract tab.\n" + dossierUrl;
    }

    /**
     * Build the no-signed-contract message body. Package-private for the same
     * reason as {@link #formatMessage}.
     */
    String formatNoBindingDocumentsMessage(RecruitmentCandidate candidate, UUID recruiterUuid) {
        String candidateName = nullSafe(candidate.getFirstName()) + " " + nullSafe(candidate.getLastName());
        String company = resolveCompanyDisplayName(candidate.getTargetCompanyUuid());
        String recruiter = resolveRecruiterName(recruiterUuid);
        String dossierUrl = stripTrailingSlash(dossierBaseUrl) + "/" + candidate.getUuid();

        StringBuilder sb = new StringBuilder(256);
        sb.append("*New hire — no signed contract to file*\n");
        sb.append("Candidate: ").append(candidateName.trim()).append('\n');
        sb.append("Company: ").append(company).append('\n');
        sb.append("Recruiter: ").append(recruiter).append('\n');
        sb.append("Dossier: ").append(dossierUrl).append('\n');
        sb.append("No dossier revision reached a completed signing case, so nothing was filed "
                + "in the employee's documents. If the contract was signed outside the system, "
                + "upload it to their file by hand.");
        return sb.toString();
    }

    /**
     * Build the Slack message body. Visible (package-private) for unit tests
     * to assert PII boundaries directly without booting Slack.
     */
    String formatMessage(RecruitmentCandidate candidate,
                         UUID recruiterUuid,
                         List<String> signedFilenames) {
        String candidateName = nullSafe(candidate.getFirstName()) + " "
                + nullSafe(candidate.getLastName());
        String company = resolveCompanyDisplayName(candidate.getTargetCompanyUuid());
        String recruiter = resolveRecruiterName(recruiterUuid);
        String dossierUrl = stripTrailingSlash(dossierBaseUrl) + "/" + candidate.getUuid();

        StringBuilder sb = new StringBuilder(256);
        sb.append("*New hire — signed contracts archived*\n");
        sb.append("Candidate: ").append(candidateName.trim()).append('\n');
        sb.append("Company: ").append(company).append('\n');
        sb.append("Recruiter: ").append(recruiter).append('\n');
        sb.append("Dossier: ").append(dossierUrl).append('\n');
        sb.append("Signed documents:");
        if (signedFilenames.isEmpty()) {
            sb.append(" (none)");
        } else {
            for (String name : signedFilenames) {
                sb.append("\n• ").append(name);
            }
        }
        return sb.toString();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String resolveCompanyDisplayName(String companyUuid) {
        if (companyUuid == null || companyUuid.isBlank()) return "unknown";
        try {
            Company c = Company.findById(companyUuid);
            if (c != null && c.getName() != null && !c.getName().isBlank()) {
                return c.getName();
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve company name for uuid=%s", companyUuid);
        }
        return "unknown";
    }

    private static String resolveRecruiterName(UUID recruiterUuid) {
        if (recruiterUuid == null) return "unknown";
        try {
            User u = User.findById(recruiterUuid.toString());
            if (u != null) {
                String first = nullSafe(u.getFirstname()).trim();
                String last = nullSafe(u.getLastname()).trim();
                String full = (first + " " + last).trim();
                if (!full.isEmpty()) return full;
                if (u.getUsername() != null && !u.getUsername().isBlank()) {
                    return u.getUsername();
                }
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve recruiter name for uuid=%s", recruiterUuid);
        }
        return "unknown";
    }

    // ── Onboarding-upload completion ───────────────────────────────────────

    /**
     * Notify HR that every required identity document has been uploaded
     * via the public onboarding upload page. Two flavours:
     *
     * <ul>
     *   <li><b>Candidate flow</b> — message names the candidate and links
     *       to the recruitment dossier page.</li>
     *   <li><b>User flow</b> — message names the new hire (full name +
     *       username); HR finds the files on the employee's HR Documents
     *       tab, so no link is attached.</li>
     * </ul>
     *
     * <p>Idempotent across the JVM lifetime per {@code token.uuid}. Slack
     * failures are logged and swallowed so the upload transaction never
     * rolls back.</p>
     *
     * <p>Display name and link URL are pre-resolved by the caller (the
     * upload service has the user / candidate context in hand and runs
     * outside any DB transaction, so the notifier no longer reaches back
     * into Panache). Pass {@code "unknown"} for {@code displayName} or
     * {@code ""} for {@code linkUrl} if the caller could not resolve
     * either — never null.</p>
     *
     * @param token        the onboarding token whose required types are now all submitted
     * @param submissions  the full set of submissions for the token (unused
     *                     for the message body itself but kept for
     *                     forward-compatibility / count metrics)
     * @param displayName  candidate full name OR {@code "Full Name (username)"}
     *                     for the user flow; never null
     * @param linkUrl      candidate dossier URL (candidate flow) or empty
     *                     (user flow); never null
     */
    public void notifyOnboardingComplete(OnboardingUploadToken token,
                                         List<OnboardingUploadSubmission> submissions,
                                         String displayName,
                                         String linkUrl) {
        if (token == null || token.getUuid() == null) {
            log.warn("notifyOnboardingComplete: token or token UUID is null, skipping");
            return;
        }
        if (!NOTIFIED_ONBOARDING_TOKEN_UUIDS.add(token.getUuid())) {
            log.debugf("notifyOnboardingComplete: already notified token=%s, skipping", token.getUuid());
            return;
        }

        try {
            String channel = hrChannel();
            String message = formatOnboardingCompleteMessage(
                    token,
                    submissions == null ? List.of() : submissions,
                    displayName == null ? "unknown" : displayName,
                    linkUrl == null ? "" : linkUrl);
            slackService.sendMessageStrict(channel, message, botTokenKey);
            log.infof("HR Slack onboarding-complete notification posted for token=%s channel=%s",
                    token.getUuid(), channel);
        } catch (Exception e) {
            log.errorf(e, "HR Slack onboarding-complete notification failed for token=%s: %s",
                    token.getUuid(), e.getMessage());
        }
    }

    /** Visible for tests. */
    String formatOnboardingCompleteMessage(OnboardingUploadToken token,
                                           List<OnboardingUploadSubmission> submissions,
                                           String displayName,
                                           String linkUrl) {
        int count = submissions.size();
        if (token.getCandidateUuid() != null) {
            return ":file_folder: Candidate " + displayName
                    + " has uploaded all required onboarding identity documents ("
                    + count + " file(s)). " + linkUrl;
        }
        // User flow: the files land in the employee document store.
        return ":file_folder: " + displayName
                + " has uploaded all required onboarding identity documents ("
                + count + " file(s)). " + linkUrl;
    }
}
