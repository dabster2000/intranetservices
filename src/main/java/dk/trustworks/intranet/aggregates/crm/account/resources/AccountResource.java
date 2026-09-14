package dk.trustworks.intranet.aggregates.crm.account.resources;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountDomainsRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountPatchRequest;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRateDTO;
import dk.trustworks.intranet.aggregates.crm.account.dto.AccountRelationshipDTO;
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
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSuggestionDTO;
import dk.trustworks.intranet.aggregates.crm.calendar.dto.CalendarSuggestionDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.calendar.services.CalendarSuggestionService;
import dk.trustworks.intranet.aggregates.crm.plan.services.AccountPlanService;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSuggestionDTO;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackSuggestionDecisionRequest;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackMentionService;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackUnmatchedCompanyService;
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
import jakarta.ws.rs.POST;
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

    @Inject
    CalendarSuggestionService calendarSuggestions;

    @Inject
    SlackUnmatchedCompanyService slackSuggestions;

    @Inject
    AccountSlackMentionService slackMentions;

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
        // The relationships read the bands, so the bands are computed once and handed on
        // rather than derived twice from the same tables.
        Map<String, AccountRelationshipDTO> relationships = accountService.relationshipsForAll(bands);
        Map<String, List<PersonDTO>> supported = accountService.supportedByForAll();
        Map<String, List<PersonDTO>> members = accountService.membersForAll();
        Map<String, Integer> knownBy = accountService.knownByForAll();
        Map<String, Integer> signals = accountService.signalCountForAll();
        Map<String, PersonDTO> addedBy = accountService.addedByForAll();
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
                    relationships.get(uuid),
                    supported.getOrDefault(uuid, List.of()),
                    members.getOrDefault(uuid, List.of()),
                    knownBy.getOrDefault(uuid, 0),
                    signals.getOrDefault(uuid, 0),
                    addedBy.get(uuid),
                    plan == null ? null : plan.rag(),
                    plan == null ? null : plan.updatedAt(),
                    plan != null && plan.started(),
                    lastActivity.get(uuid)));
        }
        return rows;
    }

    /**
     * "Seen in calendars": companies colleagues keep meeting that Intra does not know
     * (spec §2.5, cut 2).
     *
     * <p>Deliberately BEFORE {@code /{clientUuid}} in this class. RESTEasy Reactive matches
     * a literal path segment ahead of a template one, so the order is not what makes this
     * work — but a reader looking for why {@code calendar-suggestions} is not read as a
     * client uuid should find the two next to each other.
     *
     * <p>{@code accounts:read}, the class default: every employee can already read every
     * account page, and a domain with a meeting count is less than that.
     */
    @GET
    @Path("/calendar-suggestions")
    public List<CalendarSuggestionDTO> calendarSuggestions(
            @QueryParam("limit") @DefaultValue("25") int limit) {
        return calendarSuggestions.suggestions(limit);
    }

    /**
     * Add the company, link the domain to a client we already have, or never ask again.
     *
     * <p>{@code accounts:write} — all three change what the CRM holds, and the ADD branch
     * creates a company. The per-person gate is the BFF's own {@code requirePermission}.
     */
    @POST
    @Path("/calendar-suggestions/{domain}/decision")
    @RolesAllowed({"accounts:write"})
    public Response decideCalendarSuggestion(@PathParam("domain") String domain,
                                             CalendarSuggestionDecisionRequest request) {
        calendarSuggestions.decide(domain, request, requireActor());
        return Response.noContent().build();
    }

    /**
     * "Heard in Slack": companies colleagues keep talking about in the general channels
     * that Intra has never heard of (source-channel spec §6.2).
     *
     * <p>Deliberately BESIDE {@code calendar-suggestions} and, like it, BEFORE
     * {@code /{clientUuid}} in this class. RESTEasy Reactive matches a literal path segment
     * ahead of a template one, so the order is not what makes this work — but a reader
     * looking for why {@code slack-suggestions} is not read as a client uuid should find
     * the three of them next to each other.
     *
     * <p>The two panels ask the same question through different doors, which is why they
     * are neighbours rather than one endpoint: a domain identifies a company by
     * construction, a name somebody typed in a channel does not, and the row is keyed and
     * aliased on that difference.
     *
     * <p>{@code accounts:read}, the class default: every employee can already read every
     * account page, and a company name with a mention count is less than that.
     */
    @GET
    @Path("/slack-suggestions")
    public List<SlackSuggestionDTO> slackSuggestions(
            @QueryParam("limit") @DefaultValue("25") int limit) {
        return slackSuggestions.suggestions(limit);
    }

    /**
     * Add the company, say it is a client we already have, or never ask again.
     *
     * <p>{@code accounts:write} — all three change what the CRM holds, and the ADD branch
     * creates a company. The per-person gate is the BFF's own {@code requirePermission}.
     *
     * <p>Impersonation is refused: a decision here creates an account or writes a permanent
     * deny-list entry against the name of the person who took it, and one taken by an admin
     * wearing somebody else's identity would name the wrong person for ever.
     */
    @POST
    @Path("/slack-suggestions/{nameKey}/decision")
    @RolesAllowed({"accounts:write"})
    public Response decideSlackSuggestion(@PathParam("nameKey") String nameKey,
                                          SlackSuggestionDecisionRequest request) {
        slackSuggestions.decide(nameKey, request, requireHumanActor());
        return Response.noContent().build();
    }

    /**
     * "This is not about this client" — takes one Slack mention off an account.
     *
     * <p>Body-less and idempotent: there is one thing to say about a mention and the button
     * says it, so a double-click is a second call with the same meaning rather than a second
     * decision. The client is part of the address and the service checks the row against it,
     * so a mention on somebody else's account cannot be reached by guessing its uuid under
     * an account this caller does own.
     *
     * <p>{@code accounts:write} gets the caller through the door; WHICH accounts they may
     * act on is an ownership check in the service, because a scope cannot say "the owner of
     * THIS account". Management is resolved here from the PERSON's roles and handed down —
     * never from the security context, whose token is the BFF's own client credential and
     * carries scopes rather than role names.
     *
     * <p>No restore endpoint (D2): the row is kept for audit, and undoing a dismissal is
     * rare enough to be a conversation.
     */
    @POST
    @Path("/{clientUuid}/slack-mentions/{uuid}/dismiss")
    @RolesAllowed({"accounts:write"})
    public Response dismissSlackMention(@PathParam("clientUuid") String clientUuid,
                                        @PathParam("uuid") String uuid) {
        String actor = requireHumanActor();
        slackMentions.dismiss(clientUuid, uuid, actor, personRoles.isManagement(actor));
        return Response.noContent().build();
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

    /**
     * Who is on the account: supporters and the team, replaced wholesale.
     *
     * <p>A null list in the body leaves that set alone, so a caller that only edits one of
     * them cannot empty the other by omission.
     */
    @PUT
    @Path("/{clientUuid}/roles")
    @RolesAllowed({"accounts:write"})
    public AccountDTO replaceRoles(@PathParam("clientUuid") String clientUuid, AccountRolesRequest request) {
        return accountService.replaceRoles(clientUuid, request, requireActor());
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

    /**
     * The acting employee, and not an admin wearing their identity.
     *
     * <p>A second helper rather than a stricter {@link #requireActor()}, on purpose. The
     * three writes that call the looser one — band and roles, the team, the domains — record
     * who made an edit, and an administrator fixing one on somebody's behalf is a normal
     * thing to do. The writes below are different in kind: they decide that a company exists
     * or that a reading of a channel was wrong about an account, and the row keeps that
     * person's name as the answer to "who decided this". Tightening the shared helper would
     * change the behaviour of the three existing endpoints as a side effect of adding these.
     *
     * <p>Same shape, and the same reasoning, as {@code CalendarConsentResource} and
     * {@code AccountSignalResource}.
     */
    private String requireHumanActor() {
        if (requestHeaderHolder.isImpersonated()) {
            throw new WebApplicationException(
                    "This cannot be decided while impersonating — the decision records who took it",
                    Response.Status.FORBIDDEN);
        }
        return requireActor();
    }
}
