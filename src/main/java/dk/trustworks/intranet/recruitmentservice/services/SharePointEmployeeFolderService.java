package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Copies a promoted candidate's S3-backed generated PDFs and appendices into
 * the SharePoint folder defined by the template ({@code template.sharepoint_folder})
 * with the new username appended.
 * <p>
 * Replaces {@code SharePointCandidateFolderService} (V321). Per the design,
 * the recruitment-side SharePoint folder is no longer created during the
 * candidate phase — SharePoint is touched only at promote time.
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

    @ConfigProperty(name = "sharepoint.recruitment.site-url",
            defaultValue = "https://trustworks.sharepoint.com/sites/Recruitment")
    String siteUrl;

    @ConfigProperty(name = "sharepoint.recruitment.drive-name",
            defaultValue = "Documents")
    String driveName;

    /**
     * Copy every S3-backed file (generated PDFs across all revisions, and
     * every appendix) for {@code candidate} into
     * {@code templateBaseFolder/targetUsername}.
     *
     * @return {@link SharePointMoveStatus#COMPLETED} on full success,
     *         {@link SharePointMoveStatus#FAILED} otherwise
     * @throws IllegalArgumentException if {@code templateBaseFolder} is blank
     */
    public SharePointMoveStatus copyToEmployeeFolder(
            RecruitmentCandidate candidate, String targetUsername, String templateBaseFolder) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(targetUsername, "targetUsername must not be null");
        if (templateBaseFolder == null || templateBaseFolder.isBlank()) {
            throw new IllegalArgumentException(
                    "Template's sharepoint_folder is blank — cannot promote candidate "
                            + candidate.getUuid() + ". Set the destination on the template.");
        }
        guardSafePath(templateBaseFolder);
        guardSafeUsername(targetUsername);

        String destFolder = stripTrailingSlash(templateBaseFolder) + "/" + targetUsername;

        try {
            sharePointService.ensureFolderExists(siteUrl, driveName, destFolder);
        } catch (Exception e) {
            log.errorf(e, "ensureFolderExists FAILED candidate=%s dest=%s — abort with FAILED",
                    candidate.getUuid(), destFolder);
            return SharePointMoveStatus.FAILED;
        }

        int copied = 0;
        int failed = 0;

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
            for (GeneratedPdfRef ref : refs) {
                if (ref.fileUuid() == null) continue;
                try {
                    byte[] bytes = s3StorageService.fetchGeneratedPdf(ref.fileUuid());
                    sharePointService.uploadFile(siteUrl, driveName, destFolder, ref.filename(), bytes);
                    copied++;
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

        SharePointMoveStatus status;
        if (failed == 0) {
            status = SharePointMoveStatus.COMPLETED;
        } else if (copied == 0) {
            status = SharePointMoveStatus.FAILED;
        } else {
            status = SharePointMoveStatus.PARTIAL;
        }
        log.infof("SharePoint copy candidate=%s username=%s dest=%s copied=%d failed=%d status=%s",
                candidate.getUuid(), targetUsername, destFolder, copied, failed, status);
        return status;
    }

    /**
     * Stamp {@code s3_retention_until = NOW + RETENTION_DAYS} on every
     * S3-backed revision and appendix for {@code candidate}. Called after a
     * successful SharePoint copy so the {@code S3RetentionCleanupBatchlet}
     * can reap the originals after the retention window.
     *
     * @return total rows stamped (revisions + appendices)
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
        log.infof("Stamped s3_retention_until=%s on %d revision(s) + %d appendix(es) for candidate=%s",
                retentionUntil, revisions, appendices, candidate.getUuid());
        return revisions + appendices;
    }

    /**
     * Resolves the destination SharePoint base folder from the candidate's
     * dossier's template. Returns the folder string when set; {@code null}
     * when either the dossier, the template, or the template's
     * {@code sharepoint_folder} is missing.
     */
    public String resolveTemplateBaseFolder(RecruitmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        CandidateDossier dossier = CandidateDossier
                .<CandidateDossier>find("candidateUuid", candidate.getUuid())
                .firstResult();
        return resolveTemplateBaseFolder(dossier);
    }

    /**
     * Same as {@link #resolveTemplateBaseFolder(RecruitmentCandidate)} but
     * accepts an already-loaded dossier so callers that have one in scope
     * (e.g. {@code CandidateConversionUseCase} which just queried for OPEN
     * dossiers) can avoid a second round-trip.
     */
    public String resolveTemplateBaseFolder(CandidateDossier dossier) {
        if (dossier == null) return null;
        DocumentTemplateEntity template = DocumentTemplateEntity
                .<DocumentTemplateEntity>findById(dossier.getTemplateUuid());
        return template != null ? template.getSharepointFolder() : null;
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

    /** Allow only alphanumeric + dash/underscore (Trustworks usernames). */
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
}
