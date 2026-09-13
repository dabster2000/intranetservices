package dk.trustworks.intranet.aggregates.crm.account.resources;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDomainsRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRateDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipsDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRolesRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountSummaryDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.ClientDomainDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountActivityService;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountRateService;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountRelationshipService;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountService;
import dk.trustworks.intranet.aggregates.crm.account.services.PersonRoleService;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The account layer behind {@code /clients/{uuid}} (CRM spec §3.1, §3.2, §3.7, §4.3).
 *
 * <p><b>Its own root.</b> {@code /accounts} is a prefix no other class owns. RESTEasy
 * Reactive selects the resource CLASS by its class-level {@code @Path} before it looks at
 * a single method, so nesting these under {@code /clients/...} — which
 * {@code ClientResource} owns — would answer 404 "Unable to find matching target resource
 * method" no matter what the method paths said. That trap cost the internal-assignments
 * work a day on 2026-09-06.
 *
 * <p><b>Reads are open, writes are the sales tier.</b> {@code accounts:read} is granted to
 * role USER: the account page has always been firm-readable and says so on its own header.
 * {@code accounts:write} is SALES/PARTNER/ADMIN — band, roles and the plan are commercial
 * decisions. Note that {@code @RolesAllowed} gates the CLIENT, not the person
 * ({@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every scope), so the
 * per-person gate is the BFF's own {@code requirePermission}.
 *
 * <p><b>Nothing salary-derived is served here</b> except the break-even on
 * {@code /rate}, which {@link AccountRateService} nulls out for callers without a cost
 * role — server-side, so the figure never reaches the wire.
 */
@Tag(name = "crm")
@JBossLog
@Path("/accounts")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class AccountResource {

    @Inject
    AccountService accountService;

    @Inject
    AccountActivityService activityService;

    @Inject
    AccountRelationshipService relationshipService;

    @Inject
    AccountRateService rateService;

    @Inject
    AccountPlanService planService;

    @Inject
    ClientService clientService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    PersonRoleService personRoles;

    @Context
    SecurityContext securityContext;

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    @GET
    @Path("/{clientUuid}")
    public AccountDTO read(@PathParam("clientUuid") String clientUuid) {
        return accountService.read(clientUuid);
    }

    /**
     * The accounts list's CRM columns for every client, in one call.
     *
     * <p>Deliberately not one request per row: the list renders several hundred clients and
     * a per-row fetch would be several hundred round trips through the BFF. Each underlying
     * query is a single aggregate over its whole table.
     */
    @GET
    public List<AccountSummaryDTO> summaries() {
        Map<String, AccountBand> bands = accountService.bandsForAll();
        Map<String, List<PersonDTO>> supported = accountService.supportedByForAll();
        Map<String, AccountActivityDTO> lastActivity = activityService.lastActivityForAll();
        Map<String, AccountPlanService.PlanSummary> plans = planService.summariesForAll();

        List<AccountSummaryDTO> rows = new ArrayList<>();
        for (Client client : clientService.listAllClients()) {
            String uuid = client.getUuid();
            AccountBand band = bands.getOrDefault(uuid, AccountBand.BACKLOG);
            AccountPlanService.PlanSummary plan = plans.get(uuid);
            rows.add(new AccountSummaryDTO(
                    uuid,
                    band.name(),
                    supported.getOrDefault(uuid, List.of()),
                    plan == null ? null : plan.rag(),
                    plan == null ? null : plan.updatedAt(),
                    plan != null && plan.started(),
                    lastActivity.get(uuid)));
        }
        return rows;
    }

    @GET
    @Path("/{clientUuid}/activity")
    public List<AccountActivityDTO> activity(
            @PathParam("clientUuid") String clientUuid,
            @QueryParam("limit") @DefaultValue("100") int limit) {
        return activityService.forClient(clientUuid, limit);
    }

    @GET
    @Path("/{clientUuid}/relationships")
    public AccountRelationshipsDTO relationships(@PathParam("clientUuid") String clientUuid) {
        return relationshipService.forClient(clientUuid);
    }

    /**
     * The rate KPI. Break-even and target come back null unless the caller holds a cost
     * role — decided in the service, not here, so it is covered by a fast-tier test.
     */
    @GET
    @Path("/{clientUuid}/rate")
    public AccountRateDTO rate(@PathParam("clientUuid") String clientUuid) {
        return rateService.forClient(clientUuid, callerMaySeeCost());
    }

    @GET
    @Path("/{clientUuid}/domains")
    public List<ClientDomainDTO> domains(@PathParam("clientUuid") String clientUuid) {
        return accountService.domains(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------------

    @PATCH
    @Path("/{clientUuid}")
    @RolesAllowed({"accounts:write"})
    public AccountDTO patch(@PathParam("clientUuid") String clientUuid, AccountPatchRequest request) {
        return accountService.patch(clientUuid, request, requireActor());
    }

    @PUT
    @Path("/{clientUuid}/roles")
    @RolesAllowed({"accounts:write"})
    public AccountDTO replaceRoles(@PathParam("clientUuid") String clientUuid, AccountRolesRequest request) {
        return accountService.replaceSupportedBy(clientUuid, request, requireActor());
    }

    @PUT
    @Path("/{clientUuid}/domains")
    @RolesAllowed({"accounts:write"})
    public List<ClientDomainDTO> replaceDomains(@PathParam("clientUuid") String clientUuid,
                                                AccountDomainsRequest request) {
        return accountService.replaceDomains(clientUuid, request, requireActor());
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * Whether this caller may see a salary-derived cost figure.
     *
     * <p>Resolved from the PERSON in {@code X-Requested-By}, never from the security
     * context. The token this resource sees is the BFF's own client credential; its groups
     * claim carries scopes — including {@code admin:*} — and never a person's role names.
     * The earlier version of this method fell through to {@code isUserInRole("admin:*")},
     * which the BFF's token satisfies on every request, so the break-even reached every
     * employee's browser through the BFF. The four cost roles are the same the CXO cost
     * endpoints and the frontend's {@code CXO_SALARY_ROLES} use.
     *
     * <p>A direct API client that holds a cost ROLE on its token (not through the BFF) is
     * still honoured, so the behaviour for machine callers is unchanged.
     */
    private boolean callerMaySeeCost() {
        String actor = requestHeaderHolder.getUserUuid();
        if (personRoles.maySeeCost(actor)) {
            return true;
        }
        if (securityContext == null) {
            return false;
        }
        for (String role : AccountRateService.COST_ROLES) {
            if (securityContext.isUserInRole(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The acting employee. Every write records who made it, and
     * {@code X-Requested-By} falls back to the BFF's client id when the header is missing —
     * so anything that is not a uuid is refused rather than stamped on the row.
     */
    private String requireActor() {
        String actor = requestHeaderHolder.getUserUuid();
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException("X-Requested-By header is required", Response.Status.BAD_REQUEST);
        }
        try {
            java.util.UUID.fromString(actor.trim());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("X-Requested-By is not a valid UUID", Response.Status.BAD_REQUEST);
        }
        return actor.trim();
    }
}
