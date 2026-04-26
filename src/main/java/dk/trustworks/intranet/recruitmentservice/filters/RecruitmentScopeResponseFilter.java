package dk.trustworks.intranet.recruitmentservice.filters;

import dk.trustworks.intranet.recruitmentservice.api.dto.ApplicationResponse;
import dk.trustworks.intranet.recruitmentservice.api.dto.CandidateResponse;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.Collection;

/**
 * Scope-aware response masking for recruitment endpoints.
 *
 * <p>Two independent strip rules are applied:
 * <ol>
 *   <li><b>Consent / screening</b>: stripped from {@link CandidateResponse} and
 *       {@link ApplicationResponse} unless the caller has {@code recruitment:write},
 *       {@code :admin}, or {@code :offer} (Slice 1).</li>
 *   <li><b>Scorecard {@code privateNotes}</b>: stripped from {@code ScorecardResponse}
 *       unless the caller has {@code recruitment:interview}, {@code :admin}, or
 *       {@code :offer} (Slice 3a). The DTO is added in Phase E task 19; until then
 *       the {@code instanceof}/SimpleName guard makes this branch a no-op.</li>
 * </ol>
 *
 * <p><b>Limitation:</b> only handles top-level entities and {@link Collection}
 * of entities. Wrapper DTOs that embed Candidate/Application/Scorecard responses
 * inside another record would leak fields. Extend {@link #stripConsent(Object)}
 * / {@link #stripPrivateNotes(Object)} or add reflective recursion when
 * introducing such wrappers.
 */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class RecruitmentScopeResponseFilter implements ContainerResponseFilter {

    private static final String WRITE = "recruitment:write";
    private static final String ADMIN = "recruitment:admin";
    private static final String OFFER = "recruitment:offer";
    private static final String INTERVIEW = "recruitment:interview";

    @Inject ScopeContext scope;

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        Object entity = res.getEntity();
        if (entity == null) return;

        String path = req.getUriInfo().getPath();
        if (!path.startsWith("api/recruitment/") && !path.startsWith("/api/recruitment/")) return;

        boolean canSeeConsent = scope.hasAnyScope(WRITE, ADMIN, OFFER);
        boolean canSeePrivateNotes = scope.hasAnyScope(INTERVIEW, ADMIN, OFFER);

        if (canSeeConsent && canSeePrivateNotes) return;

        if (entity instanceof Collection<?> coll) {
            res.setEntity(coll.stream()
                    .map(item -> applyStrips(item, canSeeConsent, canSeePrivateNotes))
                    .toList());
        } else {
            res.setEntity(applyStrips(entity, canSeeConsent, canSeePrivateNotes));
        }
    }

    private Object applyStrips(Object item, boolean canSeeConsent, boolean canSeePrivateNotes) {
        Object stripped = canSeeConsent ? item : stripConsent(item);
        if (!canSeePrivateNotes) {
            stripPrivateNotes(stripped);
        }
        return stripped;
    }

    private Object stripConsent(Object item) {
        if (item instanceof CandidateResponse c) {
            return new CandidateResponse(
                    c.uuid(), c.firstName(), c.lastName(), c.email(), c.phone(),
                    c.currentCompany(), c.desiredPractice(), c.desiredCareerLevelUuid(),
                    c.noticePeriodDays(), c.salaryExpectation(), c.salaryCurrency(),
                    c.locationPreference(), c.linkedinUrl(), c.firstContactSource(),
                    c.tags(),
                    null, null, null,
                    c.state(), c.ownerUserUuid(),
                    c.addedToPoolAt(), c.retentionExtendedTo(),
                    c.createdAt(), c.updatedAt());
        }
        if (item instanceof ApplicationResponse a) {
            return new ApplicationResponse(
                    a.uuid(), a.candidateUuid(), a.roleUuid(),
                    a.applicationType(), a.referrerUserUuid(),
                    a.stage(),
                    null, null,
                    a.lastStageChangeAt(), a.acceptedAt(), a.convertedAt(), a.closedReason(),
                    a.createdAt(), a.updatedAt());
        }
        return item;
    }

    /**
     * Best-effort strip of {@code privateNotes} on ScorecardResponse instances.
     *
     * <p>The DTO is introduced in Phase E task 19 — until then this is a no-op
     * because no response will be of that type. SimpleName matching is used so
     * this filter does not require a hard compile-time dependency on a yet-to-be
     * authored DTO. When the DTO lands, swap to {@code instanceof
     * ScorecardResponse} and replace this body with a record-rebuild
     * (mirroring {@link #stripConsent(Object)}) for type safety. See
     * Slice 1 lesson: prefer record-rebuild over reflection once the DTO exists.
     */
    private void stripPrivateNotes(Object item) {
        if (item == null) return;
        if (!"ScorecardResponse".equals(item.getClass().getSimpleName())) return;
        nullField(item, "privateNotes");
    }

    private void nullField(Object target, String fieldName) {
        try {
            var f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Best-effort — never break a response over a missing field. The
            // record-rebuild swap in Phase E (when ScorecardResponse exists) makes
            // this concern moot.
        }
    }
}
