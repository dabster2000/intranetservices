package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableReferrerMatcher;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

/**
 * Resolves the public form's {@code KNOWS_SOMEONE} answer — a free-text
 * name typed by an anonymous applicant — to a Trustworks employee (change
 * request (e), 2026-09-01).
 * <p>
 * There is deliberately no second matcher: this delegates to
 * {@link AirtableReferrerMatcher}, which has been doing exactly this for
 * the Airtable import since P21 (exact full-name match → unique
 * first+last-token match → optional AI extraction of names from prose).
 * Its guarantees are the reason it was reused rather than re-written: it
 * never guesses on ambiguity, and the model never sees the directory and
 * never picks a uuid, so a hallucination can only fail to match — it can
 * never invent a link to a colleague.
 *
 * <h3>Why this is not part of the submit transaction</h3>
 * The AI tier is an OpenAI round-trip. Running it inside
 * {@code PublicApplyService.submitForPosition} would hold a pooled DB
 * connection open across a network call on an unauthenticated endpoint —
 * the M1 rule the Airtable importer already respects by resolving before
 * its transaction ({@code AirtableImportService}), and the same three-phase
 * shape as {@code AiIntakeGenerationService.generateUntransacted}. So the
 * directory read happens in its own short transaction that <em>completes</em>,
 * and the matching then runs with no transaction at all.
 *
 * <h3>Failure posture</h3>
 * Matching never fails a submission. Anything unexpected — a matcher
 * error, an active transaction, OpenAI trouble — degrades to
 * {@link ReferrerClaim#unmatched(String)}: the applicant's text is kept as
 * the external referrer name, no link is made, and the applicant still
 * gets the same generic 201. Losing an application because a name lookup
 * misbehaved would be the far worse failure.
 */
@JBossLog
@ApplicationScoped
public class PublicApplyReferrerService {

    /**
     * The outcome of matching one applicant claim.
     *
     * @param claimedName    the applicant's text, trimmed and capped, or
     *                       {@code null} when nothing was claimed
     * @param matchedUserUuid the employee this resolved to, or {@code null}
     *                       when nothing matched confidently
     * @param matchMethod    {@code "name"} or {@code "ai_extraction"} from
     *                       {@link AirtableReferrerMatcher}, {@code null}
     *                       when unmatched — stamped on the event for
     *                       auditability
     */
    public record ReferrerClaim(String claimedName, String matchedUserUuid, String matchMethod) {

        /** Nothing was claimed (question not asked, or left blank). */
        public static final ReferrerClaim NONE = new ReferrerClaim(null, null, null);

        static ReferrerClaim unmatched(String claimedName) {
            return new ReferrerClaim(claimedName, null, null);
        }

        /** True when the applicant named someone, matched or not. */
        public boolean isPresent() {
            return claimedName != null;
        }

        /** True when the name resolved to exactly one employee. */
        public boolean isMatched() {
            return matchedUserUuid != null;
        }
    }

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    AirtableReferrerMatcher referrerMatcher;

    /**
     * Kill switch for the matcher's AI tier alone, independent of the
     * feature flag: the deterministic tiers keep working while the OpenAI
     * leg is off. It exists because the AI tier is reachable by anonymous
     * callers — an attacker who types gibberish always misses tiers 1–2 and
     * would otherwise buy an OpenAI call per (rate-limited) submission.
     * <p>
     * Defaults to <strong>false</strong> (security review 2026-09-01, F4).
     * The deterministic tiers cost nothing and catch the ordinary case — a
     * name typed the way the colleague's user row spells it. Tier 3 is the
     * only leg an anonymous caller can force us to pay for: gibberish always
     * misses tiers 1–2, so a default-on AI tier would mean the first flag
     * flip silently opened a metered OpenAI endpoint to the internet. It
     * also widens the timing gap between a matched and an unmatched name
     * (F1), so leaving it off keeps that oracle narrow. Turn it on
     * deliberately, after watching real volume.
     */
    @ConfigProperty(name = "dk.trustworks.recruitment.apply.referrer-ai-extraction.enabled",
            defaultValue = "false")
    boolean aiExtractionEnabled;

    /**
     * Resolve the applicant's claim. MUST be called before the submit
     * transaction opens.
     *
     * @param claimedName the raw {@code KNOWS_SOMEONE} answer (already
     *                    trimmed and length-capped by the resource), or null
     * @return never null; {@link ReferrerClaim#NONE} when the flag is off or
     *         nothing was claimed
     */
    public ReferrerClaim resolve(String claimedName) {
        if (!featureFlag.isApplyReferrerClaimEnabled()) {
            return ReferrerClaim.NONE;
        }
        String claim = trimToNull(claimedName);
        if (claim == null) {
            return ReferrerClaim.NONE;
        }
        try {
            if (QuarkusTransaction.isActive()) {
                // A surrounding tx would be held across the OpenAI round-trip —
                // exactly the posture this service exists to avoid. Degrade
                // rather than 500 an applicant's submission.
                log.error("[PublicApplyReferrerService] resolve() called inside a transaction — "
                        + "keeping the claim as an external referrer name and skipping the match");
                return ReferrerClaim.unmatched(claim);
            }
            // Phase 1 — the directory read, in its own transaction that
            // completes before any network call. NOTE: must be an explicit
            // call in the lambda; a method reference (User::listAll) bypasses
            // Panache's build-time enhancement.
            List<AirtableReferrerMatcher.DirectoryUser> directory =
                    QuarkusTransaction.requiringNew().call(this::loadDirectory);
            // Phase 2 — matching, untransacted.
            return match(claim, directory);
        } catch (Exception e) {
            // Never lose an application over a name lookup.
            log.warnf("[PublicApplyReferrerService] referrer matching failed (%s) — "
                    + "keeping the claim as an external referrer name", e.getClass().getSimpleName());
            return ReferrerClaim.unmatched(claim);
        }
    }

    /**
     * The matching itself — pure given the directory, and package-private so
     * the tier behaviour (exact, unique-token, ambiguity-never-guesses) is
     * pinned without a database. Tiers 1–2 cost nothing; tier 3 is the
     * OpenAI call and only runs when the deterministic tiers miss.
     */
    ReferrerClaim match(String claim, List<AirtableReferrerMatcher.DirectoryUser> directory) {
        AirtableReferrerMatcher.Resolution resolution =
                referrerMatcher.resolve(claim, directory, aiExtractionEnabled);
        if (resolution.userUuid() == null) {
            return ReferrerClaim.unmatched(claim);
        }
        return new ReferrerClaim(claim, resolution.userUuid(), resolution.matchMethod());
    }

    /** The employee directory, reduced to the three fields the matcher needs. */
    List<AirtableReferrerMatcher.DirectoryUser> loadDirectory() {
        return User.<User>listAll().stream()
                .map(user -> new AirtableReferrerMatcher.DirectoryUser(
                        user.getUuid(), user.getFirstname(), user.getLastname()))
                .toList();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
