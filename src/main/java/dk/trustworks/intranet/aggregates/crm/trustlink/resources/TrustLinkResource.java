package dk.trustworks.intranet.aggregates.crm.trustlink.resources;

import dk.trustworks.intranet.aggregates.crm.person.services.AccountPersonService;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkAliasDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkAliasesRequest;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncStateDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSyncSummary;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkCompanyAliasService;
import dk.trustworks.intranet.aggregates.crm.trustlink.services.TrustLinkSyncService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * Which TrustLink companies an account maps to (CRM spec §3.5, §3.7).
 *
 * <p><b>Why a mapping exists at all.</b> TrustLink's LinkedIn graph is keyed on company
 * names typed by whoever added the connection, and it fragments them:
 * {@code Novo Nordisk} carries 203 tier-5 connections while {@code Novo Nordisk A/S}
 * carries 2, and the Intra client is called {@code NOVO NORDISK A/S}. A single-name match
 * is not a near miss, it loses 203 of 205 relationships. So a client points at MANY
 * TrustLink names: the nightly job seeds what it can find, and a person fixes the rest
 * here. Everything else about this feature is derived; this one list is not, because no
 * rule gets it right unaided.
 *
 * <p><b>Its own root.</b> {@code /trustlink} is a prefix no other resource class owns —
 * checked against every class-level {@code @Path} in the codebase, and locked by
 * {@code TrustLinkResourceRoutingTest}. RESTEasy Reactive selects the resource CLASS by its
 * class-level {@code @Path} before it looks at a single method, so hanging these off
 * {@code /clients/...} or {@code /accounts/...} — which {@code ClientResource} and
 * {@code AccountResource} own — would answer 404 "Unable to find matching target resource
 * method" no matter what the method paths said. That trap cost the internal-assignments
 * work a day on 2026-09-06 and {@code AccountResource} carries the same warning.
 *
 * <p><b>Reads are open, writes are the sales tier</b>, matching {@code AccountResource}
 * exactly: {@code accounts:read} for reading the mapping — it is account context, as
 * firm-readable as the account page it appears on — and {@code accounts:write} for changing
 * it. Note that {@code @RolesAllowed} gates the CLIENT, not the person
 * ({@code AdminScopeAugmentor} expands the BFF's {@code admin:*} to every scope), so the
 * per-person gate is the BFF's own {@code requirePermission}.
 *
 * <p><b>No PII passes through here.</b> The connections themselves — names, job titles and
 * LinkedIn urls of people at client organisations — are served by
 * {@code GET /accounts/{uuid}/relationships}, inside the graph that gives them a reason to
 * be on screen. This resource carries company names, a run summary and the run bookkeeping
 * only — the sole people it ever names are Trustworks colleagues the matcher could not place.
 */
@Tag(name = "crm")
@JBossLog
@Path("/trustlink")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"accounts:read"})
public class TrustLinkResource {

    /** What the typeahead asks for by default. TrustLink's own endpoint takes the same limit. */
    private static final int DEFAULT_SEARCH_LIMIT = 10;

    private static final int MAX_SEARCH_LIMIT = 25;

    /** Longer than any company name TrustLink holds; a longer term is a client bug or a probe. */
    private static final int MAX_TERM_LENGTH = 100;

    @Inject
    TrustLinkCompanyAliasService aliasService;

    @Inject
    TrustLinkSyncService syncService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    /** Only for the post-commit rebuild hook on {@link #replaceAliases}. */
    @Inject
    AccountPersonService personService;

    // ------------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------------

    /**
     * Every TrustLink company name mapped to this client, AUTO and MANUAL alike, including
     * the disabled ones. The editor has to show a suppressed name as suppressed — otherwise
     * it looks like the seeder simply never found it, and somebody adds it back by hand.
     */
    @GET
    @Path("/clients/{clientUuid}/aliases")
    public List<TrustLinkAliasDTO> aliases(@PathParam("clientUuid") String clientUuid) {
        return aliasService.aliasesFor(clientUuid);
    }

    /**
     * Whether the nightly job ran, when it last SUCCEEDED, and what it moved.
     *
     * <p><b>Why an endpoint and not a query.</b> The job writes {@code trustlink_sync_state}
     * at 02:40 and, until this existed, nothing read it: the answer to "did last night work?"
     * lived only in a production database that is read-only by default and that most of the
     * people who depend on this feed cannot reach at all. Meanwhile the failure mode is
     * silent — connections simply stop getting fresher, and a stale relationship graph looks
     * exactly like a quiet one, so nothing on the account page would ever say so.
     *
     * <p><b>{@code accounts:read}, inherited from the class</b>, and deliberately not the
     * write scope that guards the typeahead and the manual trigger. This reads our own row,
     * makes no call to TrustLink and names no external person — the counters and the
     * colleagues' names in it are firm-readable in the same way the account page is. Anyone
     * who can see the "who knows them" card can see whether the thing that fills it is alive.
     *
     * <p>The {@code enabled} flag in the response is the field that makes the rest legible: a
     * reader has to be able to tell "the feature is switched off in this environment" from
     * "the job ran and achieved nothing", and both look like zeros.
     */
    @GET
    @Path("/sync-state")
    public TrustLinkSyncStateDTO syncState() {
        return syncService.syncState();
    }

    // ------------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------------

    /**
     * Replaces the hand-kept part of a client's mapping with exactly the names given.
     *
     * <p>A name the caller kept that the seeder had found stays AUTO; a name the caller
     * removed becomes a disabled MANUAL row rather than a deleted one, so tonight's run
     * does not helpfully add it straight back. The service decides all of that — the
     * resource only establishes who is asking.
     */
    @PUT
    @Path("/clients/{clientUuid}/aliases")
    @RolesAllowed({"accounts:write"})
    public List<TrustLinkAliasDTO> replaceAliases(@PathParam("clientUuid") String clientUuid,
                                                  TrustLinkAliasesRequest request) {
        List<String> names = request == null || request.companyNames() == null
                ? List.of()
                : request.companyNames();
        List<TrustLinkAliasDTO> aliases = aliasService.replaceManualAliases(clientUuid, names, requireActor());
        rebuildPeople(clientUuid);
        return aliases;
    }

    /**
     * Refreshes the person registry for this account after its alias set changed.
     *
     * <p>The alias set decides which TrustLink connections attach to an account at all, so
     * switching a company name on or off adds or removes people from
     * {@code account_person} — and the relationships tab reads that table, not
     * {@code trustlink_connection}. Without this hook the editor would appear to have done
     * nothing until the 03:00 sweep, which is exactly the confusion the alias editor exists to
     * remove.
     *
     * <p><b>Here rather than inside {@code replaceManualAliases}</b>, which is
     * {@code @Transactional}: the rebuild has to see the committed alias rows, and a rebuild
     * that threw inside that method would roll the person's alias edit back.
     * {@code AccountPersonService.rebuild} opens its own {@code requiringNew} transaction, so
     * it is called bare; the try/catch is still required, because an exception escaping here
     * would fail a write that has already succeeded.
     *
     * <p>Counts and a uuid only. TrustLink rows are third-party PII — name, job title and
     * LinkedIn profile of people who were never asked — and the log is not the place for them.
     */
    private void rebuildPeople(String clientUuid) {
        try {
            AccountPersonService.RebuildSummary summary = personService.rebuild(clientUuid);
            log.infof("Account person registry rebuilt after an alias change: client=%s people=%d identities=%d",
                    clientUuid, summary.peopleUpserted(), summary.identitiesUpserted());
        } catch (RuntimeException e) {
            log.warnf("Account person registry could not be rebuilt after an alias change: client=%s code=%s",
                    clientUuid, e.getClass().getSimpleName());
        }
    }

    /**
     * The company-name typeahead for the alias editor, proxied.
     *
     * <p>{@code accounts:write}, not {@code accounts:read}: this is an editing aid and the
     * only endpoint that reaches a third party live on a user's keystroke. Gating it with
     * the scope that may actually edit keeps a read-only reader from being able to walk
     * TrustLink's company list through our credentials.
     *
     * <p>A blank or over-long term is refused here rather than forwarded, and the limit is
     * clamped rather than trusted. TrustLink silently ignores parameters it does not
     * understand and would answer an arbitrary page of companies — which reads as a working
     * search returning nonsense, the worst failure for a control that writes aliases.
     */
    @GET
    @Path("/companies/search")
    @RolesAllowed({"accounts:write"})
    public List<String> searchCompanies(@QueryParam("term") String term,
                                        @QueryParam("limit") @DefaultValue("" + DEFAULT_SEARCH_LIMIT) int limit) {
        String trimmed = term == null ? "" : term.trim();
        if (trimmed.isEmpty()) {
            throw new WebApplicationException("term is required", Response.Status.BAD_REQUEST);
        }
        if (trimmed.length() > MAX_TERM_LENGTH) {
            throw new WebApplicationException(
                    "term must be at most " + MAX_TERM_LENGTH + " characters", Response.Status.BAD_REQUEST);
        }
        return aliasService.searchCompanyNames(trimmed, Math.min(Math.max(limit, 1), MAX_SEARCH_LIMIT));
    }

    /**
     * Runs the nightly sync now and returns what it did.
     *
     * <p>Deliberately synchronous, like the calendar sync's manual trigger: it exists so
     * that a mapping somebody has just fixed can be proved to pull connections in, without
     * waiting for 02:40. It is not a routine call — the job is.
     *
     * <p>The summary leads with a {@code status}: {@code RAN} or {@code DISABLED}. Without it
     * a run refused by the feature flag is indistinguishable from a successful one that found
     * nobody — both are zeros and both answer 200 — and an operator would read "0 connections"
     * as "TrustLink knows nobody here" and go and rewrite a mapping that was never consulted.
     *
     * <p>{@code syncNow()} rather than {@code syncAll()}: a pass already in flight answers
     * 409 here instead of the summary of zeros the nightly job settles for. A first run
     * seeds ~300 clients one typeahead call at a time and will outlive the load balancer's
     * 60-second idle timeout — the run continues server-side and the next call returns 409
     * until it finishes, which is the honest reading of a 504 on this path.
     */
    @POST
    @Path("/sync")
    @RolesAllowed({"accounts:write"})
    public TrustLinkSyncSummary syncNow() {
        String actor = requireActor();
        log.infof("TrustLink sync triggered by hand: actor=%s", actor);
        return syncService.syncNow();
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /**
     * The acting employee. Every write records who made it, and {@code X-Requested-By}
     * falls back to the BFF's client id when the header is missing — so anything that is
     * not a uuid is refused rather than stamped on the row. Identical to
     * {@code AccountResource.requireActor()} on purpose: an alias is an account edit and is
     * attributed the same way.
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
