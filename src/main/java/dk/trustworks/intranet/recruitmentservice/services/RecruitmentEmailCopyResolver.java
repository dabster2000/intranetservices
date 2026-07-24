package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentEmailCopyRole;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentInterviewStatus;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentVisibility;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves a template's copy policy into actual people (ATS plan §P15
 * follow-up — the email loose-ends round, 2026-07-24).
 *
 * <h3>Why this is a separate service</h3>
 * Copying colleagues on a candidate email is an authorization decision,
 * not a formatting one. Every resolved person is run through
 * {@link RecruitmentVisibility#canReadCandidateProfile} before their
 * address is allowed near the outbox, so a copy can never disclose a
 * candidate the recipient is not authorized to read — the fail-closed
 * posture the module already applies to timelines (§P10 MEDIUM-2) and
 * queue rows (§P15), now applied to the mail header. A partner-track
 * candidate's rejection therefore never BCCs someone outside the circle,
 * however the template is configured.
 *
 * <h3>What resolves to nobody</h3>
 * Each source degrades silently and independently:
 * <ul>
 *   <li>{@code INTERVIEWERS} before the first interview exists (so
 *       acknowledgements and screening rejections copy no panel — nobody
 *       has met the candidate yet);</li>
 *   <li>{@code SENDER} on the reactor's automatic sends (no human acted);</li>
 *   <li>{@code HIRING_OWNER} when the email has no position context, or
 *       the position has no owner set.</li>
 * </ul>
 * An empty result is normal and never an error — the email still goes to
 * the candidate.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentEmailCopyResolver {

    /**
     * Hard ceiling on copies per email. Well above any real interview
     * panel; exists so a misconfigured position cannot produce a mail
     * header longer than the {@code cc}/{@code bcc} columns.
     */
    public static final int MAX_COPIES = 20;

    /**
     * Width of {@code mail.cc} / {@code mail.bcc} (and of
     * {@code recruitment_pending_emails.copy_user_uuids}). Keep in step
     * with the V455 column definitions.
     */
    public static final int ADDRESS_LIST_MAX_LENGTH = 1000;

    @Inject
    RecruitmentVisibility visibility;

    /**
     * One resolved copy recipient. {@code source} is the role that
     * produced them — the compose dialog labels the checkbox with it
     * ("Interviewer", "Hiring owner", "You").
     */
    public record CopyRecipient(String userUuid, String name, String email,
                                RecruitmentEmailCopyRole source) {
    }

    /**
     * Resolve a role set to visibility-filtered people.
     *
     * @param candidate       the email's recipient candidate
     * @param applicationUuid optional application context — scopes
     *                        {@code INTERVIEWERS} to that application's
     *                        interviews; null widens to all of the
     *                        candidate's applications
     * @param roles           the template's copy policy
     * @param actorUserUuid   the recruiter pressing Send, or null on the
     *                        reactor path (then {@code SENDER} yields
     *                        nobody)
     * @return recipients in a stable order, deduplicated by user, never null
     */
    public List<CopyRecipient> resolve(RecruitmentCandidate candidate,
                                       String applicationUuid,
                                       Set<RecruitmentEmailCopyRole> roles,
                                       String actorUserUuid) {
        if (candidate == null || roles == null || roles.isEmpty()) {
            return List.of();
        }
        // Deduplicate by user, keeping the FIRST source that produced
        // them — enum order makes "Interviewer" win over "Hiring owner"
        // for someone who is both, which is the more informative label.
        Map<String, CopyRecipient> byUser = new LinkedHashMap<>();
        for (RecruitmentEmailCopyRole role : RecruitmentEmailCopyRole.values()) {
            if (!roles.contains(role)) {
                continue;
            }
            for (String userUuid : userUuidsFor(role, candidate, applicationUuid, actorUserUuid)) {
                if (userUuid == null || userUuid.isBlank() || byUser.containsKey(userUuid)) {
                    continue;
                }
                toRecipient(userUuid, role, candidate).ifPresent(r -> byUser.put(userUuid, r));
            }
        }
        List<CopyRecipient> resolved = new ArrayList<>(byUser.values());
        if (resolved.size() > MAX_COPIES) {
            log.warnf("Copy list for candidate %s resolved to %d people — truncating to %d",
                    candidate.getUuid(), resolved.size(), MAX_COPIES);
            resolved = resolved.subList(0, MAX_COPIES);
        }
        return List.copyOf(resolved);
    }

    /**
     * Everyone the compose dialog may offer for this candidate — the union
     * of all three sources, regardless of the template's policy, so the
     * recruiter can add a person the template does not copy by default.
     * Same visibility filter as {@link #resolve}.
     */
    public List<CopyRecipient> eligiblePool(RecruitmentCandidate candidate,
                                            String applicationUuid,
                                            String actorUserUuid) {
        return resolve(candidate, applicationUuid,
                Set.of(RecruitmentEmailCopyRole.values()), actorUserUuid);
    }

    /**
     * Resolve caller-supplied user uuids (the per-send override) to
     * recipients. Anything the caller may not copy — an unknown user, a
     * user without an address, or a user who cannot read this candidate —
     * is dropped silently rather than failing the send: the email to the
     * candidate matters more than a copy.
     */
    public List<CopyRecipient> resolveExplicit(RecruitmentCandidate candidate,
                                               List<String> userUuids) {
        if (candidate == null || userUuids == null || userUuids.isEmpty()) {
            return List.of();
        }
        Map<String, CopyRecipient> byUser = new LinkedHashMap<>();
        for (String userUuid : userUuids) {
            if (userUuid == null || userUuid.isBlank() || byUser.containsKey(userUuid)) {
                continue;
            }
            if (byUser.size() >= MAX_COPIES) {
                log.warnf("Explicit copy list for candidate %s exceeded %d — ignoring the rest",
                        candidate.getUuid(), MAX_COPIES);
                break;
            }
            toRecipient(userUuid.trim(), null, candidate).ifPresent(r -> byUser.put(userUuid, r));
        }
        return List.copyOf(new ArrayList<>(byUser.values()));
    }

    // ------------------------------------------------------------------
    // Sources
    // ------------------------------------------------------------------

    private List<String> userUuidsFor(RecruitmentEmailCopyRole role,
                                      RecruitmentCandidate candidate,
                                      String applicationUuid,
                                      String actorUserUuid) {
        return switch (role) {
            case INTERVIEWERS -> interviewerUuids(candidate, applicationUuid);
            case SENDER -> actorUserUuid == null || actorUserUuid.isBlank()
                    ? List.of() : List.of(actorUserUuid);
            case HIRING_OWNER -> hiringOwnerUuid(candidate, applicationUuid);
        };
    }

    /**
     * Assigned interviewers on non-cancelled interviews. Scoped to one
     * application when the email has that context; otherwise every
     * application the candidate holds (an email with no application
     * context is about the person, not one process).
     */
    private List<String> interviewerUuids(RecruitmentCandidate candidate, String applicationUuid) {
        List<String> applicationUuids = applicationUuid != null && !applicationUuid.isBlank()
                ? List.of(applicationUuid)
                : RecruitmentApplication
                        .<RecruitmentApplication>list("candidateUuid", candidate.getUuid())
                        .stream().map(RecruitmentApplication::getUuid).toList();
        if (applicationUuids.isEmpty()) {
            return List.of();
        }
        List<RecruitmentInterview> interviews = RecruitmentInterview.list(
                "applicationUuid in ?1 and status != ?2",
                applicationUuids, RecruitmentInterviewStatus.CANCELLED);
        Set<String> uuids = new LinkedHashSet<>();
        for (RecruitmentInterview interview : interviews) {
            if (interview.getInterviewerUuids() != null) {
                uuids.addAll(interview.getInterviewerUuids());
            }
        }
        return List.copyOf(uuids);
    }

    private List<String> hiringOwnerUuid(RecruitmentCandidate candidate, String applicationUuid) {
        RecruitmentPosition position = positionFor(candidate, applicationUuid);
        if (position == null || position.getHiringOwnerUuid() == null
                || position.getHiringOwnerUuid().isBlank()) {
            return List.of();
        }
        return List.of(position.getHiringOwnerUuid());
    }

    private RecruitmentPosition positionFor(RecruitmentCandidate candidate, String applicationUuid) {
        if (applicationUuid == null || applicationUuid.isBlank()) {
            return null;
        }
        RecruitmentApplication application = RecruitmentApplication.findById(applicationUuid);
        if (application == null || !application.getCandidateUuid().equals(candidate.getUuid())) {
            return null;
        }
        return RecruitmentPosition.findById(application.getPositionUuid());
    }

    // ------------------------------------------------------------------
    // Visibility gate + shaping
    // ------------------------------------------------------------------

    /**
     * The load-bearing step: a person only becomes a recipient when they
     * exist, have an address, and may read this candidate's profile.
     */
    private Optional<CopyRecipient> toRecipient(String userUuid,
                                                          RecruitmentEmailCopyRole source,
                                                          RecruitmentCandidate candidate) {
        User user = User.findById(userUuid);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return Optional.empty();
        }
        if (!visibility.canReadCandidateProfile(userUuid, candidate)) {
            log.debugf("Copy recipient %s dropped for candidate %s — not authorized to read the profile",
                    userUuid, candidate.getUuid());
            return Optional.empty();
        }
        String email = user.getEmail().trim();
        if (candidate.getEmail() != null
                && candidate.getEmail().trim().equalsIgnoreCase(email)) {
            return Optional.empty(); // never copy the candidate onto their own email
        }
        return Optional.of(new CopyRecipient(userUuid, displayName(user), email, source));
    }

    private static String displayName(User user) {
        String first = user.getFirstname() == null ? "" : user.getFirstname().trim();
        String last = user.getLastname() == null ? "" : user.getLastname().trim();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? user.getUsername() == null ? "" : user.getUsername() : name;
    }

    /**
     * Comma-separated addresses for the {@code mail} row; null when empty.
     * <p>
     * Bounded by {@link #ADDRESS_LIST_MAX_LENGTH}: recipients that would
     * overflow the column are dropped with a warning rather than allowed
     * to fail the INSERT. Under {@code STRICT_TRANS_TABLES} an over-long
     * value is error 1406, which would roll back the whole send — losing
     * the candidate's email to save a copy. Dropping the tail keeps the
     * email going out, and the log says who missed it.
     */
    public static String addressesOf(List<CopyRecipient> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int dropped = 0;
        for (CopyRecipient recipient : recipients) {
            int addedLength = recipient.email().length() + (sb.isEmpty() ? 0 : 1);
            if (sb.length() + addedLength > ADDRESS_LIST_MAX_LENGTH) {
                dropped++;
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(recipient.email());
        }
        if (dropped > 0) {
            log.warnf("Copy address list exceeded %d characters — %d recipient(s) dropped",
                    ADDRESS_LIST_MAX_LENGTH, dropped);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Comma-separated user uuids for the queue snapshot; null when empty.
     * Bounded like {@link #addressesOf} — same column width, same reason.
     */
    public static String userUuidsOf(List<CopyRecipient> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (CopyRecipient recipient : recipients) {
            int addedLength = recipient.userUuid().length() + (sb.isEmpty() ? 0 : 1);
            if (sb.length() + addedLength > ADDRESS_LIST_MAX_LENGTH) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(',');
            }
            sb.append(recipient.userUuid());
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /** Split a stored uuid CSV back into a list; empty for null/blank. */
    public static List<String> splitUserUuids(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }
}
