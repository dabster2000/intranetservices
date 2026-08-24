package dk.trustworks.intranet.vacationservice.resources;

import dk.trustworks.intranet.security.ScopeGuard;
import dk.trustworks.intranet.vacationservice.dto.CreateVacationPolicyRequest;
import dk.trustworks.intranet.vacationservice.model.VacationPolicy;
import dk.trustworks.intranet.vacationservice.services.VacationPolicyService;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

/**
 * Temporal accrual rates (Settings → Salary tab, ADMIN only — the BFF gates
 * the role; the human gate here requires unbounded {@code salaries:read}
 * reach). Append-only and forward-only: rates never change back in time.
 */
@JBossLog
@Tag(name = "vacation")
@Path("/vacation/policy")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"vacation:read"})
public class VacationPolicyResource {

    @Inject
    VacationPolicyService policyService;

    @Inject
    ScopeGuard scopeGuard;

    @GET
    public List<VacationPolicy> list() {
        requireHrHuman();
        return policyService.list();
    }

    @POST
    @RolesAllowed({"vacation:write"})
    public VacationPolicy create(CreateVacationPolicyRequest request) {
        requireHrHuman();
        return policyService.create(request, requireActor());
    }

    private String requireActor() {
        String actor = scopeGuard.actorOrNull();
        if (actor == null) {
            throw new ForbiddenException("An acting user is required");
        }
        return actor;
    }

    private void requireHrHuman() {
        requireActor();
        if (!scopeGuard.actorHasUnbounded("salaries:read")) {
            throw new ForbiddenException("Admin access is required for vacation policy");
        }
    }
}
