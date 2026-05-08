package dk.trustworks.intranet.recruitmentservice.notifications;

import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
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
 * SharePoint copy completes successfully on Convert. Idempotent across the
 * JVM lifetime via an in-memory candidate-UUID dedup set, so the
 * SharePoint retry batchlet cannot trigger a second notification.
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
 * caller's transactional flow ({@code applySharePointResult}) is not
 * affected.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentHrSlackNotifier {

    /**
     * In-memory dedup set, JVM-lifetime. Mirrors the pattern used by
     * {@link RecruitmentSignatureCompletionListener#NOTIFIED_CASE_KEYS} but
     * keyed on candidate UUID — the SharePoint retry batchlet may cause the
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

    @ConfigProperty(name = "recruitment.hr.slack.channel-id", defaultValue = "C0B1XUB3AEB")
    String channelId;

    @ConfigProperty(name = "recruitment.hr.slack.bot-token-key", defaultValue = "mother")
    String botTokenKey;

    @ConfigProperty(name = "recruitment.hr.slack.dossier-base-url",
            defaultValue = "https://intra.trustworks.dk/recruitment/candidates")
    String dossierBaseUrl;

    /**
     * Notify HR that {@code candidate} has been hired and their signed
     * documents have landed in SharePoint. No-ops if a notification was
     * already sent for this candidate UUID in the current JVM.
     *
     * @param candidate       the hired candidate (must not be null)
     * @param recruiterUuid   the actor (recruiter) who triggered Convert; may
     *                        be {@code null} if unknown
     * @param signedFilenames bullet list of {@code _signed.pdf} filenames
     *                        archived to SharePoint; may be empty but not null
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
            String message = formatMessage(candidate, recruiterUuid,
                    signedFilenames == null ? List.of() : signedFilenames);
            slackService.sendMessage(channelId, message, botTokenKey);
            log.infof("HR Slack notification posted for candidate=%s channel=%s",
                    candidate.getUuid(), channelId);
        } catch (Exception e) {
            // Never propagate — Slack failure must not affect Convert.
            log.errorf(e, "HR Slack notification failed for candidate=%s: %s",
                    candidate.getUuid(), e.getMessage());
        }
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
     *       username) and links to their SharePoint onboarding folder via
     *       the most recent submission's {@code webUrl}.</li>
     * </ul>
     *
     * <p>Idempotent across the JVM lifetime per {@code token.uuid}. Slack
     * failures are logged and swallowed so the upload transaction never
     * rolls back.</p>
     */
    public void notifyOnboardingComplete(OnboardingUploadToken token,
                                         List<OnboardingUploadSubmission> submissions) {
        if (token == null || token.getUuid() == null) {
            log.warn("notifyOnboardingComplete: token or token UUID is null, skipping");
            return;
        }
        if (!NOTIFIED_ONBOARDING_TOKEN_UUIDS.add(token.getUuid())) {
            log.debugf("notifyOnboardingComplete: already notified token=%s, skipping", token.getUuid());
            return;
        }

        try {
            String message = formatOnboardingCompleteMessage(token,
                    submissions == null ? List.of() : submissions);
            slackService.sendMessage(channelId, message, botTokenKey);
            log.infof("HR Slack onboarding-complete notification posted for token=%s channel=%s",
                    token.getUuid(), channelId);
        } catch (Exception e) {
            log.errorf(e, "HR Slack onboarding-complete notification failed for token=%s: %s",
                    token.getUuid(), e.getMessage());
        }
    }

    /** Visible for tests. */
    String formatOnboardingCompleteMessage(OnboardingUploadToken token,
                                           List<OnboardingUploadSubmission> submissions) {
        int count = submissions.size();
        if (token.getCandidateUuid() != null) {
            String candidateName = resolveCandidateName(token.getCandidateUuid());
            String dossierUrl = stripTrailingSlash(dossierBaseUrl) + "/" + token.getCandidateUuid();
            return ":file_folder: Candidate " + candidateName
                    + " has uploaded all required onboarding identity documents ("
                    + count + " file(s)). " + dossierUrl;
        }
        // User flow.
        String userUuid = token.getUserUuid();
        String fullName = "unknown";
        String username = "unknown";
        if (userUuid != null) {
            try {
                User u = User.findById(userUuid);
                if (u != null) {
                    String first = nullSafe(u.getFirstname()).trim();
                    String last = nullSafe(u.getLastname()).trim();
                    String composed = (first + " " + last).trim();
                    if (!composed.isEmpty()) fullName = composed;
                    if (u.getUsername() != null && !u.getUsername().isBlank()) {
                        username = u.getUsername();
                    }
                }
            } catch (RuntimeException e) {
                log.debugf(e, "Could not resolve user for uuid=%s", userUuid);
            }
        }
        String folderUrl = "";
        for (int i = submissions.size() - 1; i >= 0; i--) {
            String url = submissions.get(i).getSharepointWebUrl();
            if (url != null && !url.isBlank()) {
                folderUrl = url;
                break;
            }
        }
        return ":file_folder: " + fullName + " (" + username
                + ") has uploaded all required onboarding identity documents to SharePoint. "
                + folderUrl;
    }

    private static String resolveCandidateName(String candidateUuid) {
        if (candidateUuid == null) return "unknown";
        try {
            RecruitmentCandidate c = RecruitmentCandidate.findById(candidateUuid);
            if (c != null) {
                String full = (nullSafe(c.getFirstName()) + " " + nullSafe(c.getLastName())).trim();
                if (!full.isEmpty()) return full;
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve candidate name for uuid=%s", candidateUuid);
        }
        return "unknown";
    }
}
