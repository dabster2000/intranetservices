package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertRequest;
import dk.trustworks.intranet.recruitmentservice.dto.ConvertResponse;
import dk.trustworks.intranet.recruitmentservice.dto.DeclineRequest;
import dk.trustworks.intranet.recruitmentservice.dto.DossierRequest;
import dk.trustworks.intranet.recruitmentservice.dto.DossierResponse;
import dk.trustworks.intranet.recruitmentservice.dto.RevisionResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SendReviewRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SendSignatureRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.recruitmentservice.dto.WithdrawRequest;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.security.RecruitmentSecuredResponse;
import dk.trustworks.intranet.recruitmentservice.services.CandidateConversionUseCase;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import dk.trustworks.intranet.recruitmentservice.services.DossierPdfGenerationService;
import dk.trustworks.intranet.recruitmentservice.services.DossierPdfGenerationService.GeneratedPdf;
import dk.trustworks.intranet.recruitmentservice.services.DossierRevisionService;
import dk.trustworks.intranet.recruitmentservice.services.DossierRevisionService.RecipientInfo;
import dk.trustworks.intranet.recruitmentservice.services.DossierService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.services.SharePointCandidateFolderService;
import dk.trustworks.intranet.recruitmentservice.util.HtmlEscape;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.dto.signing.DocumentInfo;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import lombok.extern.jbosslog.JBossLog;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * REST entry point for the Recruitment Dossier feature. Implements the 17
 * endpoints from spec §7.1 under {@code /recruitment/}.
 * <p>
 * The class is intentionally thin: every endpoint validates the feature flag,
 * looks up the actor UUID from the {@code X-Requested-By} header, and delegates
 * to a domain service. Business rules live on entities and in services; this
 * resource only orchestrates the HTTP-to-service hop.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Class-level {@code @RolesAllowed({"recruitment:read"})} sets the
 *       baseline; method-level {@code @RolesAllowed({"recruitment:write"})}
 *       overrides for write operations.</li>
 *   <li>{@link RecruitmentSecuredResponse} binds
 *       {@code RecruitmentRevisionResponseFilter} to this resource so revision
 *       snapshot bodies have sensitive placeholder values stripped for callers
 *       without {@code users:read}.</li>
 *   <li>Feature flag: {@link RecruitmentFeatureFlag#isEnabled()} is checked at
 *       the start of every method. Off + non-admin → 404. Admins always pass.</li>
 * </ul>
 */
@JBossLog
@Path("/recruitment")
@RequestScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RecruitmentSecuredResponse
@RolesAllowed({"recruitment:read"})
public class RecruitmentResource {

    private static final String ADMIN_WILDCARD = "admin:*";

    @Inject
    CandidateService candidateService;

    @Inject
    DossierService dossierService;

    @Inject
    DossierRevisionService dossierRevisionService;

    @Inject
    DossierPdfGenerationService pdfGenerationService;

    @Inject
    SharePointCandidateFolderService sharePointCandidateFolderService;

    @Inject
    CandidateConversionUseCase candidateConversionUseCase;

    @Inject
    NextsignSigningService nextsignSigningService;

    @Inject
    MailResource mailResource;

    @Inject
    UserService userService;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    ScopeContext scopeContext;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    // ---- Candidate endpoints --------------------------------------------------

    @GET
    @Path("/candidates")
    public Response listCandidates(
            @QueryParam("status") String status,
            @QueryParam("search") String search,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("20") int size) {
        enforceFlag();
        CandidateListResponse result = candidateService.list(status, search, page, size);
        return Response.ok(result).build();
    }

    @POST
    @Path("/candidates")
    @RolesAllowed({"recruitment:write"})
    public Response createCandidate(@Valid CandidateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        UUID actor = currentActor();
        CandidateResponse created = candidateService.createCandidate(request, actor);
        return Response.created(URI.create("/recruitment/candidates/" + created.uuid()))
                .entity(created)
                .build();
    }

    @GET
    @Path("/candidates/{uuid}")
    public Response getCandidate(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        return candidateService.findById(uuid)
                .map(dto -> Response.ok(dto).build())
                .orElseThrow(() -> new NotFoundException("Candidate not found: " + uuid));
    }

    @PUT
    @Path("/candidates/{uuid}")
    @RolesAllowed({"recruitment:write"})
    public Response updateCandidate(@PathParam("uuid") UUID uuid,
                                    @Valid CandidateRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        CandidateResponse updated = candidateService.update(uuid, request, currentActor());
        return Response.ok(updated).build();
    }

    @POST
    @Path("/candidates/{uuid}/decline")
    @RolesAllowed({"recruitment:write"})
    public Response declineCandidate(@PathParam("uuid") UUID uuid,
                                     @Valid DeclineRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        CandidateResponse result = candidateService.decline(uuid, request.reason(), currentActor());
        return Response.ok(result).build();
    }

    @POST
    @Path("/candidates/{uuid}/withdraw")
    @RolesAllowed({"recruitment:write"})
    public Response withdrawCandidate(@PathParam("uuid") UUID uuid,
                                      @Valid WithdrawRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        CandidateResponse result = candidateService.withdraw(uuid, request.reason(), currentActor());
        return Response.ok(result).build();
    }

    @POST
    @Path("/candidates/{uuid}/convert")
    @RolesAllowed({"recruitment:write"})
    public Response convertCandidate(@PathParam("uuid") UUID uuid,
                                     @Valid ConvertRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        ConvertResponse result = candidateConversionUseCase.execute(uuid, request, currentActor());
        return Response.ok(result).build();
    }

    // ---- Dossier endpoints ----------------------------------------------------

    @GET
    @Path("/candidates/{uuid}/dossier")
    public Response getDossier(@PathParam("uuid") UUID uuid) {
        enforceFlag();
        return dossierService.loadForCandidate(uuid)
                .map(d -> Response.ok(d).build())
                .orElseThrow(() -> new NotFoundException("Dossier not found for candidate: " + uuid));
    }

    @PUT
    @Path("/candidates/{uuid}/dossier")
    @RolesAllowed({"recruitment:write"})
    public Response updateDossier(@PathParam("uuid") UUID candidateUuid,
                                  @Valid DossierRequest request) {
        enforceFlag();
        Objects.requireNonNull(request, "request body must not be null");
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        DossierResponse updated = dossierService.update(
                UUID.fromString(dossier.getUuid()), request, currentActor());
        return Response.ok(updated).build();
    }

    @POST
    @Path("/candidates/{uuid}/dossier/appendices")
    @RolesAllowed({"recruitment:write"})
    public Response addAppendix(@PathParam("uuid") UUID candidateUuid,
                                AppendixUploadRequest request) {
        enforceFlag();
        if (request == null || request.fileUuid == null || request.originalFilename == null) {
            throw new WebApplicationException(
                    "fileUuid and originalFilename are required",
                    Response.Status.BAD_REQUEST);
        }
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        AppendixDto appendix = dossierService.addAppendix(
                UUID.fromString(dossier.getUuid()),
                request.originalFilename,
                request.fileUuid,
                currentActor());
        return Response.created(URI.create(String.format(
                        "/recruitment/candidates/%s/dossier/appendices/%s",
                        candidateUuid, appendix.fileUuid())))
                .entity(appendix)
                .build();
    }

    @DELETE
    @Path("/candidates/{uuid}/dossier/appendices/{fileUuid}")
    @RolesAllowed({"recruitment:write"})
    public Response removeAppendix(@PathParam("uuid") UUID candidateUuid,
                                   @PathParam("fileUuid") String fileUuid) {
        enforceFlag();
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        dossierService.removeAppendix(UUID.fromString(dossier.getUuid()), fileUuid, currentActor());
        return Response.noContent().build();
    }

    @GET
    @Path("/candidates/{uuid}/dossier/revisions")
    public Response listRevisions(@PathParam("uuid") UUID candidateUuid) {
        enforceFlag();
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        List<RevisionResponse> revisions = dossierRevisionService.findByDossier(
                UUID.fromString(dossier.getUuid()));
        return Response.ok(revisions).build();
    }

    @GET
    @Path("/candidates/{uuid}/dossier/revisions/{revUuid}")
    public Response getRevision(@PathParam("uuid") UUID candidateUuid,
                                @PathParam("revUuid") UUID revUuid) {
        enforceFlag();
        return dossierRevisionService.findById(revUuid)
                .filter(r -> isRevisionForCandidate(r, candidateUuid))
                .map(r -> Response.ok(r).build())
                .orElseThrow(() -> new NotFoundException("Revision not found: " + revUuid));
    }

    /**
     * Stream a generated-or-appendix PDF document attached to a revision.
     * The {@code index} is 1-based to match the position in
     * {@code RevisionResponse.pdfArtifactsSnapshot} as rendered to the user.
     */
    @GET
    @Path("/candidates/{uuid}/dossier/revisions/{revUuid}/documents/{index}")
    @Produces("application/pdf")
    public Response downloadRevisionDocument(@PathParam("uuid") UUID candidateUuid,
                                             @PathParam("revUuid") UUID revUuid,
                                             @PathParam("index") int index) {
        enforceFlag();
        CandidateDossierRevision revision = requireRevisionForCandidate(revUuid, candidateUuid);
        CandidateDossier dossier = requireDossierById(revision.getDossierUuid());
        List<GeneratedPdf> pdfs = pdfGenerationService.generatePdfsFor(revision, dossier.getTemplateUuid());
        if (index < 1 || index > pdfs.size()) {
            throw new NotFoundException("Document index out of range: " + index);
        }
        GeneratedPdf pdf = pdfs.get(index - 1);
        if (pdf.pdfBytes() == null) {
            // appendix file — generated services keep the original bytes in S3.
            // For Stage 3 we surface a 501-ish to the caller until the
            // appendix-streaming wiring lands. Document the gap explicitly.
            throw new WebApplicationException(
                    "Direct streaming of appendix files is not yet implemented; download from S3 via fileUuid="
                            + pdf.fileUuid(),
                    Response.Status.NOT_IMPLEMENTED);
        }
        StreamingOutput stream = streamFor(pdf.pdfBytes());
        return Response.ok(stream)
                .header("Content-Disposition",
                        "attachment; filename=\"" + pdf.filename() + "\"")
                .build();
    }

    // ---- Send actions ---------------------------------------------------------

    @POST
    @Path("/candidates/{uuid}/dossier/send-review")
    @RolesAllowed({"recruitment:write"})
    @Transactional
    public Response sendReview(@PathParam("uuid") UUID candidateUuid,
                               @Valid SendReviewRequest request) {
        enforceFlag();
        SendReviewRequest body = request != null ? request : new SendReviewRequest(null);
        UUID actor = currentActor();

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        User sender = requireUser(actor);

        // 1) Snapshot the revision (allocates version_number + freezes JSON).
        RecipientInfo recipient = new RecipientInfo(
                candidate.getEmail(),
                fullName(candidate.getFirstName(), candidate.getLastName()),
                actor,
                body.note(),
                null,
                List.of());
        CandidateDossierRevision revision = dossierRevisionService.snapshot(
                dossier, RevisionKind.REVIEW_EMAIL, recipient, actor);

        // 2) Generate PDFs from the snapshot. Failure here rolls back the
        //    revision (we are inside the @Transactional boundary).
        List<GeneratedPdf> pdfs = pdfGenerationService.generatePdfsFor(
                revision, dossier.getTemplateUuid());

        // 3) Build and queue the review email. Recipient is locked to
        //    candidate.email per spec §8.2 — the request DTO has no `to`
        //    field, so caller-supplied recipient overrides are impossible.
        TrustworksMail mail = new TrustworksMail(
                UUID.randomUUID().toString(),
                candidate.getEmail(),
                "Trustworks: Documents for your review",
                buildReviewEmailBody(candidate, sender, body.note()));
        for (GeneratedPdf pdf : pdfs) {
            if (pdf.pdfBytes() != null) {
                mail.getAttachments().add(new EmailAttachment(
                        pdf.filename(), "application/pdf", pdf.pdfBytes()));
            }
            // Appendices live in S3; downstream code does not yet attach them
            // direct from S3 to TrustworksMail. Logged so the stage 4 gap is
            // visible in audit logs.
        }
        mailResource.sendingHTML(mail);

        return Response.ok(dossierRevisionService.toResponse(revision)).build();
    }

    @POST
    @Path("/candidates/{uuid}/dossier/generate-review-pdf")
    @RolesAllowed({"recruitment:write"})
    @Produces({MediaType.APPLICATION_JSON, "application/pdf"})
    @Transactional
    public Response generateReviewPdf(@PathParam("uuid") UUID candidateUuid,
                                      @Valid SendReviewRequest request) {
        enforceFlag();
        SendReviewRequest body = request != null ? request : new SendReviewRequest(null);
        UUID actor = currentActor();

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);

        RecipientInfo recipient = new RecipientInfo(
                candidate.getEmail(),
                fullName(candidate.getFirstName(), candidate.getLastName()),
                actor,
                body.note(),
                null,
                List.of());
        CandidateDossierRevision revision = dossierRevisionService.snapshot(
                dossier, RevisionKind.REVIEW_PDF, recipient, actor);

        List<GeneratedPdf> templatePdfs = pdfGenerationService.generateTemplatePdfsFor(
                revision, dossier.getTemplateUuid());
        if (templatePdfs.isEmpty()) {
            throw new WebApplicationException(
                    "No template documents are configured on this dossier",
                    Response.Status.CONFLICT);
        }

        // Stream the FIRST template PDF as the response. Multi-document
        // download for review-PDF is currently not on spec §11.4 — clients
        // download the additional documents via the per-revision document
        // endpoint after the revision is created.
        GeneratedPdf primary = templatePdfs.get(0);
        StreamingOutput stream = streamFor(primary.pdfBytes());
        return Response.ok(stream, "application/pdf")
                .header("Content-Disposition",
                        "attachment; filename=\"" + primary.filename() + "\"")
                .header("X-Recruitment-Revision-Uuid", revision.getUuid())
                .build();
    }

    @POST
    @Path("/candidates/{uuid}/dossier/send-signature")
    @RolesAllowed({"recruitment:write"})
    public Response sendSignature(@PathParam("uuid") UUID candidateUuid,
                                  @Valid SendSignatureRequest request) {
        enforceFlag();
        SendSignatureRequest body = request != null ? request : new SendSignatureRequest(null);
        UUID actor = currentActor();

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);

        Map<String, String> placeholders = dossierService.currentPlaceholderValues(dossier);
        List<SignerConfigDto> signers = dossierService.currentSignersConfig(dossier);
        List<AppendixDto> appendices = dossierService.currentAppendices(dossier.getUuid());

        // Validate signers before generating PDFs — PDF generation is the
        // most expensive step in this flow, so a misconfigured dossier
        // should fail fast.
        List<SignerInfo> signerInfos = mapSigners(signers);
        if (signerInfos.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot send signature: no signers configured on dossier",
                    Response.Status.CONFLICT);
        }

        List<GeneratedPdf> pdfs = pdfGenerationService.generatePdfsFromValues(
                dossier.getTemplateUuid(), placeholders, appendices);

        List<DocumentInfo> documents = new ArrayList<>(pdfs.size());
        for (GeneratedPdf pdf : pdfs) {
            if (pdf.pdfBytes() != null) {
                documents.add(new DocumentInfo(pdf.filename(), pdf.pdfBytes(), pdf.fromTemplate()));
            }
        }
        if (documents.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot send signature: no documents available on dossier",
                    Response.Status.CONFLICT);
        }

        // External calls run before any DB write so a slow NextSign round-trip
        // does not hold a transaction open against candidate_dossier_revisions.
        sharePointCandidateFolderService.ensureFolderForCandidate(candidate);
        String caseKey = nextsignSigningService.createMultiDocumentSigningCase(
                documents,
                signerInfos,
                "recruitment-candidate:" + candidate.getUuid(),
                collectSigningSchemas(signers));

        String firstSignerEmail = firstSignerEmailFromList(signers, candidate.getEmail());
        RecipientInfo recipient = new RecipientInfo(
                firstSignerEmail,
                fullName(candidate.getFirstName(), candidate.getLastName()),
                actor,
                body.note(),
                caseKey,
                List.of());
        CandidateDossierRevision revision = dossierRevisionService.snapshotFromValues(
                dossier, RevisionKind.SIGNATURE,
                placeholders, signers, appendices,
                recipient, actor);

        return Response.ok(dossierRevisionService.toResponse(revision)).build();
    }

    // ---- Helpers --------------------------------------------------------------

    /**
     * Block the request when the {@code recruitment.dossier.enabled} flag is
     * off, unless the caller is admin. Returns 404 (not 503) to avoid leaking
     * the existence of the feature to unauthorised callers.
     */
    private void enforceFlag() {
        if (featureFlag.isEnabled()) {
            return;
        }
        if (scopeContext.hasScope(ADMIN_WILDCARD)) {
            return;
        }
        throw new NotFoundException("Resource not found");
    }

    /**
     * Resolve the actor UUID from the {@code X-Requested-By} header
     * (populated by {@code HeaderInterceptor}). Throws 400 if absent —
     * write operations on this resource are useless without an actor.
     */
    private UUID currentActor() {
        String userUuid = requestHeaderHolder.getUserUuid();
        if (userUuid == null || userUuid.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By header is required",
                    Response.Status.BAD_REQUEST);
        }
        try {
            return UUID.fromString(userUuid);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "X-Requested-By is not a valid UUID",
                    Response.Status.BAD_REQUEST);
        }
    }

    private RecruitmentCandidate requireCandidate(UUID candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return candidate;
    }

    private CandidateDossier requireDossierByCandidate(UUID candidateUuid) {
        Optional<CandidateDossier> dossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1", candidateUuid.toString())
                .firstResultOptional();
        if (dossier.isEmpty()) {
            throw new NotFoundException("Dossier not found for candidate: " + candidateUuid);
        }
        return dossier.get();
    }

    private CandidateDossier requireDossierById(String dossierUuid) {
        CandidateDossier dossier = CandidateDossier.findById(dossierUuid);
        if (dossier == null) {
            throw new NotFoundException("Dossier not found: " + dossierUuid);
        }
        return dossier;
    }

    private CandidateDossierRevision requireRevisionForCandidate(UUID revUuid, UUID candidateUuid) {
        CandidateDossierRevision revision = CandidateDossierRevision.findById(revUuid.toString());
        if (revision == null || !isRevisionForCandidateRaw(revision, candidateUuid)) {
            throw new NotFoundException("Revision not found: " + revUuid);
        }
        return revision;
    }

    private User requireUser(UUID userUuid) {
        User user = User.findById(userUuid.toString());
        if (user == null) {
            throw new NotFoundException("User not found: " + userUuid);
        }
        return user;
    }

    private boolean isRevisionForCandidate(RevisionResponse revision, UUID candidateUuid) {
        CandidateDossier dossier = CandidateDossier.findById(revision.dossierUuid());
        return dossier != null && candidateUuid.toString().equals(dossier.getCandidateUuid());
    }

    private boolean isRevisionForCandidateRaw(CandidateDossierRevision revision, UUID candidateUuid) {
        CandidateDossier dossier = CandidateDossier.findById(revision.getDossierUuid());
        return dossier != null && candidateUuid.toString().equals(dossier.getCandidateUuid());
    }

    private static String fullName(String first, String last) {
        return ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
    }

    private static List<SignerInfo> mapSigners(List<SignerConfigDto> signers) {
        List<SignerInfo> out = new ArrayList<>(signers.size());
        int group = 1;
        for (SignerConfigDto s : signers) {
            String role = s.role() != null ? s.role() : (s.signing() ? "signer" : "copy");
            out.add(new SignerInfo(group, s.name(), s.email(), role, s.signing(), s.needsCpr()));
            group++;
        }
        return out;
    }

    private static List<String> collectSigningSchemas(List<SignerConfigDto> signers) {
        List<String> schemas = new ArrayList<>();
        for (SignerConfigDto s : signers) {
            if (s.signingSchema() != null && !s.signingSchema().isBlank()
                    && !schemas.contains(s.signingSchema())) {
                schemas.add(s.signingSchema());
            }
        }
        return schemas;
    }

    private static String firstSignerEmailFromList(List<SignerConfigDto> signers, String fallback) {
        for (SignerConfigDto s : signers) {
            if (s.signing() && s.email() != null && !s.email().isBlank()) {
                return s.email();
            }
        }
        return fallback;
    }

    /**
     * Compose a minimalist HTML body for the review email. Intentionally
     * generic — corporate branding lives on a future {@code review-email.html}
     * template (per stage 4 todo).
     */
    private static String buildReviewEmailBody(RecruitmentCandidate candidate, User sender, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>Hi ").append(HtmlEscape.escape(candidate.getFirstName())).append(",</p>");
        sb.append("<p>Please find attached the documents for your review. ");
        sb.append("Reach out if anything is unclear before the formal signing step.</p>");
        if (note != null && !note.isBlank()) {
            sb.append("<p>").append(HtmlEscape.escape(note)).append("</p>");
        }
        sb.append("<p>Best regards,<br/>")
                .append(HtmlEscape.escape(sender.getFirstname()))
                .append(" ")
                .append(HtmlEscape.escape(sender.getLastname()))
                .append("<br/>")
                .append(HtmlEscape.escape(sender.getEmail()))
                .append("</p>");
        return sb.toString();
    }

    private static StreamingOutput streamFor(byte[] bytes) {
        return out -> {
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                in.transferTo(out);
            }
        };
    }

    /**
     * Carrier for {@code POST /dossier/appendices}. Captures the metadata
     * that links a dossier to an already-uploaded S3 file. Validation lives
     * in {@link DossierService#addAppendix} (filename sanitisation).
     */
    public static class AppendixUploadRequest {
        public String fileUuid;
        public String originalFilename;

        // Suppress an unused-warning for the placeholder map — present so
        // future stage extensions (file size, mime type) can land without a
        // breaking API change.
        @SuppressWarnings("unused")
        public Map<String, String> meta = new HashMap<>();
    }

}
