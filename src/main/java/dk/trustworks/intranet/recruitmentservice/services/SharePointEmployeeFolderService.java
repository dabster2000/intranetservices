package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.enums.SharePointLocationType;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import dk.trustworks.intranet.utils.services.SigningService;
import dk.trustworks.intranet.utils.services.SigningService.SignedDocumentDownload;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Copies a promoted candidate's S3-backed generated PDFs and appendices into
 * the SharePoint folder defined by the {@code (user.company, EMPLOYEE)}
 * SharePointLocation, with the new username appended.
 * <p>
 * Per the design, the recruitment-side SharePoint folder is no longer created
 * during the candidate phase — SharePoint is touched only at promote time.
 * The destination is the same one the e-signing flow uses for the same user
 * (single source of truth: {@link SharePointLocationEntity}).
 */
@JBossLog
@ApplicationScoped
public class SharePointEmployeeFolderService {

    private static final int RETENTION_DAYS = 30;

    @Inject
    SharePointService sharePointService;

    @Inject
    RecruitmentS3StorageService s3StorageService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    StatusService statusService;

    @Inject
    SigningService signingService;

    @Inject
    EntityManager em;

    /**
     * Outcome of {@link #copyToEmployeeFolder}: the aggregate move status
     * plus the ordered list of signed-PDF filenames that were uploaded. The
     * Convert flow uses {@code signedFilenames} to build the HR Slack message
     * body on COMPLETED.
     */
    public record CopyResult(SharePointMoveStatus status, List<String> signedFilenames) { }

    /**
     * Resolve the destination SharePoint location for a promoted user via the
     * shared {@link SharePointLocationEntity} registry. Same chain that
     * {@code SigningService.resolveSharepointLocationUuid} uses:
     * {@code userUuid → UserStatus.company → (company, EMPLOYEE) → location}.
     *
     * @return the location, or {@code null} when the user has no active
     *         employment status, no company, or the company has no active
     *         {@link SharePointLocationType#EMPLOYEE} location configured.
     */
    public SharePointLocationEntity resolveEmployeeLocation(String userUuid) {
        if (userUuid == null || userUuid.isBlank()) return null;
        UserStatus currentStatus = statusService.getLatestEmploymentStatus(userUuid);
        if (currentStatus == null || currentStatus.getCompany() == null) return null;
        return SharePointLocationEntity.findByCompanyAndType(
                currentStatus.getCompany().getUuid(), SharePointLocationType.EMPLOYEE);
    }

    /**
     * Copy every S3-backed file (generated PDFs across all revisions, and
     * every appendix) for {@code candidate} into
     * {@code location.folderPath/targetUsername} on
     * {@code location.siteUrl / location.driveName}.
     *
     * <p>For the latest COMPLETED {@code SIGNATURE} revision, the
     * template-rendered uploads are renamed to {@code {name}_unsigned.pdf}
     * and the signed PDFs from NextSign are uploaded as
     * {@code {name}_signed.pdf} to provide a side-by-side audit trail in
     * SharePoint. Older or non-SIGNATURE revisions are not renamed; their
     * filenames are unchanged. Appendices keep their original filenames.
     *
     * @return a {@link CopyResult} carrying the aggregate
     *         {@link SharePointMoveStatus} (COMPLETED / PARTIAL / FAILED) and
     *         the list of {@code _signed.pdf} filenames successfully
     *         uploaded; the list is empty when no signed archival ran.
     * @throws NullPointerException     if any required argument is null
     * @throws IllegalArgumentException if the username or resolved path fails
     *                                  the safety guards
     */
    public CopyResult copyToEmployeeFolder(
            RecruitmentCandidate candidate, String targetUsername, SharePointLocationEntity location) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(targetUsername, "targetUsername must not be null");
        Objects.requireNonNull(location, "location must not be null");

        String siteUrl = location.getSiteUrl();
        String driveName = location.getDriveName();
        String basePath = location.getFolderPath();
        if (basePath != null) guardSafePath(basePath);
        guardSafeUsername(targetUsername);

        String destFolder = buildDestFolder(basePath, targetUsername);

        try {
            sharePointService.ensureFolderExists(siteUrl, driveName, destFolder);
        } catch (Exception e) {
            log.errorf(e, "ensureFolderExists FAILED candidate=%s dest=%s — abort with FAILED",
                    candidate.getUuid(), destFolder);
            return new CopyResult(SharePointMoveStatus.FAILED, List.of());
        }

        int copied = 0;
        int failed = 0;

        // Identify the latest COMPLETED SIGNATURE revision (if any). Files
        // belonging to this revision will be renamed to _unsigned.pdf after
        // the unsigned upload loop, and the signed envelope will be
        // downloaded + uploaded as _signed.pdf below.
        LatestSignatureRevision latestSigned = findLatestCompletedSignatureRevision(candidate.getUuid());
        Set<String> filenamesUploadedForSignedRevision = new HashSet<>();

        for (CandidateDossierRevision rev : CandidateDossierRevision.findByCandidate(candidate.getUuid())) {
            if (rev.getGeneratedPdfsSnapshot() == null) continue;
            List<GeneratedPdfRef> refs;
            try {
                refs = objectMapper.readValue(
                        rev.getGeneratedPdfsSnapshot(),
                        new TypeReference<List<GeneratedPdfRef>>() {});
            } catch (Exception e) {
                log.errorf(e,
                        "Skipping revision: failed to deserialize generated_pdfs_snapshot " +
                        "candidate=%s revision=%s",
                        candidate.getUuid(), rev.getUuid());
                failed++;
                continue;
            }
            boolean isSignedRevision = latestSigned != null
                    && rev.getUuid().equals(latestSigned.revisionUuid);
            for (GeneratedPdfRef ref : refs) {
                if (ref.fileUuid() == null) continue;
                try {
                    byte[] bytes = s3StorageService.fetchGeneratedPdf(ref.fileUuid());
                    sharePointService.uploadFile(siteUrl, driveName, destFolder, ref.filename(), bytes);
                    copied++;
                    if (isSignedRevision) {
                        filenamesUploadedForSignedRevision.add(ref.filename());
                    }
                } catch (Exception e) {
                    log.errorf(e,
                            "SharePoint copy FAILED for one file " +
                            "candidate=%s revision=%s fileUuid=%s filename=%s",
                            candidate.getUuid(), rev.getUuid(), ref.fileUuid(), ref.filename());
                    failed++;
                }
            }
        }

        for (CandidateDossierAppendix a : CandidateDossierAppendix.findByCandidate(candidate.getUuid())) {
            if (a.getFileUuid() == null) continue;
            try {
                byte[] bytes = s3StorageService.fetchGeneratedPdf(a.getFileUuid());
                sharePointService.uploadFile(siteUrl, driveName, destFolder, a.getOriginalFilename(), bytes);
                copied++;
            } catch (Exception e) {
                log.errorf(e,
                        "SharePoint copy FAILED for appendix " +
                        "candidate=%s appendix=%s fileUuid=%s filename=%s",
                        candidate.getUuid(), a.getUuid(), a.getFileUuid(), a.getOriginalFilename());
                failed++;
            }
        }

        // ── Onboarding identity documents ────────────────────────────────
        // Migrate any S3-backed onboarding uploads (driver's licence, health
        // insurance, criminal record) into <destFolder>/Onboarding/. Same
        // location the user-flow upload page (OnboardingUploadService) writes
        // to directly when the token is bound to a user instead of a
        // candidate. Filenames are deterministic so retries overwrite cleanly
        // and HR has a predictable layout.
        String onboardingFolder = destFolder + "/Onboarding";
        List<OnboardingUploadSubmission> onboardingUploads =
                OnboardingUploadSubmission.findS3SubmissionsByCandidate(candidate.getUuid());
        if (!onboardingUploads.isEmpty()) {
            boolean folderReady = true;
            try {
                sharePointService.ensureFolderExists(siteUrl, driveName, onboardingFolder);
            } catch (Exception e) {
                log.errorf(e,
                        "ensureFolderExists FAILED for Onboarding subfolder " +
                        "candidate=%s dest=%s — counting %d onboarding files as failed",
                        candidate.getUuid(), onboardingFolder, onboardingUploads.size());
                failed += onboardingUploads.size();
                folderReady = false;
            }
            if (folderReady) {
                for (OnboardingUploadSubmission sub : onboardingUploads) {
                    try {
                        byte[] bytes = s3StorageService.fetchGeneratedPdf(sub.getS3FileUuid());
                        String filename = onboardingFilename(sub.getDocumentType(), sub.getContentType());
                        sharePointService.uploadFile(
                                siteUrl, driveName, onboardingFolder, filename, bytes);
                        copied++;
                    } catch (Exception e) {
                        log.errorf(e,
                                "SharePoint copy FAILED for onboarding upload " +
                                "candidate=%s submission=%s type=%s fileUuid=%s",
                                candidate.getUuid(), sub.getUuid(),
                                sub.getDocumentType(), sub.getS3FileUuid());
                        failed++;
                    }
                }
            }
        }

        // Signed archival: rename unsigned uploads of the latest COMPLETED
        // SIGNATURE revision to {name}_unsigned.pdf, then download and
        // upload signed PDFs as {name}_signed.pdf.
        List<String> signedFilenames = List.of();
        if (latestSigned == null) {
            log.infof("No COMPLETED SIGNATURE revision for candidate=%s — skipping signed archival",
                    candidate.getUuid());
        } else {
            // Rename successful unsigned uploads from this revision.
            for (String original : filenamesUploadedForSignedRevision) {
                String renamed = stripPdfSuffix(original) + "_unsigned.pdf";
                try {
                    sharePointService.renameFile(siteUrl, driveName, destFolder, original, renamed);
                } catch (Exception e) {
                    log.errorf(e,
                            "SharePoint rename FAILED candidate=%s revision=%s %s -> %s",
                            candidate.getUuid(), latestSigned.revisionUuid, original, renamed);
                    failed++;
                }
            }

            // Download signed envelope and upload each signed PDF.
            try {
                List<SignedDocumentDownload> signed = signingService
                        .downloadAllSignedDocuments(latestSigned.caseKey);
                List<String> uploadedSigned = new ArrayList<>(signed.size());
                for (SignedDocumentDownload doc : signed) {
                    String signedName = stripPdfSuffix(doc.name()) + "_signed.pdf";
                    try {
                        sharePointService.uploadFile(siteUrl, driveName, destFolder,
                                signedName, doc.pdfBytes());
                        uploadedSigned.add(signedName);
                        copied++;
                    } catch (Exception e) {
                        log.errorf(e,
                                "SharePoint upload FAILED for signed PDF " +
                                "candidate=%s caseKey=%s name=%s",
                                candidate.getUuid(), latestSigned.caseKey, signedName);
                        failed++;
                    }
                }
                signedFilenames = List.copyOf(uploadedSigned);
            } catch (Exception e) {
                log.errorf(e,
                        "downloadAllSignedDocuments FAILED candidate=%s caseKey=%s",
                        candidate.getUuid(), latestSigned.caseKey);
                // Unknown count — record at least one failure so status
                // collapses to PARTIAL/FAILED and Slack does not fire.
                failed++;
            }
        }

        SharePointMoveStatus status;
        if (failed == 0) {
            status = SharePointMoveStatus.COMPLETED;
        } else if (copied == 0) {
            status = SharePointMoveStatus.FAILED;
        } else {
            status = SharePointMoveStatus.PARTIAL;
        }
        log.infof("SharePoint copy candidate=%s username=%s site=%s drive=%s dest=%s copied=%d failed=%d signed=%d onboarding=%d status=%s",
                candidate.getUuid(), targetUsername, siteUrl, driveName, destFolder,
                copied, failed, signedFilenames.size(), onboardingUploads.size(), status);
        return new CopyResult(status, signedFilenames);
    }

    /** Strip a trailing {@code .pdf} extension (case-insensitive). */
    static String stripPdfSuffix(String name) {
        if (name == null) return "";
        if (name.length() < 4) return name;
        String tail = name.substring(name.length() - 4);
        return tail.equalsIgnoreCase(".pdf")
                ? name.substring(0, name.length() - 4)
                : name;
    }

    /** Internal carrier for the latest COMPLETED SIGNATURE revision lookup. */
    private record LatestSignatureRevision(String revisionUuid, String caseKey) { }

    /**
     * Find the most recent {@code SIGNATURE} revision for {@code candidateUuid}
     * whose linked {@code signing_cases.status = 'COMPLETED'}. Returns
     * {@code null} when none exists. Native SQL inner-join mirrors the
     * pattern used by {@code RecruitmentSignatureCompletionListener} so the
     * recruitment domain has one canonical join shape.
     */
    private LatestSignatureRevision findLatestCompletedSignatureRevision(String candidateUuid) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT cdr.uuid, cdr.signing_case_key " +
                "FROM candidate_dossier_revisions cdr " +
                "INNER JOIN signing_cases sc " +
                "  ON cdr.signing_case_key = sc.case_key " +
                "WHERE cdr.kind = 'SIGNATURE' " +
                "  AND cdr.signing_case_key IS NOT NULL " +
                "  AND sc.status = 'COMPLETED' " +
                "  AND cdr.dossier_uuid IN " +
                "      (SELECT d.uuid FROM candidate_dossiers d WHERE d.candidate_uuid = :cuuid) " +
                "ORDER BY cdr.created_at DESC " +
                "LIMIT 1")
                .setParameter("cuuid", candidateUuid)
                .getResultList();
        if (rows.isEmpty()) return null;
        Object[] row = rows.get(0);
        return new LatestSignatureRevision((String) row[0], (String) row[1]);
    }

    /**
     * Stamp {@code s3_retention_until = NOW + RETENTION_DAYS} on every
     * S3-backed revision and appendix for {@code candidate}. Called after a
     * successful SharePoint copy so the {@code S3RetentionCleanupBatchlet}
     * can reap the originals after the retention window.
     *
     * @return total rows stamped (revisions + appendices + onboarding uploads)
     */
    public int stampS3RetentionUntil(RecruitmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        LocalDateTime retentionUntil = LocalDateTime.now().plusDays(RETENTION_DAYS);
        int revisions = (int) CandidateDossierRevision.update(
                "s3RetentionUntil = ?1 WHERE dossierUuid IN " +
                "(SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?2) " +
                "AND generatedPdfsSnapshot IS NOT NULL",
                retentionUntil, candidate.getUuid());
        int appendices = (int) CandidateDossierAppendix.update(
                "s3RetentionUntil = ?1 WHERE dossierUuid IN " +
                "(SELECT d.uuid FROM CandidateDossier d WHERE d.candidateUuid = ?2) " +
                "AND fileUuid IS NOT NULL",
                retentionUntil, candidate.getUuid());
        int onboarding = (int) OnboardingUploadSubmission.update(
                "s3RetentionUntil = ?1 WHERE candidateUuid = ?2 " +
                "AND storageTarget = ?3 AND s3FileUuid IS NOT NULL",
                retentionUntil, candidate.getUuid(),
                OnboardingUploadSubmission.StorageTarget.S3);
        log.infof("Stamped s3_retention_until=%s on %d revision(s) + %d appendix(es) + %d onboarding upload(s) for candidate=%s",
                retentionUntil, revisions, appendices, onboarding, candidate.getUuid());
        return revisions + appendices + onboarding;
    }

    /**
     * Build the destination folder path. A blank or null base resolves to
     * {@code /<username>}; otherwise {@code <basePath>/<username>} with a
     * single separator.
     */
    private static String buildDestFolder(String basePath, String username) {
        if (basePath == null || basePath.isBlank()) return "/" + username;
        return stripTrailingSlash(basePath) + "/" + username;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static void guardSafePath(String path) {
        if (path == null) return; // null handled separately by callers
        String lower = path.toLowerCase();
        if (lower.contains("..") || lower.contains("%2e%2e") || lower.contains("\\")) {
            throw new IllegalArgumentException("Path failed safety check: " + path);
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException("Path contains control characters");
            }
        }
    }

    /** Allow only alphanumeric + dash/underscore/dot (Trustworks usernames). */
    private static void guardSafeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("targetUsername must not be blank");
        }
        for (int i = 0; i < username.length(); i++) {
            char c = username.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            if (!ok) {
                throw new IllegalArgumentException(
                        "Username contains disallowed character: " + username);
            }
        }
    }

    /**
     * Build the deterministic SharePoint filename for an onboarding
     * identity-document upload during candidate promotion. Format:
     * {@code <doc_type>.<ext>}, where the doc type is a fixed lowercase
     * snake_case name and the extension is derived from the submission's
     * stored content type.
     *
     * <p>Filenames are deterministic so the retry batchlet's overwrite
     * semantics work and HR can predict the layout (drivers_license.pdf,
     * health_insurance.jpg, criminal_record.png).</p>
     *
     * <p>Package-private for unit testing.</p>
     */
    static String onboardingFilename(OnboardingDocumentType type, String contentType) {
        String base = switch (type) {
            case DRIVERS_LICENSE  -> "drivers_license";
            case HEALTH_INSURANCE -> "health_insurance";
            case CRIMINAL_RECORD  -> "criminal_record";
        };
        return base + extensionFor(contentType);
    }

    /**
     * Resolve a file extension from a stored content_type. Strips any charset
     * suffix and lowercases. Unknown / null types yield "" (no extension)
     * rather than guessing — the caller's allowlist
     * ({@link dk.trustworks.intranet.recruitmentservice.services.OnboardingUploadService})
     * means the unknown branch should be unreachable in practice.
     */
    static String extensionFor(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String bare = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
        return switch (bare) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg"      -> ".jpg";
            case "image/png"       -> ".png";
            default                -> "";
        };
    }
}
