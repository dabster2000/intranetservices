package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Lazy provisioner of the per-candidate SharePoint folder. The folder is
 * created on the FIRST signature send (per spec §11.5) — never on candidate
 * create, never on review email/PDF — to avoid spamming the SharePoint site
 * with empty folders for candidates who never reach signature.
 * <p>
 * Path layout: {@code /Sites/Recruitment/Candidates/{candidateUuid}/}
 * <p>
 * The candidate UUID component is validated as a strict UUID-v4 string before
 * being interpolated into the path. Anything else (paths starting with {@code /},
 * paths containing {@code ..}, control characters) is rejected to prevent
 * Graph API path-traversal exploits, even though entity-level UUIDs are
 * already constrained.
 */
@JBossLog
@ApplicationScoped
public class SharePointCandidateFolderService {

    /** Strict UUID v4-ish format (dash positions and hex characters only). */
    static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /** Folder path inside the SharePoint document library. */
    static final String CANDIDATES_BASE = "Recruitment/Candidates";

    @Inject
    SharePointService sharePointService;

    @ConfigProperty(name = "sharepoint.recruitment.site-url",
            defaultValue = "https://trustworks.sharepoint.com/sites/Recruitment")
    String siteUrl;

    @ConfigProperty(name = "sharepoint.recruitment.drive-name",
            defaultValue = "Documents")
    String driveName;

    /**
     * Ensure the SharePoint folder for a candidate exists. Idempotent: if the
     * folder already exists the call returns the existing path; if not, the
     * folder is created (along with any missing parent folders).
     * <p>
     * The candidate's {@code sharepoint_folder_path} is updated to the
     * canonical path so subsequent calls can short-circuit on the field
     * being non-null.
     *
     * @return the absolute folder path used in SharePoint
     */
    @Transactional
    public String ensureFolderForCandidate(RecruitmentCandidate candidate) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        String candidateUuid = candidate.getUuid();
        if (!UUID_PATTERN.matcher(candidateUuid == null ? "" : candidateUuid).matches()) {
            throw new IllegalArgumentException(
                    "Candidate UUID is not a valid UUID-v4: " + candidateUuid);
        }
        String folderPath = CANDIDATES_BASE + "/" + candidateUuid;

        // Defense-in-depth — the regex match above already guarantees this,
        // but be explicit for the next reader.
        guardSafePath(folderPath);

        if (candidate.getSharepointFolderPath() != null
                && candidate.getSharepointFolderPath().equals(folderPath)) {
            log.debugf("SharePoint folder already recorded for candidate=%s path=%s",
                    candidateUuid, folderPath);
            return folderPath;
        }

        log.infof("Ensuring SharePoint folder for candidate=%s path=%s", candidateUuid, folderPath);
        sharePointService.ensureFolderExists(siteUrl, driveName, folderPath);
        candidate.setSharepointFolderPath(folderPath);
        return folderPath;
    }

    /**
     * Copy the candidate's SharePoint folder to the new employee's home
     * folder, then delete the recruitment-side folder. This runs from the
     * post-commit batchlet (Stage 3 ownership) — Stage 2 declares the
     * signature so the conversion use case can call it and persist
     * {@code sharepoint_move_status} appropriately.
     * <p>
     * <strong>TODO (stage 3):</strong> wire up the actual copy via
     * {@link SharePointService#ensureFolderExists} for the destination plus
     * a recursive copy/delete via the Graph driveItem children API. Until
     * stage 3 lands, returning {@link SharePointMoveStatus#PENDING}
     * preserves the column-as-queue contract — the batchlet will pick up
     * the row and complete the move.
     *
     * @return the move status to persist on the candidate
     */
    public SharePointMoveStatus copyAndDeleteOnHire(RecruitmentCandidate candidate, String targetUsername) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(targetUsername, "targetUsername must not be null");
        // Stub — return FAILED so the batchlet stops re-queueing.
        log.warnf("copyAndDeleteOnHire not implemented for candidate=%s target=%s — marking FAILED",
                candidate.getUuid(), targetUsername);
        return SharePointMoveStatus.FAILED;
    }

    private static void guardSafePath(String path) {
        if (path.contains("..") || path.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Candidate folder path failed safety check: " + path);
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new IllegalArgumentException(
                        "Candidate folder path contains control characters");
            }
        }
    }
}
