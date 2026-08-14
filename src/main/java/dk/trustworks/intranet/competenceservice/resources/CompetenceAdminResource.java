package dk.trustworks.intranet.competenceservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import dk.trustworks.intranet.competenceservice.content.CompetencePayloadCodec;
import dk.trustworks.intranet.competenceservice.domain.CompetencePlaceholderScanner.Finding;
import dk.trustworks.intranet.competenceservice.dto.AudiencePreviewDTO;
import dk.trustworks.intranet.competenceservice.dto.DecisionOutcomeDTO;
import dk.trustworks.intranet.competenceservice.dto.DecisionRequest;
import dk.trustworks.intranet.competenceservice.dto.DecisionResponse;
import dk.trustworks.intranet.competenceservice.dto.DraftUpsertRequest;
import dk.trustworks.intranet.competenceservice.dto.ImportResultDTO;
import dk.trustworks.intranet.competenceservice.dto.PendingApprovalDTO;
import dk.trustworks.intranet.competenceservice.dto.PublishPreviewDTO;
import dk.trustworks.intranet.competenceservice.dto.PublishRequest;
import dk.trustworks.intranet.competenceservice.dto.RequirementAdminDTO;
import dk.trustworks.intranet.competenceservice.dto.RequirementUpsertRequest;
import dk.trustworks.intranet.competenceservice.dto.SettingsAuditDTO;
import dk.trustworks.intranet.competenceservice.dto.SettingsDTO;
import dk.trustworks.intranet.competenceservice.dto.SettingsUpdateRequest;
import dk.trustworks.intranet.competenceservice.dto.VersionSummaryDTO;
import dk.trustworks.intranet.competenceservice.model.CompetenceAttempt;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.CompetenceSettingsAudit;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.competenceservice.model.ContentStatus;
import dk.trustworks.intranet.competenceservice.services.CompetenceContentService;
import dk.trustworks.intranet.competenceservice.services.CompetenceDecisionService;
import dk.trustworks.intranet.competenceservice.services.CompetenceDecisionService.DecisionOutcome;
import dk.trustworks.intranet.competenceservice.services.CompetenceDecisionService.PendingApproval;
import dk.trustworks.intranet.competenceservice.services.CompetenceExportService;
import dk.trustworks.intranet.competenceservice.services.CompetenceMatrixService;
import dk.trustworks.intranet.competenceservice.services.CompetenceMatrixService.Matrix;
import dk.trustworks.intranet.competenceservice.services.CompetenceRequirementService;
import dk.trustworks.intranet.competenceservice.services.CompetenceSettingsService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
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

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * The whole {@code /knowledge/competence/admin} tree — matrix, approvals, exports, authoring
 * and settings (spec §6.2 and §6.3).
 *
 * <h2>Why one class and not two</h2>
 *
 * <p>§6.3 describes the authoring surface as {@code /admin/content}, which reads like a second
 * root resource. It is not one here. Two JAX-RS root resources where one {@code @Path} is a
 * prefix of the other is a dispatch ambiguity that resolves one way in dev mode and another
 * once a different provider ordering is in play — the class of bug that is invisible in a test
 * suite and obvious only in production. One root owns the prefix; the split that matters is
 * the authorization split below, not a URL split.
 *
 * <h2>The two keys</h2>
 *
 * <p>The class default is {@code competence:approve}, and every authoring and settings-write
 * method overrides it with {@code competence:write}. Method-level {@code @RolesAllowed} wins,
 * so the two keys stay genuinely separable (§3.1): a content owner holding only
 * {@code competence:write} can author without ever seeing a colleague's score, and a team lead
 * holding only {@code competence:approve} can approve without being able to change what the
 * test asks. Granting both is a decision somebody makes, not a side effect of the routing.
 *
 * <p>The approve surface is additionally narrowed row-by-row inside the services by
 * {@code AuthorizationService.resolveReach()} — the scope opens the endpoint, reach decides
 * which people appear in it. Neither substitutes for the other, and neither lives here.
 *
 * <h2>Helpers are private, without exception</h2>
 *
 * <p>Class-level {@code @RolesAllowed} is applied by an interceptor that binds to every
 * non-private method on the class, helpers included. A package-private helper would therefore
 * become a security-interposed method that is not an endpoint but behaves like one, and a
 * later refactor that adds a {@code @Path} to it would inherit a gate nobody chose. Every
 * helper below is {@code private}; anything that wanted to be shared went into a service.
 *
 * <h2>Logging</h2>
 *
 * <p>This class logs nothing. Every domain event on these paths already carries its stable
 * token from the service that performs it — {@code COMPETENCE_DECISION},
 * {@code COMPETENCE_CONTENT_PUBLISHED}, {@code COMPETENCE_EXPORT},
 * {@code COMPETENCE_SETTING_CHANGED} — and a second line from the resource would double every
 * count the production log sweep reports. Payloads, answers and option ids are never logged
 * anywhere in this module (§10.7).
 */
@JBossLog
@Tag(name = "competence")
@Path("/knowledge/competence/admin")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"competence:approve"})
public class CompetenceAdminResource {

    private static final String CSV = "text/csv";

    /** How many change-log rows the settings screen shows unless it asks for more. */
    private static final int DEFAULT_CHANGE_LOG_ROWS = 25;

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    CompetenceContentService contentService;

    @Inject
    CompetenceDecisionService decisionService;

    @Inject
    CompetenceMatrixService matrixService;

    @Inject
    CompetenceExportService exportService;

    @Inject
    CompetenceSettingsService settingsService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // -----------------------------------------------------------------------
    // §6.2 — matrix and approvals (competence:approve)
    // -----------------------------------------------------------------------

    /**
     * The compliance grid, narrowed to the people the caller may see.
     *
     * <p>The actor comes from the request header, never from a parameter: there is nothing on
     * this URL to tamper with, so widening the view means acquiring the grant, not editing a
     * query string (§10.1).
     */
    @GET
    @Path("/matrix")
    public Matrix matrix() {
        return matrixService.matrix(actor());
    }

    /**
     * Passed attempts awaiting a decision, oldest first, within reach.
     *
     * <p>Names are resolved here rather than in the service because they are presentation:
     * the queue is keyed on uuids and stays correct without them.
     */
    @GET
    @Path("/attempts/pending")
    public List<PendingApprovalDTO> pendingApprovals() {
        List<PendingApproval> pending = decisionService.pendingApprovals(actor());
        Set<String> subjects = new LinkedHashSet<>();
        for (PendingApproval row : pending) {
            subjects.add(row.attempt().getUseruuid());
        }
        Map<String, String> names = requirementService.displayNamesOf(subjects);

        LocalDateTime now = LocalDateTime.now();
        List<PendingApprovalDTO> out = new ArrayList<>(pending.size());
        for (PendingApproval row : pending) {
            CompetenceAttempt attempt = row.attempt();
            out.add(PendingApprovalDTO.of(attempt, row.requirement(),
                    displayName(names, attempt.getUseruuid()), now));
        }
        return out;
    }

    /**
     * Bulk approve or revoke. Always {@code 200}; the verdicts are in the body (§6.2).
     *
     * <p>Partial success is the design, not a fallback. A batch of twenty in which one row is
     * the actor's own attempt, or has drifted out of reach since the queue was rendered, must
     * decide the other nineteen — an all-or-nothing 4xx would train people to approve one at a
     * time, and the per-item refusal is the only place the self-approval rule is visible.
     *
     * <p>Whole-call refusals still throw: an empty list or a missing decision is a
     * {@code 400}, and deciding while impersonating is a {@code 403} raised by
     * {@code CompetenceDecisionService} itself (§10.4). That guard is deliberately not
     * duplicated here — one enforcement point that every caller of the service passes through
     * is stronger than two that can drift apart.
     */
    @POST
    @Path("/attempts/decisions")
    public Response decide(DecisionRequest request) {
        if (request == null) {
            throw new WebApplicationException("No decision supplied", Response.Status.BAD_REQUEST);
        }
        List<DecisionOutcome> outcomes = decisionService.decide(
                request.attemptUuids(), request.decision(), request.note(), actor());

        List<DecisionOutcomeDTO> results = new ArrayList<>(outcomes.size());
        for (DecisionOutcome outcome : outcomes) {
            results.add(new DecisionOutcomeDTO(outcome.attemptUuid(), outcome.ok(),
                    outcome.message()));
        }
        return Response.ok(DecisionResponse.of(results)).build();
    }

    /**
     * The auditor file — approved attempts only.
     *
     * <p>Separate from {@link #exportAttempts()} on purpose: the file that goes outside
     * Trustworks must not be producible by accidentally leaving a filter unchecked.
     */
    @GET
    @Path("/export/approved")
    @Produces(CSV)
    public Response exportApproved() {
        return csvDownload(exportService.approvedCsv(actor()), "kompetence-godkendte");
    }

    /** The full internal file, failures included. */
    @GET
    @Path("/export/attempts")
    @Produces(CSV)
    public Response exportAttempts() {
        return csvDownload(exportService.attemptsCsv(actor()), "kompetence-forsoeg");
    }

    // -----------------------------------------------------------------------
    // §6.3 — requirements (competence:write)
    // -----------------------------------------------------------------------

    /** Every requirement, inactive ones included — the admin list is not the learner list. */
    @GET
    @Path("/requirements")
    @RolesAllowed({"competence:write"})
    public List<RequirementAdminDTO> listRequirements() {
        List<CompetenceRequirement> requirements = requirementService.listAll();
        List<RequirementAdminDTO> out = new ArrayList<>(requirements.size());
        for (CompetenceRequirement requirement : requirements) {
            out.add(toAdminDTO(requirement));
        }
        return out;
    }

    /**
     * Creates a requirement. {@code comp_id} is slugified from the name with a numeric suffix
     * on collision, inside {@code CompetenceRequirementService} — the resource neither
     * composes nor validates keys.
     */
    @POST
    @Path("/requirements")
    @RolesAllowed({"competence:write"})
    public Response createRequirement(RequirementUpsertRequest request) {
        CompetenceRequirement requirement = requirementService.create(request, actor());
        return Response
                .created(URI.create("/knowledge/competence/admin/requirements/"
                        + requirement.getUuid()))
                .entity(toAdminDTO(requirement))
                .build();
    }

    /**
     * Full update of the editable fields. Unknown targeting uuids are a {@code 400} naming the
     * offender (§11.4), and the practice token {@code UD}/null is refused outright — a target
     * that silently matches nobody is the one failure mode that reports itself as full
     * compliance.
     */
    @PUT
    @Path("/requirements/{uuid}")
    @RolesAllowed({"competence:write"})
    public RequirementAdminDTO updateRequirement(@PathParam("uuid") String uuid,
                                                 RequirementUpsertRequest request) {
        return toAdminDTO(requirementService.update(uuid, request, actor()));
    }

    /** Resolved audience, so an empty or wrong targeting is visible before go-live. */
    @GET
    @Path("/requirements/{uuid}/audience")
    @RolesAllowed({"competence:write"})
    public AudiencePreviewDTO audience(@PathParam("uuid") String uuid) {
        return requirementService.audiencePreview(uuid);
    }

    /** Version history for both kinds — what was live when, and what superseded it. */
    @GET
    @Path("/requirements/{uuid}/versions")
    @RolesAllowed({"competence:write"})
    public List<VersionSummaryDTO> versions(@PathParam("uuid") String uuid) {
        return contentService.versionHistory(uuid).stream().map(VersionSummaryDTO::of).toList();
    }

    // -----------------------------------------------------------------------
    // §6.3 — drafts (competence:write)
    // -----------------------------------------------------------------------

    /**
     * The open DRAFT, so the editor can load what it is editing. {@code 404} when there is
     * none — "no draft" is a state the editor branches on, not an error to be papered over
     * with an empty payload that would then overwrite the live content on the next save.
     *
     * <p>Answers with the same shape {@link #saveDraft} accepts, so the editor round-trips
     * without a second contract to keep aligned. Version metadata — uuid, timestamps,
     * supersession — lives on {@code /versions}, which is where it stays useful after publish.
     */
    @GET
    @Path("/requirements/{uuid}/{kind}/draft")
    @RolesAllowed({"competence:write"})
    public DraftUpsertRequest draft(@PathParam("uuid") String uuid,
                                    @PathParam("kind") String kind) {
        ContentKind contentKind = contentKind(kind);
        CompetenceContentVersion draft = pick(contentService.versionHistory(uuid), contentKind,
                ContentStatus.DRAFT);
        if (draft == null) {
            throw new WebApplicationException("No draft", Response.Status.NOT_FOUND);
        }
        return new DraftUpsertRequest(draft.getVersionLabel(), readTree(draft));
    }

    /**
     * Upserts the single DRAFT. Validates in full (§11.1, §11.2) and activates nothing — a
     * draft must never reach an employee (C-2), which is what makes saving cheap enough to do
     * mid-thought.
     */
    @PUT
    @Path("/requirements/{uuid}/{kind}/draft")
    @RolesAllowed({"competence:write"})
    public VersionSummaryDTO saveDraft(@PathParam("uuid") String uuid,
                                       @PathParam("kind") String kind,
                                       DraftUpsertRequest request) {
        if (request == null) {
            throw new WebApplicationException("No draft supplied", Response.Status.BAD_REQUEST);
        }
        return VersionSummaryDTO.of(contentService.upsertDraft(uuid, contentKind(kind),
                request.versionLabel(), request.payloadJson(), actor()));
    }

    /** Discards the DRAFT. ACTIVE and ARCHIVED versions are never reachable from here. */
    @DELETE
    @Path("/requirements/{uuid}/{kind}/draft")
    @RolesAllowed({"competence:write"})
    public Response discardDraft(@PathParam("uuid") String uuid, @PathParam("kind") String kind) {
        contentService.discardDraft(uuid, contentKind(kind), actor());
        return Response.noContent().build();
    }

    /**
     * What publishing would do, for the confirmation dialog (§8.7).
     *
     * <p>The dialog has to show real values rather than "the current version": the label is
     * what the status rules compare, so publishing a draft whose label equals the live one
     * leaves everyone green and forces nobody to re-read anything. {@code sameLabel} is that
     * warning, and it is a documented property (§5.3), not a bug to be fixed by the client.
     *
     * <p>Answers {@code 409} when there is no draft, with the same message
     * {@code CompetenceContentService.publish} uses — a preview that disagreed with the action
     * it previews would be worse than no preview.
     */
    @GET
    @Path("/requirements/{uuid}/{kind}/publish-preview")
    @RolesAllowed({"competence:write"})
    public PublishPreviewDTO publishPreview(@PathParam("uuid") String uuid,
                                            @PathParam("kind") String kind,
                                            @QueryParam("forcedRetake") @DefaultValue("true")
                                            boolean forcedRetake) {
        ContentKind contentKind = contentKind(kind);
        List<CompetenceContentVersion> history = contentService.versionHistory(uuid);
        CompetenceContentVersion draft = pick(history, contentKind, ContentStatus.DRAFT);
        if (draft == null) {
            throw new WebApplicationException("No draft to publish", Response.Status.CONFLICT);
        }
        CompetenceContentVersion active = pick(history, contentKind, ContentStatus.ACTIVE);
        String from = active == null ? null : active.getVersionLabel();
        String to = draft.getVersionLabel();

        List<Finding> findings = contentService.unresolvedPlaceholders(uuid, contentKind);
        return new PublishPreviewDTO(from, to, forcedRetake, findings.size(), findings,
                from != null && from.equals(to));
    }

    /**
     * DRAFT → ACTIVE in one transaction. {@code 409} with the placeholder list when the course
     * still carries unresolved {@code [Udfyldes af Trustworks · TW-nn]} callouts and the caller
     * has not acknowledged them (§9.4) — content that admits it is unfinished must not become
     * the thing an employee is measured against by accident.
     */
    @POST
    @Path("/requirements/{uuid}/{kind}/publish")
    @RolesAllowed({"competence:write"})
    public VersionSummaryDTO publish(@PathParam("uuid") String uuid,
                                     @PathParam("kind") String kind,
                                     PublishRequest request) {
        PublishRequest body = PublishRequest.orDefaults(request);
        return VersionSummaryDTO.of(contentService.publish(uuid, contentKind(kind),
                body.forcedRetake(), body.acknowledgeUnresolved(), actor()));
    }

    // -----------------------------------------------------------------------
    // §6.3 — content import / export (competence:write)
    // -----------------------------------------------------------------------

    /**
     * The full content file, or one topic when {@code compId} is given.
     *
     * <p>Served as a download rather than as an inline body: this is the half of the
     * export/edit/import loop an author saves to disk, and a browser that renders it inline
     * makes "save as" the step that mangles the encoding.
     */
    @GET
    @Path("/export/content")
    @Produces(APPLICATION_JSON)
    @RolesAllowed({"competence:write"})
    public Response exportContent(@QueryParam("compId") String compId) {
        String json = contentService.exportContent(compId, actor());
        String stem = (compId == null || compId.isBlank())
                ? "kompetencemodul-full-export"
                : "kompetencemodul-" + safeFilenameToken(compId);
        return download(json, APPLICATION_JSON, stem + "-" + LocalDate.now() + ".json");
    }

    /**
     * Imports a {@code kompetencemodul-full-export} file as DRAFTs. Never activates anything,
     * and writes nothing at all when any topic fails validation — a partially imported
     * four-topic file is a state nobody can reason about. Schema violations come back as a
     * {@code 400} carrying every topic's errors at once.
     */
    @POST
    @Path("/import/content")
    @RolesAllowed({"competence:write"})
    public ImportResultDTO importContent(String json) {
        CompetenceContentService.ImportSummary summary = contentService.importContent(json, actor());
        return new ImportResultDTO(summary.topics(), summary.requirementsCreated(),
                summary.draftsWritten(), summary.compIds());
    }

    // -----------------------------------------------------------------------
    // settings
    // -----------------------------------------------------------------------

    /**
     * Current settings plus the change log.
     *
     * <p>Readable with either key: the threshold and the cadence explain why a cell is red, so
     * an approver needs them to read the matrix, and an author needs them to write a test
     * against them. Only writing is restricted to {@code competence:write}.
     */
    @GET
    @Path("/settings")
    @RolesAllowed({"competence:approve", "competence:write"})
    public SettingsDTO settings(@QueryParam("changeLog") @DefaultValue("25") int changeLogLimit) {
        // The service clamps the limit to 1..500; an absurd value is capped, not refused.
        return currentSettings(changeLogLimit);
    }

    /**
     * Changes one or more settings. Each key is validated and audited individually by
     * {@code CompetenceSettingsService}, in one transaction, so a value can never move without
     * leaving a row behind. Answers with the settings as they now stand, so the confirmation
     * dialog's from → to (§11.3) is read back from the store rather than assumed.
     */
    @PUT
    @Path("/settings")
    @RolesAllowed({"competence:write"})
    public SettingsDTO updateSettings(SettingsUpdateRequest request) {
        if (request == null) {
            throw new WebApplicationException("No settings supplied", Response.Status.BAD_REQUEST);
        }
        settingsService.updateSettings(request.updates(), actor());
        return currentSettings(DEFAULT_CHANGE_LOG_ROWS);
    }

    // -----------------------------------------------------------------------
    // helpers — private, every one of them (see the class javadoc)
    // -----------------------------------------------------------------------

    /**
     * The actor. Always the header, never a parameter — the property that removes the IDOR
     * surface from the whole module (§10.1).
     *
     * <p>Refuses a missing {@code X-Requested-By} with a 400, the same way
     * {@code CompetenceLearnerResource} does, and for a sharper reason. On the
     * {@code competence:approve} half a null actor is already harmless: {@code resolveReach}
     * answers {@code none()} for it, so the matrix, the queue and both CSV exports fail closed.
     * <strong>The {@code competence:write} half has no reach to fail closed on.</strong>
     * Without this guard a request carrying only the client-credentials JWT could publish a
     * content version company-wide, writing NULL into {@code published_by} — the evidence
     * column §10.6 exists for — and leaving {@code actor=null} in the log line that is the only
     * other record of it. "Nobody published this" is not a state the module may be able to
     * reach.
     *
     * <p>400 rather than 401 for the same reason as on the learner surface: the API client
     * authenticated correctly and the request is simply incomplete, and a 401 would send the
     * BFF into a token refresh that cannot fix it.
     */
    private String actor() {
        String useruuid = requestHeaderHolder.getUserUuid();
        if (useruuid == null || useruuid.isBlank()) {
            log.warn("Competence admin request without X-Requested-By — refusing");
            throw new WebApplicationException(
                    "X-Requested-By is required — the competence API always acts for a named user",
                    Response.Status.BAD_REQUEST);
        }
        return useruuid;
    }

    /**
     * {@code {kind}} → {@link ContentKind}, case-insensitively, so {@code /course} and
     * {@code /COURSE} both work.
     *
     * <p>An unknown value is a {@code 400} rather than the {@code IllegalArgumentException}
     * from {@code valueOf} that would surface as a {@code 500}: a client typo is the client's
     * problem to see, and a 500 sends someone reading server logs for it.
     */
    private ContentKind contentKind(String raw) {
        if (raw != null) {
            String trimmed = raw.trim();
            for (ContentKind kind : ContentKind.values()) {
                if (kind.name().equalsIgnoreCase(trimmed)) {
                    return kind;
                }
            }
        }
        throw new WebApplicationException(
                "Unknown content kind '" + clip(raw) + "' — expected 'course' or 'test'",
                Response.Status.BAD_REQUEST);
    }

    /** Assembles the admin view of one requirement. Mapping only; every input is a service call. */
    private RequirementAdminDTO toAdminDTO(CompetenceRequirement requirement) {
        List<CompetenceContentVersion> history =
                contentService.versionHistory(requirement.getUuid());
        return RequirementAdminDTO.of(
                requirement,
                requirementService.targetingOf(requirement),
                pick(history, ContentKind.COURSE, ContentStatus.ACTIVE),
                pick(history, ContentKind.COURSE, ContentStatus.DRAFT),
                pick(history, ContentKind.TEST, ContentStatus.ACTIVE),
                pick(history, ContentKind.TEST, ContentStatus.DRAFT),
                settingsService.effectiveCadenceDays(requirement),
                contentService.unresolvedPlaceholders(requirement.getUuid(), ContentKind.COURSE)
                        .size());
    }

    /**
     * The one version of a kind in a status, from a history already loaded.
     *
     * <p>Filtering in memory rather than issuing four more queries per requirement: the two
     * conditional unique indexes guarantee at most one ACTIVE and one DRAFT per kind, so this
     * is a lookup, not a scan that might return the wrong row.
     */
    private CompetenceContentVersion pick(List<CompetenceContentVersion> history, ContentKind kind,
                                          ContentStatus status) {
        for (CompetenceContentVersion version : history) {
            if (version.getContentKind() == kind && version.getStatus() == status) {
                return version;
            }
        }
        return null;
    }

    /**
     * The stored payload as a tree, for the editor.
     *
     * <p>Stored payloads are written canonically by the codec, so failing here means the row
     * was changed outside the application. That is a {@code 500} naming the version rather
     * than an empty editor, which would invite an author to "fix" it by saving over content
     * they never saw.
     */
    private JsonNode readTree(CompetenceContentVersion draft) {
        try {
            return CompetencePayloadCodec.mapper().readTree(draft.getPayloadJson());
        } catch (Exception e) {
            log.errorf(e, "Unreadable competence draft payload on version %s", draft.getUuid());
            throw new WebApplicationException(
                    "Kladdens indhold kunne ikke læses — kontakt en administrator",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private SettingsDTO currentSettings(int changeLogLimit) {
        // Not named `log`: that is the Lombok logger field, and shadowing it in a method that
        // may later want to log is a trap for whoever adds the line.
        List<CompetenceSettingsAudit> changeLog = settingsService.changeLog(changeLogLimit);
        Set<String> actors = new LinkedHashSet<>();
        for (CompetenceSettingsAudit row : changeLog) {
            actors.add(row.getChangedBy());
        }
        Map<String, String> names = requirementService.displayNamesOf(actors);

        List<SettingsAuditDTO> entries = new ArrayList<>(changeLog.size());
        for (CompetenceSettingsAudit row : changeLog) {
            entries.add(SettingsAuditDTO.of(row, displayName(names, row.getChangedBy())));
        }
        return SettingsDTO.of(settingsService.passThreshold(), settingsService.cadenceDays(),
                settingsService.attemptTimeoutMinutes(), settingsService.retentionYears(), entries);
    }

    /**
     * Falls back to the uuid rather than to a blank, exactly as the CSV exports do: a
     * pseudonymised subject reads as {@code erased:…} and a deleted user reads as a uuid, and
     * both are honest. A blank name would make a row look like a rendering fault.
     */
    private String displayName(Map<String, String> names, String useruuid) {
        if (useruuid == null) {
            return null;
        }
        return names.getOrDefault(useruuid, useruuid);
    }

    /** A dated CSV download. Both exports are access events and both are logged by the service. */
    private Response csvDownload(String csv, String stem) {
        return download(csv, CSV, stem + "-" + LocalDate.now() + ".csv");
    }

    /**
     * {@code Content-Disposition: attachment} plus {@code nosniff}.
     *
     * <p>Every filename reaching here is either a constant or has been through
     * {@link #safeFilenameToken}, so nothing caller-controlled can carry a quote or a CR into
     * the header — header injection through a download name is the standard trick and the
     * defence is that the name is never taken verbatim from a parameter.
     */
    private Response download(String body, String mediaType, String filename) {
        return Response.ok(body, mediaType)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .build();
    }

    /**
     * Reduces a caller-supplied {@code compId} to the slug alphabet before it can appear in a
     * filename. An unknown compId is already a {@code 404} from the export service, so this
     * only ever narrows a value that exists.
     */
    private String safeFilenameToken(String value) {
        String token = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        return token.isEmpty() ? "export" : token.substring(0, Math.min(token.length(), 64));
    }

    /** Bounds an echoed parameter so a malformed request cannot compose a large error body. */
    private String clip(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 40 ? trimmed : trimmed.substring(0, 40) + "…";
    }
}
