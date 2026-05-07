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
import dk.trustworks.intranet.recruitmentservice.dto.RevisionSigningStatusSummary;
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
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentS3StorageService;
import dk.trustworks.intranet.recruitmentservice.util.HtmlEscape;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import dk.trustworks.intranet.signing.domain.SigningCase;
import dk.trustworks.intranet.signing.repository.SigningCaseRepository;
import dk.trustworks.intranet.utils.NextsignSigningService;
import dk.trustworks.intranet.utils.dto.nextsign.NextSignCaseDetailDTO;
import dk.trustworks.intranet.utils.dto.signing.DocumentInfo;
import dk.trustworks.intranet.utils.dto.signing.SignerInfo;
import dk.trustworks.intranet.utils.services.SigningService;
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
import org.eclipse.microprofile.context.ManagedExecutor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
    RecruitmentS3StorageService recruitmentS3StorageService;

    @Inject
    CandidateConversionUseCase candidateConversionUseCase;

    @Inject
    NextsignSigningService nextsignSigningService;

    @Inject
    SigningService signingService;

    @Inject
    SigningCaseRepository signingCaseRepository;

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

    /**
     * MicroProfile {@link ManagedExecutor} used to dispatch the post-commit
     * SharePoint copy after a successful candidate conversion. Running the
     * copy off the request thread releases DB locks fast and keeps the
     * convert-candidate REST response sub-100ms (efficiency finding H2).
     * The copy itself is best-effort; the
     * {@link dk.trustworks.intranet.recruitmentservice.jobs.SharePointEmployeeFolderMoveBatchlet}
     * retries any rows still in PENDING/PARTIAL/FAILED.
     */
    @Inject
    ManagedExecutor managedExecutor;

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

        // Fire-and-forget the SharePoint copy after the conversion tx has
        // committed. Doing it inline would hold DB row locks on the
        // candidate/dossier/revision/appendix tables for 2-8 seconds while
        // Graph API uploads each PDF (efficiency finding H2). On failure,
        // sharepoint_move_status stays PENDING/PARTIAL/FAILED and the retry
        // batchlet picks it up on its 5-minute cadence — no caller retry
        // needed.
        final UUID asyncCandidateUuid = uuid;
        managedExecutor.execute(() -> {
            try {
                candidateConversionUseCase.runSharePointCopy(asyncCandidateUuid);
            } catch (Exception e) {
                log.errorf(e, "Async SharePoint copy failed for candidate=%s — batchlet will retry",
                        asyncCandidateUuid);
            }
        });

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
        if (request == null || request.originalFilename == null || request.originalFilename.isBlank()) {
            throw new WebApplicationException(
                    "originalFilename is required",
                    Response.Status.BAD_REQUEST);
        }
        boolean hasFileContent = request.fileContent != null && !request.fileContent.isBlank();
        boolean hasFileUuid = request.fileUuid != null && !request.fileUuid.isBlank();
        if (!hasFileContent && !hasFileUuid) {
            throw new WebApplicationException(
                    "Either fileContent (base64) or fileUuid is required",
                    Response.Status.BAD_REQUEST);
        }

        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);

        String fileUuid;
        if (hasFileContent) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(request.fileContent);
            } catch (IllegalArgumentException e) {
                throw new WebApplicationException(
                        "Invalid fileContent (base64 decode failed)",
                        Response.Status.BAD_REQUEST);
            }
            fileUuid = recruitmentS3StorageService.storeAppendix(
                    bytes, request.originalFilename, candidateUuid);
        } else {
            fileUuid = request.fileUuid;
        }

        boolean signObligated = request.signObligated == null || request.signObligated;
        AppendixDto appendix = dossierService.addAppendix(
                UUID.fromString(dossier.getUuid()),
                request.originalFilename,
                fileUuid,
                signObligated,
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

    /**
     * Fetches the live NextSign signing case detail for a SIGNATURE-kind
     * revision. Reuses {@link SigningService#getCaseDetail(String)} — the same
     * call used by the admin signing-cases tab — so per-signer audit trail and
     * identity verification data is current. The freshness trade-off is
     * round-trip latency (200-500ms) instead of cached staleness.
     *
     * <p>Authorization-by-ownership: the revision UUID must belong to a
     * dossier owned by the candidate UUID in the path. URL-guessed revision
     * UUIDs cannot leak signing detail across candidates.</p>
     */
    @GET
    @Path("/candidates/{uuid}/dossier/revisions/{revUuid}/signing-status")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"recruitment:read"})
    @Transactional
    public Response getSigningStatus(
            @PathParam("uuid") UUID candidateUuid,
            @PathParam("revUuid") UUID revisionUuid) {

        enforceFlag();

        CandidateDossierRevision revision = CandidateDossierRevision.findById(revisionUuid.toString());
        if (revision == null
                || revision.getKind() != RevisionKind.SIGNATURE
                || revision.getSigningCaseKey() == null
                || revision.getSigningCaseKey().isBlank()) {
            throw new NotFoundException(
                    "No signing case for revision " + revisionUuid);
        }

        // Authorization-by-ownership: confirm the revision belongs to this
        // candidate's dossier so URL-guessed revision UUIDs cannot leak.
        CandidateDossier dossier = CandidateDossier.findById(revision.getDossierUuid());
        if (dossier == null || !dossier.getCandidateUuid().equals(candidateUuid.toString())) {
            throw new NotFoundException(
                    "Revision " + revisionUuid + " does not belong to candidate " + candidateUuid);
        }

        try {
            NextSignCaseDetailDTO detail = signingService.getCaseDetail(revision.getSigningCaseKey());
            return Response.ok(detail).build();
        } catch (SigningService.SigningException e) {
            log.warnf("NextSign case detail not found for revision=%s caseKey=%s — %s",
                    revisionUuid, revision.getSigningCaseKey(), e.getMessage());
            throw new NotFoundException(
                    "NextSign case " + revision.getSigningCaseKey() + " not found");
        }
    }

    /**
     * Lightweight signing-status summary for the collapsed view of the
     * recruitment dossier panel. Reads entirely from the local
     * {@code signing_cases} cache populated by {@code NextSignStatusSyncBatchlet}
     * — NO NextSign API call. Per-signer audit log / identity verification
     * are not available from this endpoint; expand the panel to trigger the
     * full {@link #getSigningStatus(UUID, UUID)} endpoint.
     *
     * <p>Same authorization-by-ownership rule as the full endpoint: the
     * revision UUID must belong to a dossier owned by the candidate UUID.</p>
     */
    @GET
    @Path("/candidates/{uuid}/dossier/revisions/{revUuid}/signing-status/summary")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"recruitment:read"})
    @Transactional
    public Response getSigningStatusSummary(
            @PathParam("uuid") UUID candidateUuid,
            @PathParam("revUuid") UUID revisionUuid) {

        enforceFlag();

        CandidateDossierRevision revision = CandidateDossierRevision.findById(revisionUuid.toString());
        if (revision == null
                || revision.getKind() != RevisionKind.SIGNATURE
                || revision.getSigningCaseKey() == null
                || revision.getSigningCaseKey().isBlank()) {
            throw new NotFoundException(
                    "No signing case for revision " + revisionUuid);
        }

        // Authorization-by-ownership: confirm the revision belongs to this
        // candidate's dossier so URL-guessed revision UUIDs cannot leak.
        CandidateDossier dossier = CandidateDossier.findById(revision.getDossierUuid());
        if (dossier == null || !dossier.getCandidateUuid().equals(candidateUuid.toString())) {
            throw new NotFoundException(
                    "Revision " + revisionUuid + " does not belong to candidate " + candidateUuid);
        }

        SigningCase sc = signingCaseRepository.findByCaseKey(revision.getSigningCaseKey())
                .orElseThrow(() -> new NotFoundException("Signing case not in local cache yet"));

        return Response.ok(new RevisionSigningStatusSummary(
                sc.getCaseKey(),
                sc.getStatus(),
                sc.getTotalSigners() != null ? sc.getTotalSigners() : 0,
                sc.getCompletedSigners() != null ? sc.getCompletedSigners() : 0,
                sc.getLastStatusFetch()
        )).build();
    }

    @POST
    @Path("/candidates/{uuid}/dossier/branch-from-revision/{revUuid}")
    @Produces(MediaType.APPLICATION_JSON)
    @RolesAllowed({"recruitment:write"})
    @Transactional
    public DossierResponse branchFromRevision(
            @PathParam("uuid") UUID candidateUuid,
            @PathParam("revUuid") UUID revisionUuid) {
        enforceFlag();
        return dossierService.branchFromRevision(candidateUuid, revisionUuid, currentActor());
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
        if (body.note() == null || body.note().isBlank()) {
            throw new WebApplicationException(
                    "A message is required — the email body is exclusively this note.",
                    Response.Status.BAD_REQUEST);
        }
        UUID actor = currentActor();

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);
        User sender = requireUser(actor);

        // 1) Generate PDFs from the current draft (so we have bytes to store
        //    in S3 before the revision row references them).
        Map<String, String> placeholders = dossierService.currentPlaceholderValues(dossier);
        List<SignerConfigDto> signers = dossierService.currentSignersConfig(dossier);
        List<AppendixDto> appendices = dossierService.currentAppendices(dossier.getUuid());
        List<GeneratedPdf> pdfs = pdfGenerationService.generatePdfsFromValues(
                dossier.getTemplateUuid(), placeholders, appendices);

        // 2) Persist each template-generated PDF to S3 (best-effort — the
        //    audit-side store must not block the user-facing email when the
        //    in-memory bytes are valid attachments).
        List<RevisionResponse.PdfArtifactRef> pdfRefs = storeTemplatePdfsBestEffort(
                pdfs, candidateUuid, RevisionKind.REVIEW_EMAIL);

        // 3) Snapshot the revision (S3 fileUuids land in generated_pdfs_snapshot).
        RecipientInfo recipient = new RecipientInfo(
                candidate.getEmail(),
                fullName(candidate.getFirstName(), candidate.getLastName()),
                actor,
                body.note(),
                null,
                pdfRefs);
        CandidateDossierRevision revision = dossierRevisionService.snapshotFromValues(
                dossier, RevisionKind.REVIEW_EMAIL,
                placeholders, signers, appendices,
                recipient, actor);

        // 4) Build and send the review email immediately (so the transient
        //    PDF attachments and Reply-To survive — the queued path persists
        //    only the row, dropping both). Recipient is locked to
        //    candidate.email per spec §8.2 — the request DTO has no `to`
        //    field, so caller-supplied recipient overrides are impossible.
        TrustworksMail mail = new TrustworksMail(
                UUID.randomUUID().toString(),
                candidate.getEmail(),
                "Trustworks: Dokumenter til gennemlæsning / Documents for your review",
                buildReviewEmailBody(body.note()));
        mail.setReplyTo(sender.getEmail());
        for (GeneratedPdf pdf : materializePdfBytes(pdfs)) {
            if (pdf.pdfBytes() == null) continue;
            mail.getAttachments().add(new EmailAttachment(
                    pdf.filename(), "application/pdf", pdf.pdfBytes()));
        }
        mailResource.sendWithAttachments(mail);

        return Response.ok(dossierRevisionService.toResponse(revision)).build();
    }

    @POST
    @Path("/candidates/{uuid}/dossier/generate-review-pdf")
    @RolesAllowed({"recruitment:write"})
    @Produces({MediaType.APPLICATION_JSON, "application/zip"})
    @Transactional
    public Response generateReviewPdf(@PathParam("uuid") UUID candidateUuid,
                                      @Valid SendReviewRequest request) {
        enforceFlag();
        SendReviewRequest body = request != null ? request : new SendReviewRequest(null);
        UUID actor = currentActor();

        RecruitmentCandidate candidate = requireCandidate(candidateUuid);
        CandidateDossier dossier = requireDossierByCandidate(candidateUuid);

        // 1) Generate PDFs from the current draft (so bytes exist before the
        //    revision row references them in generated_pdfs_snapshot).
        Map<String, String> placeholders = dossierService.currentPlaceholderValues(dossier);
        List<SignerConfigDto> signers = dossierService.currentSignersConfig(dossier);
        List<AppendixDto> appendices = dossierService.currentAppendices(dossier.getUuid());
        List<GeneratedPdf> allPdfs = pdfGenerationService.generatePdfsFromValues(
                dossier.getTemplateUuid(), placeholders, appendices);
        List<GeneratedPdf> templatePdfs = allPdfs.stream()
                .filter(GeneratedPdf::fromTemplate)
                .toList();
        if (templatePdfs.isEmpty()) {
            throw new WebApplicationException(
                    "No template documents are configured on this dossier",
                    Response.Status.CONFLICT);
        }

        // 2) Assemble the ZIP from the in-memory bytes FIRST so the audit-side
        //    S3 store cannot fail the user-facing download. Includes both
        //    template-derived PDFs (bytes already in memory) and appendices
        //    (bytes fetched from S3 via materializePdfBytes).
        byte[] zipBytes = zipPdfs(materializePdfBytes(allPdfs));
        String zipName = zipFilenameFor(candidate);

        // 3) Persist each template-generated PDF to S3 (best-effort — empty
        //    refs land in generated_pdfs_snapshot if the upload hiccups; the
        //    revision row still records the action and the user still gets the
        //    documents).
        List<RevisionResponse.PdfArtifactRef> pdfRefs = storeTemplatePdfsBestEffort(
                templatePdfs, candidateUuid, RevisionKind.REVIEW_PDF);

        // 4) Snapshot the revision (S3 fileUuids land in generated_pdfs_snapshot).
        RecipientInfo recipient = new RecipientInfo(
                candidate.getEmail(),
                fullName(candidate.getFirstName(), candidate.getLastName()),
                actor,
                body.note(),
                null,
                pdfRefs);
        CandidateDossierRevision revision = dossierRevisionService.snapshotFromValues(
                dossier, RevisionKind.REVIEW_PDF,
                placeholders, signers, appendices,
                recipient, actor);

        // 5) Stream the ZIP back to the manager for download.
        StreamingOutput stream = streamFor(zipBytes);
        return Response.ok(stream, "application/zip")
                .header("Content-Disposition",
                        "attachment; filename=\"" + zipName + "\"")
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

        // Resolve appendix bytes from S3 so NextSign receives every dossier
        // document, not just the template-rendered ones. Templates already
        // carry their bytes; appendices arrive with only a fileUuid.
        List<GeneratedPdf> pdfsWithBytes = materializePdfBytes(pdfs);

        List<DocumentInfo> documents = new ArrayList<>(pdfsWithBytes.size());
        for (GeneratedPdf pdf : pdfsWithBytes) {
            documents.add(new DocumentInfo(pdf.filename(), pdf.pdfBytes(), pdf.signObligated()));
        }
        if (documents.isEmpty()) {
            throw new WebApplicationException(
                    "Cannot send signature: no documents available on dossier",
                    Response.Status.CONFLICT);
        }

        // Persist each template-generated PDF to S3 before the revision row
        // references them in generated_pdfs_snapshot.
        List<RevisionResponse.PdfArtifactRef> pdfRefs = recruitmentS3StorageService.storeTemplatePdfs(
                pdfs, candidateUuid, RevisionKind.SIGNATURE);

        // External calls run before any DB write so a slow NextSign round-trip
        // does not hold a transaction open against candidate_dossier_revisions.
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
                pdfRefs);
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

    /**
     * Wrap {@link RecruitmentS3StorageService#storeTemplatePdfs} so a transient
     * S3 upload failure cannot block the user-facing email/ZIP. The store is
     * pure audit (revision row's {@code generated_pdfs_snapshot}) — the actual
     * PDF bytes already live in memory and have already been delivered to the
     * caller (or are about to be). On failure we log a {@code warn} and return
     * an empty ref list; the revision row still records the action.
     */
    private List<RevisionResponse.PdfArtifactRef> storeTemplatePdfsBestEffort(
            List<GeneratedPdf> pdfs, UUID candidateUuid, RevisionKind kind) {
        try {
            return recruitmentS3StorageService.storeTemplatePdfs(pdfs, candidateUuid, kind);
        } catch (RuntimeException e) {
            log.warnf(e, "S3 audit-store failed for candidate=%s kind=%s — proceeding with empty pdf refs",
                    candidateUuid, kind);
            return List.of();
        }
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
     * Compose the review email body. The body is exclusively the manager's
     * note (escaped for safety) — no template greeting or sign-off — so the
     * recipient sees only what the sender wrote. The note is required by
     * {@link SendReviewRequest} validation, so we never produce an empty body.
     * Newlines in the note are preserved as paragraph breaks.
     */
    private static String buildReviewEmailBody(String note) {
        StringBuilder sb = new StringBuilder();
        for (String paragraph : note.trim().split("\\R{2,}")) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) continue;
            sb.append("<p>")
                    .append(HtmlEscape.escape(trimmed).replace("\n", "<br/>"))
                    .append("</p>");
        }
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
     * Resolve the bytes of every PDF in the list. Template-generated PDFs
     * already carry their bytes; appendices arrive with only an S3
     * {@code fileUuid} and need a GET to materialise. Returns a new list
     * (input order preserved) where every entry has non-null
     * {@code pdfBytes}, ready for emailing or zipping. Skips entries that
     * have neither bytes nor a fileUuid (defensive — shouldn't happen).
     */
    private List<GeneratedPdf> materializePdfBytes(List<GeneratedPdf> pdfs) {
        List<GeneratedPdf> out = new ArrayList<>(pdfs.size());
        for (GeneratedPdf pdf : pdfs) {
            if (pdf.pdfBytes() != null) {
                out.add(pdf);
                continue;
            }
            if (pdf.fileUuid() == null || pdf.fileUuid().isBlank()) {
                log.warnf("Skipping PDF with no bytes and no fileUuid: %s", pdf.filename());
                continue;
            }
            byte[] bytes = recruitmentS3StorageService.fetchGeneratedPdf(pdf.fileUuid());
            out.add(new GeneratedPdf(pdf.filename(), pdf.fileUuid(), bytes,
                    pdf.fromTemplate(), pdf.signObligated()));
        }
        return out;
    }

    /**
     * Pack each PDF as a separate entry in a single ZIP. Used by
     * {@code POST /dossier/generate-review-pdf} so the manager downloads
     * all dossier documents — template-derived PDFs AND appendices — in
     * one click without server-side merging (per spec §5.4 the manager is
     * the one composing the review). Caller is responsible for resolving
     * appendix bytes via {@link #materializePdfBytes(List)} first.
     * Duplicate {@code filename} values are disambiguated with a numeric
     * suffix to avoid clobbering entries inside the archive.
     */
    private static byte[] zipPdfs(List<GeneratedPdf> pdfs) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Map<String, Integer> nameUseCounts = new HashMap<>();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (GeneratedPdf pdf : pdfs) {
                if (pdf.pdfBytes() == null) continue;
                String entryName = uniqueZipEntryName(pdf.filename(), nameUseCounts);
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(pdf.pdfBytes());
                zos.closeEntry();
            }
        } catch (IOException e) {
            throw new WebApplicationException(
                    "Failed to assemble review ZIP: " + e.getMessage(),
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
        return baos.toByteArray();
    }

    private static String uniqueZipEntryName(String filename, Map<String, Integer> seen) {
        String base = (filename == null || filename.isBlank()) ? "document.pdf" : filename;
        int count = seen.getOrDefault(base, 0);
        seen.put(base, count + 1);
        if (count == 0) return base;
        int dot = base.lastIndexOf('.');
        if (dot <= 0) return base + "-" + (count + 1);
        return base.substring(0, dot) + "-" + (count + 1) + base.substring(dot);
    }

    private static String zipFilenameFor(RecruitmentCandidate candidate) {
        String name = (candidate.getFirstName() + "-" + candidate.getLastName())
                .trim()
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (name.isEmpty()) {
            name = "candidate";
        }
        return name + "-review.zip";
    }

    /**
     * Carrier for {@code POST /dossier/appendices}. Two upload modes:
     * <ul>
     *   <li><b>Direct upload (frontend default):</b> caller provides
     *       {@code originalFilename} and {@code fileContent} (Base64-encoded
     *       file bytes). The resource decodes, stores in S3, then registers
     *       the appendix. Mirrors the {@code /templates/documents/upload}
     *       pattern so the frontend can stay in the multipart→base64 BFF
     *       shape it already uses for Word templates.</li>
     *   <li><b>Pre-uploaded reference (legacy):</b> caller provides
     *       {@code fileUuid} pointing at an already-S3-stored file plus
     *       {@code originalFilename}. The resource only registers the
     *       appendix row.</li>
     * </ul>
     * Validation lives in {@link DossierService#addAppendix} (filename
     * sanitisation).
     */
    public static class AppendixUploadRequest {
        public String fileUuid;
        public String originalFilename;
        /** Base64-encoded file bytes; mutually exclusive with {@link #fileUuid}. */
        public String fileContent;
        /**
         * {@code true} = recipient must sign this appendix; {@code false} =
         * attachment-only. Defaults to {@code true} when omitted to match the
         * employee-management templates wizard's default.
         */
        public Boolean signObligated;

        // Suppress an unused-warning for the placeholder map — present so
        // future stage extensions (file size, mime type) can land without a
        // breaking API change.
        @SuppressWarnings("unused")
        public Map<String, String> meta = new HashMap<>();
    }

}
