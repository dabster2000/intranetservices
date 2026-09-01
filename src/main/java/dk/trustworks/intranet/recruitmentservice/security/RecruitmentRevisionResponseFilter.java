package dk.trustworks.intranet.recruitmentservice.security;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SendSignatureResponse;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import lombok.extern.jbosslog.JBossLog;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Recruitment-scoped {@link ContainerResponseFilter} that redacts sensitive
 * placeholder values from {@link RevisionResponse} bodies before they reach
 * the wire. Activated only on resources annotated with
 * {@link RecruitmentSecuredResponse}, so this filter never touches
 * {@code /users}, {@code /invoices}, {@code /contracts}, etc.
 *
 * <h3>Why a recruitment-specific filter?</h3>
 * Spec §8.2 mandates that revision snapshot JSON may legally contain CPR or
 * salary fields when a Word template references them, but those values must
 * not leak to callers without {@code users:read}. The existing
 * {@code UserScopeResponseFilter} works on {@code User} entities and would
 * be the wrong vehicle: revisions are not Users, and bloating the global
 * filter with recruitment-specific JSON walking would harm readability for
 * the rest of the codebase.
 *
 * <h3>Behavior</h3>
 * For every {@link RevisionResponse} found in the entity (direct, in a
 * {@link Collection}, or embedded in a {@link CandidateResponse#latestRevision()}-
 * style record), the filter rebuilds the {@code placeholderValuesSnapshot}
 * map: any key matching the case-insensitive regex
 * {@code (?i).*(cpr|salary|salar|løn|lon|pension|wage|gehalt).*} has its
 * value replaced with the literal string {@code [REDACTED]}. Other keys are
 * preserved verbatim. The filter is a no-op when the caller holds
 * {@code users:read} (or {@code admin:*}, which augments to all scopes).
 *
 * <h3>Why a record-rebuild instead of mutation?</h3>
 * Java records are immutable; mutating a {@code RevisionResponse} after it
 * leaves the resource is impossible. The filter therefore replaces the
 * top-level entity on the response context with a redacted copy, leaving
 * the in-memory cache / JPA-level state untouched.
 */
@JBossLog
@Provider
@RecruitmentSecuredResponse
@Priority(Priorities.ENTITY_CODER)
public class RecruitmentRevisionResponseFilter implements ContainerResponseFilter {

    /**
     * Sensitive placeholder key matcher.
     * <p>
     * Audit notes (per spec §8.2): {@code cpr} catches Danish CPR-number
     * placeholders; {@code salary}/{@code salar} catches English {@code salary},
     * {@code salaries}, and accidentally-spelled {@code salar...}; {@code løn}
     * and {@code lon} catch the Danish word for salary; {@code pension},
     * {@code wage}, and {@code gehalt} catch English/German money-comp keys
     * commonly seen in employment contracts. The regex is intentionally
     * conservative — we'd rather over-redact a "salary review meeting" key
     * than leak compensation data.
     */
    // (?u) enables Unicode-aware case folding so e.g. uppercase Ø matches
    // the lowercase ø in `løn`. Without it, `(?i)` only folds ASCII A-Z.
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?iu).*(cpr|salary|salar|løn|lon|pension|wage|gehalt).*");

    /**
     * Additional money-shaped keys used by the clause parameter convention
     * (template-clauses spec §4.3), applied to clause parameter values only
     * so existing placeholder-snapshot redaction behavior is unchanged.
     */
    private static final Pattern SENSITIVE_CLAUSE_KEY_PATTERN = Pattern.compile(
            "(?iu).*(amount|bonus|beløb|belob).*");

    private static final String SCOPE_USERS_READ = "users:read";
    private static final String REDACTED_VALUE = "[REDACTED]";

    @Inject
    ScopeContext scopeContext;

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        Object entity = responseContext.getEntity();
        if (entity == null) {
            return;
        }
        if (scopeContext.hasScope(SCOPE_USERS_READ)) {
            // Caller is authorised to see HR/User data — leave snapshots intact.
            return;
        }
        Object replacement = walk(entity);
        if (replacement != null && replacement != entity) {
            responseContext.setEntity(replacement);
        }
    }

    /**
     * Walk the response entity. Returns the same reference when no rewrite
     * is needed, or a new container/record holding redacted children when
     * the tree contained a {@link RevisionResponse}.
     */
    private Object walk(Object entity) {
        if (entity instanceof RevisionResponse rev) {
            return redact(rev);
        }
        if (entity instanceof SendSignatureResponse send) {
            // The envelope returned by POST .../send-signature wraps a
            // RevisionResponse alongside a localPersistenceFailed flag.
            // Redact the inner revision; the flag is non-sensitive.
            RevisionResponse inner = send.revision();
            RevisionResponse redacted = inner == null ? null : redact(inner);
            if (redacted == inner) {
                return send;
            }
            return new SendSignatureResponse(redacted, send.localPersistenceFailed());
        }
        if (entity instanceof CandidateResponse cand) {
            // CandidateResponse embeds a RevisionSummary, not a RevisionResponse,
            // and RevisionSummary carries no placeholder map — so there is
            // nothing to redact here. Returned as-is.
            return cand;
        }
        if (entity instanceof List<?> list) {
            return walkList(list);
        }
        if (entity instanceof Collection<?> coll) {
            return walkCollection(coll);
        }
        return entity;
    }

    private Object walkList(List<?> list) {
        boolean changed = false;
        java.util.List<Object> out = new java.util.ArrayList<>(list.size());
        for (Object element : list) {
            Object replacement = element == null ? null : walk(element);
            if (replacement != element) {
                changed = true;
            }
            out.add(replacement);
        }
        return changed ? out : list;
    }

    private Object walkCollection(Collection<?> coll) {
        boolean changed = false;
        java.util.List<Object> out = new java.util.ArrayList<>(coll.size());
        for (Object element : coll) {
            Object replacement = element == null ? null : walk(element);
            if (replacement != element) {
                changed = true;
            }
            out.add(replacement);
        }
        return changed ? out : coll;
    }

    /**
     * Build a redacted copy of a {@link RevisionResponse}. The frozen
     * snapshot map is rebuilt with sensitive values replaced; every other
     * field is copied as-is.
     */
    private RevisionResponse redact(RevisionResponse revision) {
        Map<String, String> original = revision.placeholderValuesSnapshot();
        Map<String, String> redacted = redactValueMap(original);
        List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> redactedClauses =
                redactClauses(revision.clausesSnapshot());
        boolean changed = redacted != original || redactedClauses != revision.clausesSnapshot();
        if (!changed) {
            return revision;
        }
        if (log.isDebugEnabled()) {
            log.debugf("Redacted sensitive placeholder/clause value(s) from revision %s", revision.uuid());
        }
        return new RevisionResponse(
                revision.uuid(),
                revision.dossierUuid(),
                revision.versionNumber(),
                revision.kind(),
                redacted,
                revision.signersConfigSnapshot(),
                revision.appendixFileUuidsSnapshot(),
                redactedClauses,
                revision.pdfArtifactsSnapshot(),
                revision.signingCaseKey(),
                revision.recipientEmail(),
                revision.recipientName(),
                revision.note(),
                revision.createdByUserUuid(),
                revision.createdAt());
    }

    /** Same reference back when nothing matched; a rebuilt map otherwise. */
    private Map<String, String> redactValueMap(Map<String, String> original) {
        if (original == null || original.isEmpty()) {
            return original;
        }
        Map<String, String> redacted = new LinkedHashMap<>(original.size());
        boolean changed = false;
        for (Map.Entry<String, String> e : original.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key != null && SENSITIVE_KEY_PATTERN.matcher(key).matches()
                    && value != null && !value.isEmpty()) {
                redacted.put(key, REDACTED_VALUE);
                changed = true;
            } else {
                redacted.put(key, value);
            }
        }
        return changed ? redacted : original;
    }

    /**
     * Clause snapshots carry negotiated compensation terms: parameter
     * values are redacted by the same key pattern plus the money-shaped
     * keys the clause convention uses ({@code *_AMOUNT}, {@code *BONUS*},
     * {@code *BELØB*}), and an Individuel aftale's free text — which can
     * say anything — is redacted wholesale.
     */
    private List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> redactClauses(
            List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> clauses) {
        if (clauses == null || clauses.isEmpty()) {
            return clauses;
        }
        boolean changed = false;
        List<dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO> out =
                new java.util.ArrayList<>(clauses.size());
        for (var clause : clauses) {
            Map<String, String> params = clause.parameterValues();
            Map<String, String> redactedParams = params;
            if (params != null && !params.isEmpty()) {
                Map<String, String> rebuilt = new LinkedHashMap<>(params.size());
                boolean paramChanged = false;
                for (Map.Entry<String, String> e : params.entrySet()) {
                    String key = e.getKey();
                    String value = e.getValue();
                    boolean sensitive = key != null && value != null && !value.isEmpty()
                            && (SENSITIVE_KEY_PATTERN.matcher(key).matches()
                                || SENSITIVE_CLAUSE_KEY_PATTERN.matcher(key).matches());
                    if (sensitive) {
                        rebuilt.put(key, REDACTED_VALUE);
                        paramChanged = true;
                    } else {
                        rebuilt.put(key, value);
                    }
                }
                if (paramChanged) {
                    redactedParams = rebuilt;
                }
            }
            String customText = clause.customText();
            String redactedText = customText == null || customText.isEmpty() ? customText : REDACTED_VALUE;
            if (redactedParams != params || !java.util.Objects.equals(redactedText, customText)) {
                changed = true;
                out.add(new dk.trustworks.intranet.utils.dto.signing.SelectedClauseDTO(
                        clause.clauseUuid(), redactedParams, clause.customTitle(), redactedText,
                        clause.displayOrder()));
            } else {
                out.add(clause);
            }
        }
        return changed ? out : clauses;
    }
}
