package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-off repair for the referrer names we have been collecting and never
 * showing (change request (e) follow-up, 2026-09-01).
 *
 * <h3>What went wrong</h3>
 * The public form has asked "Hvem henviste dig?" for months — but only as a
 * follow-up to the NETWORK source, and the answer was written into the
 * {@code source_detail} JSON blob and nowhere else. Nothing read it. So a
 * candidate like the reported {@code WEBSITE} applicant whose blob says
 * {@code {"selfReportedSource":"NETWORK","referenceName":"…"}} has a
 * perfectly good referrer that no surface, filter or count could see, and
 * {@code referred_by_user_uuid} is NULL.
 *
 * <h3>What this does</h3>
 * Reads those names out of the blob and, where one resolves CONFIDENTLY to
 * a current user row, fills {@code referred_by_user_uuid} — the column the
 * profile, the referral list and any future filter already understand.
 *
 * <h3>What it deliberately does NOT do</h3>
 * <ul>
 *   <li><b>It never notifies.</b> The claims are historical; DM-ing a
 *       colleague about an application from months ago would be noise at
 *       best and alarming at worst. Suppression is structural rather than a
 *       flag: the event appended here is
 *       {@link RecruitmentEventType#APPLICANT_REFERRER_BACKFILLED}, and
 *       {@code ApplicantReferrerNotificationReactor} only ever acts on
 *       {@code APPLICANT_REFERRER_CLAIMED}. There is no code path from this
 *       service to Slack, so a later refactor cannot accidentally open one.</li>
 *   <li><b>It never writes {@code external_referrer_name}.</b> An unmatched
 *       name is already visible on the profile straight from
 *       {@code source_detail} ("Named by the applicant"), so copying it into
 *       the referrer column would show the same string twice while implying
 *       a link we did not make.</li>
 *   <li><b>It never guesses.</b> The matching is
 *       {@link AirtableReferrerMatcher}'s, unchanged: exact full name, then a
 *       unique first+last token hit, and nothing on ambiguity.</li>
 *   <li><b>It never touches a row that already has an answer</b> — a
 *       non-null {@code referred_by_user_uuid} or {@code
 *       external_referrer_name} is left exactly as it is, so a recruiter's
 *       manual entry always wins and re-running changes nothing.</li>
 *   <li><b>It skips ANONYMIZED candidates.</b> P19 erasure blanks
 *       {@code source_detail} precisely because reference names live in it;
 *       a repair job that read erased PII back out would undo an erasure.</li>
 * </ul>
 *
 * <h3>Not gated by the feature flag</h3>
 * {@code recruitment.apply.referrer-claim.enabled} gates asking new
 * applicants, matching their answers, storing them and notifying the named
 * colleague — the things that need the rewritten privacy notice first. This
 * job asks nobody anything and tells nobody anything: it re-reads data
 * already lawfully collected under the existing notice and moves it into the
 * column that can display it. So it is admin-triggered rather than
 * flag-gated, and can be run before the flag is ever flipped.
 */
@JBossLog
@ApplicationScoped
public class PublicApplyReferrerBackfillService {

    /** The key the public form has always written into {@code source_detail}. */
    static final String REFERENCE_NAME_KEY = "referenceName";

    @Inject
    AirtableReferrerMatcher referrerMatcher;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    /**
     * Outcome of one run. {@code scanned} counts rows that carried a usable
     * name and were eligible to change; the three others partition it.
     *
     * @param scanned    rows with a non-blank reference name and no referrer yet
     * @param matched    rows a name resolved for (and, when not a dry run, wrote)
     * @param unmatched  names that hit no user row at all
     * @param ambiguous  names the matcher refused to resolve rather than guess
     * @param dryRun     true when nothing was written
     * @param examples   a few "typed name → outcome" lines, for eyeballing
     */
    public record BackfillReport(int scanned, int matched, int unmatched, int ambiguous,
                                 boolean dryRun, List<String> examples) {
    }

    /** One candidate's pending change, resolved before any write happens. */
    private record Pending(String candidateUuid, String claimedName, String matchedUserUuid,
                           String matchMethod) {
    }

    /**
     * Run the backfill.
     * <p>
     * Three phases on purpose, the same shape as the live path: read in a
     * transaction that COMMITS, match with no transaction held (the matcher's
     * AI tier is an OpenAI round-trip and must never run with a pooled
     * connection open), then write. Matching is deterministic-only here —
     * {@code useAi=false} — because a historical sweep over every candidate
     * is exactly the shape that turns a per-row model call into a surprise
     * invoice, and the deterministic tiers are what a typed colleague name
     * hits anyway.
     *
     * @param dryRun when true, resolve and report but write nothing
     */
    public BackfillReport backfill(boolean dryRun, UUID actor) {
        List<AirtableReferrerMatcher.DirectoryUser> directory =
                QuarkusTransaction.requiringNew().call(this::loadDirectory);
        List<Candidate> candidates =
                QuarkusTransaction.requiringNew().call(this::loadCandidatesWithUnusedClaim);

        List<Pending> pending = new ArrayList<>();
        int unmatched = 0;
        int ambiguous = 0;
        List<String> examples = new ArrayList<>();
        for (Candidate candidate : candidates) {
            AirtableReferrerMatcher.Resolution resolution =
                    referrerMatcher.resolve(candidate.claimedName(), directory, false);
            if (resolution.userUuid() != null) {
                pending.add(new Pending(candidate.uuid(), candidate.claimedName(),
                        resolution.userUuid(), resolution.matchMethod()));
                addExample(examples, candidate.claimedName(), "matched");
            } else if (isAmbiguous(candidate.claimedName(), directory)) {
                ambiguous++;
                addExample(examples, candidate.claimedName(), "ambiguous — left alone");
            } else {
                unmatched++;
                addExample(examples, candidate.claimedName(), "no employee of that name");
            }
        }

        if (!dryRun && !pending.isEmpty()) {
            QuarkusTransaction.requiringNew().run(() -> writeAll(pending, actor));
        }
        log.infof("Referrer backfill (%s): %d scanned, %d matched, %d unmatched, %d ambiguous",
                dryRun ? "dry run" : "applied", candidates.size(), pending.size(),
                unmatched, ambiguous);
        return new BackfillReport(candidates.size(), pending.size(), unmatched, ambiguous,
                dryRun, List.copyOf(examples));
    }

    /** A candidate row reduced to what the match needs. */
    private record Candidate(String uuid, String claimedName) {
    }

    /**
     * Candidates carrying a reference name that never became a link.
     * <p>
     * The {@code source_detail} filtering is done in Java rather than SQL:
     * the column is JSON, MariaDB's JSON support across the versions this
     * runs on is not uniform, and the row count here is small (public-form
     * candidates only). Correctness beats a clever predicate.
     */
    List<Candidate> loadCandidatesWithUnusedClaim() {
        List<RecruitmentCandidate> rows = RecruitmentCandidate
                .list("referredByUserUuid is null and externalReferrerName is null "
                        + "and sourceDetail is not null");
        List<Candidate> out = new ArrayList<>();
        for (RecruitmentCandidate row : rows) {
            if (row.getStatus() == CandidateStatus.ANONYMIZED) {
                continue; // erased PII must not be read back out
            }
            String claimed = claimedNameOf(row.getSourceDetail());
            if (claimed != null) {
                out.add(new Candidate(row.getUuid(), claimed));
            }
        }
        return out;
    }

    /**
     * The applicant's typed name, or null. Defensive about the blob: it is
     * untyped JSON written by several generations of the form, so anything
     * that is not a non-blank string is treated as absent.
     */
    static String claimedNameOf(Map<String, Object> sourceDetail) {
        if (sourceDetail == null) {
            return null;
        }
        Object raw = sourceDetail.get(REFERENCE_NAME_KEY);
        if (!(raw instanceof String s)) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Whether the matcher declined because the name fits more than one
     * colleague, as opposed to nobody. Only used to make the report
     * honest — an ambiguous name is a person a human could resolve in
     * seconds, and lumping it in with "no such employee" would hide that.
     */
    private boolean isAmbiguous(String claimedName,
                                List<AirtableReferrerMatcher.DirectoryUser> directory) {
        String normalised = claimedName.toLowerCase().trim();
        long exact = directory.stream()
                .filter(u -> fullName(u).equals(normalised))
                .count();
        return exact > 1;
    }

    private static String fullName(AirtableReferrerMatcher.DirectoryUser user) {
        return ((user.firstname() == null ? "" : user.firstname()) + " "
                + (user.lastname() == null ? "" : user.lastname())).toLowerCase().trim();
    }

    private static void addExample(List<String> examples, String claimedName, String outcome) {
        if (examples.size() < 20) {
            examples.add(claimedName + " → " + outcome);
        }
    }

    /**
     * Write the resolved links and append one audit event each.
     * <p>
     * The event is the point as much as the column is: without it, a link
     * made by this sweep would be indistinguishable from a referral the
     * colleague actually submitted. {@code origin=BACKFILL} and the typed
     * name in pii say exactly what happened and what it was derived from.
     */
    void writeAll(List<Pending> pending, UUID actor) {
        for (Pending item : pending) {
            RecruitmentCandidate candidate = RecruitmentCandidate.findById(item.candidateUuid());
            if (candidate == null || candidate.getReferredByUserUuid() != null
                    || candidate.getExternalReferrerName() != null) {
                continue; // changed under us between the read and the write
            }
            candidate.setReferredByUserUuid(item.matchedUserUuid());
            RecruitmentCandidate.persist(candidate);
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.APPLICANT_REFERRER_BACKFILLED)
                    .candidate(item.candidateUuid())
                    .actorUser(actor.toString())
                    .payload("matched_user_uuid", item.matchedUserUuid())
                    .payload("match_method", item.matchMethod())
                    .payload("origin", "BACKFILL")
                    .pii("claimed_name", item.claimedName()));
        }
    }

    /** The directory the matcher needs — explicit lambda body, not a method
     * reference: {@code User::listAll} bypasses Panache's build-time
     * enhancement. */
    List<AirtableReferrerMatcher.DirectoryUser> loadDirectory() {
        return User.<User>listAll().stream()
                .map(user -> new AirtableReferrerMatcher.DirectoryUser(
                        user.getUuid(), user.getFirstname(), user.getLastname()))
                .toList();
    }
}
