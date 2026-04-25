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
 * Slice 1 scope-aware response masking for recruitment endpoints.
 *
 * <p>Strips consent and screening fields from {@link CandidateResponse} and
 * {@link ApplicationResponse} unless the caller has {@code recruitment:write},
 * {@code :admin}, or {@code :offer}.
 *
 * <p><b>Limitation:</b> only handles top-level entities and {@link Collection}
 * of entities. Wrapper DTOs that embed CandidateResponse/ApplicationResponse
 * inside another record (none exist in Slice 1) would leak fields. Extend
 * {@link #strip(Object)} or add reflective recursion when introducing such
 * wrappers in later slices.
 */
@Provider
@Priority(Priorities.ENTITY_CODER)
public class RecruitmentScopeResponseFilter implements ContainerResponseFilter {

    private static final String WRITE = "recruitment:write";
    private static final String ADMIN = "recruitment:admin";
    private static final String OFFER = "recruitment:offer";

    @Inject ScopeContext scope;

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) {
        Object entity = res.getEntity();
        if (entity == null) return;

        String path = req.getUriInfo().getPath();
        if (!path.startsWith("api/recruitment/") && !path.startsWith("/api/recruitment/")) return;

        boolean canSeeConsent = scope.hasAnyScope(WRITE, ADMIN, OFFER);
        if (canSeeConsent) return;

        if (entity instanceof Collection<?> coll) {
            res.setEntity(coll.stream().map(this::strip).toList());
        } else {
            res.setEntity(strip(entity));
        }
    }

    private Object strip(Object item) {
        if (item instanceof CandidateResponse c) {
            return new CandidateResponse(
                    c.uuid(), c.firstName(), c.lastName(), c.email(), c.phone(),
                    c.currentCompany(), c.desiredPractice(), c.desiredCareerLevelUuid(),
                    c.noticePeriodDays(), c.salaryExpectation(), c.salaryCurrency(),
                    c.locationPreference(), c.linkedinUrl(), c.firstContactSource(),
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
}
