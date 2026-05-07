package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.AppendixDto;
import dk.trustworks.intranet.recruitmentservice.dto.DossierRequest;
import dk.trustworks.intranet.recruitmentservice.dto.DossierResponse;
import dk.trustworks.intranet.recruitmentservice.dto.SignerConfigDto;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Application service for the {@link CandidateDossier} aggregate. Handles
 * autosave of the JSON draft state (placeholders + signers + appendices),
 * appendix CRUD with filename sanitisation, and read-side projection to the
 * {@link DossierResponse} DTO.
 * <p>
 * Send actions live on {@link DossierRevisionService}, not here, so this
 * service stays focused on draft-state mutation only.
 */
@JBossLog
@ApplicationScoped
public class DossierService {

    /** Maximum allowed appendix display index — bound matches what fits comfortably on the dossier UI. */
    private static final int MAX_APPENDIX_ORDER = 99;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Look up the open dossier for the given (candidate, template) pair, if
     * one exists. Returns {@link Optional#empty()} for a candidate that has
     * no dossier on that template — the caller decides whether to 404.
     */
    public Optional<DossierResponse> findByCandidateAndTemplate(UUID candidateUuid, UUID templateUuid) {
        return CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND templateUuid = ?2",
                        candidateUuid.toString(), templateUuid.toString())
                .firstResultOptional()
                .map(this::toResponse);
    }

    /**
     * Load the (unique) dossier for a candidate. Per spec there is one
     * dossier per candidate per template, and each candidate is created with
     * exactly one dossier — so in practice this returns at most one row.
     */
    public Optional<DossierResponse> loadForCandidate(UUID candidateUuid) {
        return CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1", Sort.descending("createdAt"),
                        candidateUuid.toString())
                .firstResultOptional()
                .map(this::toResponse);
    }

    /**
     * Apply autosave updates to the dossier's JSON draft state. The request
     * payload is partial — only non-null fields on {@code req} are written;
     * the others are left as-is on the entity.
     *
     * @throws BusinessRuleViolation if the dossier is CLOSED — autosave on a
     *                               closed dossier indicates a stale UI and
     *                               we surface 409 to force a reload.
     */
    @Transactional
    public DossierResponse update(UUID dossierUuid, DossierRequest req, UUID actor) {
        Objects.requireNonNull(req, "req must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "update");

        if (req.placeholderValues() != null) {
            dossier.setPlaceholderValuesJson(writeJson(req.placeholderValues()));
        }
        if (req.signersConfig() != null) {
            dossier.setSignersConfigJson(writeJson(req.signersConfig()));
        }
        log.debugf("Autosaved dossier uuid=%s by actor=%s", dossier.getUuid(), actor);
        return toResponse(dossier);
    }

    /**
     * Add an appendix to a dossier. The {@code originalFilename} is sanitised
     * via {@link Path#getFileName()} and rejected if it contains path-traversal
     * sequences or non-printable characters. The S3 file UUID is the caller's
     * responsibility — the file must already be uploaded by the time this
     * method is called.
     */
    @Transactional
    public AppendixDto addAppendix(UUID dossierUuid, String originalFilename, String fileUuid,
                                   boolean signObligated, UUID actor) {
        Objects.requireNonNull(originalFilename, "originalFilename must not be null");
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "addAppendix");

        String sanitised = sanitiseFilename(originalFilename);

        int nextOrder = nextDisplayOrder(dossier.getUuid());

        CandidateDossierAppendix appendix = new CandidateDossierAppendix();
        appendix.setDossierUuid(dossier.getUuid());
        appendix.setFileUuid(fileUuid);
        appendix.setOriginalFilename(sanitised);
        appendix.setDisplayOrder(nextOrder);
        appendix.setSignObligated(signObligated);
        appendix.setUploadedByUseruuid(actor.toString());
        CandidateDossierAppendix.persist(appendix);

        log.infof("Added appendix uuid=%s file=%s signObligated=%s to dossier uuid=%s",
                appendix.getUuid(), fileUuid, signObligated, dossier.getUuid());

        return new AppendixDto(appendix.getUuid(), fileUuid, sanitised, nextOrder, signObligated);
    }

    /**
     * Remove an appendix from a dossier by its S3 {@code fileUuid}. Idempotent
     * if the file is not present on the dossier (no rows deleted).
     */
    @Transactional
    public void removeAppendix(UUID dossierUuid, String fileUuid, UUID actor) {
        Objects.requireNonNull(fileUuid, "fileUuid must not be null");
        Objects.requireNonNull(actor, "actor must not be null");
        CandidateDossier dossier = requireDossier(dossierUuid);
        guardOpen(dossier, "removeAppendix");
        long deleted = CandidateDossierAppendix.delete(
                "dossierUuid = ?1 AND fileUuid = ?2", dossier.getUuid(), fileUuid);
        if (deleted > 0) {
            log.infof("Removed appendix file=%s from dossier uuid=%s", fileUuid, dossier.getUuid());
        }
    }

    /**
     * Replace the open dossier's draft state with a frozen revision snapshot
     * (placeholders, signers, appendices). Past revisions stay immutable —
     * only the current draft is modified.
     *
     * <p>Appendices are stored in a separate child table; this method
     * deletes all current appendix rows and re-inserts new rows derived from
     * the revision's {@code appendicesSnapshot}. The snapshot's S3 fileUuids
     * are preserved on the new rows; if any of those S3 objects have been
     * reaped, a subsequent Send action will fail with a clear error.
     *
     * @return refreshed {@link DossierResponse}
     * @throws NotFoundException if revision doesn't belong to this candidate
     * @throws WebApplicationException 409 if dossier is CLOSED or candidate
     *         is in a terminal state
     */
    @Transactional
    public DossierResponse branchFromRevision(UUID candidateUuid, UUID revisionUuid, UUID actor) {
        Objects.requireNonNull(actor, "actor must not be null");

        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid.toString());
        if (candidate == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        if (candidate.getStatus() == CandidateStatus.HIRED
                || candidate.getStatus() == CandidateStatus.DECLINED
                || candidate.getStatus() == CandidateStatus.WITHDRAWN) {
            throw new WebApplicationException(
                    "Cannot branch — candidate is in terminal state " + candidate.getStatus(),
                    Response.Status.CONFLICT);
        }

        CandidateDossier dossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid = ?1 AND status = ?2",
                        candidate.getUuid(), DossierStatus.OPEN)
                .firstResult();
        if (dossier == null) {
            throw new WebApplicationException(
                    "Cannot branch — no OPEN dossier on candidate " + candidateUuid,
                    Response.Status.CONFLICT);
        }

        CandidateDossierRevision rev = CandidateDossierRevision.findById(revisionUuid.toString());
        if (rev == null || !rev.getDossierUuid().equals(dossier.getUuid())) {
            throw new NotFoundException(
                    "Revision " + revisionUuid + " does not belong to candidate " + candidateUuid);
        }

        // 1) Overwrite placeholder + signer drafts.
        dossier.setPlaceholderValuesJson(rev.getPlaceholderValuesSnapshot());
        dossier.setSignersConfigJson(rev.getSignersConfigSnapshot());

        // 2) Replace appendix rows with what the snapshot recorded.
        CandidateDossierAppendix.delete("dossierUuid", dossier.getUuid());
        List<AppendixDto> snapshotAppendices = readJson(
                rev.getAppendicesSnapshot(), new TypeReference<>() {});
        if (snapshotAppendices != null) {
            for (AppendixDto snap : snapshotAppendices) {
                CandidateDossierAppendix appendix = new CandidateDossierAppendix();
                appendix.setDossierUuid(dossier.getUuid());
                appendix.setFileUuid(snap.fileUuid());
                appendix.setOriginalFilename(snap.originalFilename());
                appendix.setDisplayOrder(snap.displayOrder());
                appendix.setSignObligated(snap.signObligated());
                appendix.setUploadedByUseruuid(actor.toString());
                CandidateDossierAppendix.persist(appendix);
            }
        }

        log.infof("BRANCHED_FROM_REVISION candidate=%s revision=%s versionNumber=%d actor=%s",
                candidate.getUuid(), rev.getUuid(), rev.getVersionNumber(), actor);

        return toResponse(dossier);
    }

    // ---- helpers ---------------------------------------------------------------

    private CandidateDossier requireDossier(UUID dossierUuid) {
        CandidateDossier d = CandidateDossier.findById(dossierUuid.toString());
        if (d == null) {
            throw new NotFoundException("Dossier not found: " + dossierUuid);
        }
        return d;
    }

    private void guardOpen(CandidateDossier dossier, String operation) {
        if (dossier.getStatus() != DossierStatus.OPEN) {
            throw new BusinessRuleViolation(
                    "Cannot %s dossier %s: status is CLOSED".formatted(operation, dossier.getUuid()));
        }
    }

    /**
     * Sanitise an appendix filename: strip path components (defensive against
     * upload payloads that include directory prefixes), reject {@code ..},
     * leading slashes, and any non-printable / control characters before the
     * value can land on a SharePoint URL.
     *
     * @throws IllegalArgumentException if the name contains forbidden patterns
     */
    static String sanitiseFilename(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("originalFilename must not be empty");
        }
        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) {
            throw new IllegalArgumentException("originalFilename must not start with a path separator");
        }
        if (trimmed.contains("..")) {
            throw new IllegalArgumentException("originalFilename must not contain '..'");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            // Reject ASCII control chars (0x00-0x1F, 0x7F) and the bidi/format chars
            // most commonly used to bypass filename filters.
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("originalFilename must not contain control characters");
            }
        }
        try {
            // Path.getFileName() drops any directory prefix the client sneaked in.
            Path leaf = Paths.get(trimmed).getFileName();
            if (leaf == null) {
                throw new IllegalArgumentException("originalFilename has no leaf component");
            }
            String name = leaf.toString();
            if (name.equals("..") || name.equals(".")) {
                throw new IllegalArgumentException("originalFilename must not be a relative path component");
            }
            return name;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("originalFilename is not a valid path: " + e.getMessage());
        }
    }

    private int nextDisplayOrder(String dossierUuid) {
        // Use MAX+1 — display_order is unique per dossier, and a count-based
        // value would collide after delete-then-add.
        Integer max = CandidateDossierAppendix.<CandidateDossierAppendix>find(
                        "dossierUuid", Sort.descending("displayOrder"), dossierUuid)
                .firstResultOptional()
                .map(CandidateDossierAppendix::getDisplayOrder)
                .orElse(0);
        int next = Math.min(max + 1, MAX_APPENDIX_ORDER);
        return Math.max(next, 1);
    }

    private DossierResponse toResponse(CandidateDossier dossier) {
        Map<String, String> placeholderValues = readJson(
                dossier.getPlaceholderValuesJson(),
                new TypeReference<>() {
                });
        List<SignerConfigDto> signersConfig = readJson(
                dossier.getSignersConfigJson(),
                new TypeReference<>() {
                });
        List<AppendixDto> appendices = CandidateDossierAppendix
                .<CandidateDossierAppendix>find("dossierUuid", Sort.ascending("displayOrder"), dossier.getUuid())
                .stream()
                .map(a -> new AppendixDto(a.getUuid(), a.getFileUuid(), a.getOriginalFilename(),
                        a.getDisplayOrder(), a.isSignObligated()))
                .toList();
        return new DossierResponse(
                dossier.getUuid(),
                dossier.getCandidateUuid(),
                dossier.getTemplateUuid(),
                placeholderValues != null ? placeholderValues : Map.of(),
                signersConfig != null ? signersConfig : List.of(),
                dossier.getStatus(),
                appendices,
                dossier.getCreatedAt(),
                dossier.getUpdatedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw CandidateService.jsonError("write", e);
        }
    }

    private <T> T readJson(String raw, TypeReference<T> ref) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, ref);
        } catch (JsonProcessingException e) {
            // Re-throw: silent fallback to empty would let the autosave PUT
            // overwrite the (still-valid) DB JSON with empty maps.
            throw CandidateService.jsonError("read", e);
        }
    }

    /**
     * Used by sister services and the recruitment resource to pull a dossier
     * together with its draft snapshots in one place. Deliberately not on the
     * entity — entity stays raw-JSON pure.
     */
    public Map<String, String> currentPlaceholderValues(CandidateDossier dossier) {
        Map<String, String> v = readJson(dossier.getPlaceholderValuesJson(), new TypeReference<>() {
        });
        if (v == null || v.isEmpty()) {
            log.warnf("Dossier %s has no persisted placeholder values — PDFs will render with blank substitutions",
                    dossier.getUuid());
            return Collections.emptyMap();
        }
        return new LinkedHashMap<>(v);
    }

    public List<SignerConfigDto> currentSignersConfig(CandidateDossier dossier) {
        List<SignerConfigDto> v = readJson(dossier.getSignersConfigJson(), new TypeReference<>() {
        });
        return v == null ? List.of() : v;
    }

    /**
     * Read-side helper used by the revision service and resource to produce
     * the appendix snapshot for a Send action. Returns the appendices in
     * display-order.
     */
    public List<AppendixDto> currentAppendices(String dossierUuid) {
        return CandidateDossierAppendix
                .<CandidateDossierAppendix>find("dossierUuid", Sort.ascending("displayOrder"), dossierUuid)
                .stream()
                .map(a -> new AppendixDto(a.getUuid(), a.getFileUuid(), a.getOriginalFilename(),
                        a.getDisplayOrder(), a.isSignObligated()))
                .toList();
    }

    /**
     * Resolve the candidate's email — the locked review-email recipient. The
     * candidate is loaded fresh because the dossier knows only the candidate
     * UUID.
     */
    String requireCandidateEmail(String candidateUuid) {
        RecruitmentCandidate c = RecruitmentCandidate.findById(candidateUuid);
        if (c == null) {
            throw new NotFoundException("Candidate not found: " + candidateUuid);
        }
        return c.getEmail();
    }
}
