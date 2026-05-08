package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.SharePointLocationEntity;
import dk.trustworks.intranet.documentservice.model.enums.SharePointLocationType;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import dk.trustworks.intranet.sharepoint.dto.DriveItem;
import dk.trustworks.intranet.sharepoint.service.SharePointService;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application service that orchestrates the public onboarding upload
 * endpoint. Validates the token, routes the bytes to S3 (candidate flow)
 * or SharePoint (user flow), persists an audit row, and triggers a single
 * HR Slack notification once all required types are received.
 *
 * <p>Failures from the Slack notifier are caught and logged so a
 * downstream notification problem can never roll back a successful upload
 * — the candidate / new-hire's file must land before any UX nicety.</p>
 *
 * <h3>PII boundary</h3>
 * <p>Filenames and bytes are never logged. Only outcome, token UUID,
 * document-type, and storage target are emitted at INFO level.</p>
 */
@JBossLog
@ApplicationScoped
public class OnboardingUploadService {

    /**
     * Allow-list of MIME types accepted by the upload endpoint.
     *
     * <p>The client-asserted Content-Type is paired with a magic-byte check
     * in {@link #magicMatches} so a caller cannot bypass the format
     * restriction by lying about the type ({@code application/octet-stream}
     * is intentionally excluded).</p>
     */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png"
    );

    /** Hard size cap. 10 MiB matches the client-side check. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Inject
    RecruitmentS3StorageService recruitmentS3StorageService;

    @Inject
    SharePointService sharePointService;

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    /**
     * Persist a single uploaded identity document for the given token,
     * routing to S3 or SharePoint based on token ownership. Returns a
     * fresh {@link OnboardingValidateResponse} reflecting the post-upload
     * submitted-state so the caller can lock the relevant zone in one
     * round trip.
     */
    @Transactional
    public OnboardingValidateResponse handleUpload(String tokenUuid,
                                                   OnboardingDocumentType type,
                                                   String filename,
                                                   String contentType,
                                                   byte[] bytes) {
        // 1. Token validity — same silence rule as /validate (403, no leak).
        OnboardingUploadToken token = OnboardingUploadToken.findById(tokenUuid);
        if (token == null || token.isExpired()) {
            throw new ForbiddenException();
        }

        // 2. Token-flag gate: the type must be one the token explicitly asked for.
        if (!isTypeAllowedByToken(token, type)) {
            throw new BadRequestException(
                    Response.status(Response.Status.BAD_REQUEST)
                            .entity("{\"error\":\"DOCUMENT_TYPE_NOT_ALLOWED\"}")
                            .type(MediaType.APPLICATION_JSON)
                            .build());
        }

        // 3. Single-shot per type (DB unique key is the source of truth).
        if (OnboardingUploadSubmission.existsForTokenAndType(tokenUuid, type)) {
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity("{\"error\":\"ALREADY_SUBMITTED\"}")
                            .type(MediaType.APPLICATION_JSON)
                            .build());
        }

        // 4. Bytes / MIME / size sanity check.
        if (bytes == null || bytes.length == 0) {
            throw badRequest("EMPTY_FILE");
        }
        if (bytes.length > MAX_BYTES) {
            throw badRequest("FILE_TOO_LARGE");
        }
        String normalisedContentType = normaliseContentType(contentType);
        if (!ALLOWED_MIME_TYPES.contains(normalisedContentType)) {
            throw badRequest("UNSUPPORTED_MEDIA_TYPE");
        }
        if (!magicMatches(normalisedContentType, bytes)) {
            // Asserted MIME and actual bytes disagree — refuse rather than
            // trust the public-facing Content-Type header.
            throw badRequest("UNSUPPORTED_MEDIA_TYPE");
        }

        // 5. Sanitise filename — never trust public input as a path component.
        String safeFilename = sanitiseFilename(filename);
        if (safeFilename.isBlank()) {
            throw badRequest("INVALID_FILENAME");
        }

        // 6. Branch on token ownership (XOR enforced both at DB and here).
        boolean candidateFlow = token.getCandidateUuid() != null;
        boolean userFlow = token.getUserUuid() != null;
        if (candidateFlow == userFlow) {
            // Either both null or both set — token row is corrupt; fail closed.
            log.errorf("Onboarding token %s has invalid owner state (candidate=%s,user=%s)",
                    tokenUuid, token.getCandidateUuid(), token.getUserUuid());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }

        OnboardingUploadSubmission row = new OnboardingUploadSubmission();
        row.setTokenUuid(tokenUuid);
        row.setDocumentType(type);
        row.setOriginalFilename(safeFilename);
        row.setContentType(normalisedContentType);
        row.setFileSizeBytes(bytes.length);

        if (candidateFlow) {
            String fileUuid = recruitmentS3StorageService.storeIdentityDocument(
                    bytes, safeFilename, UUID.fromString(token.getCandidateUuid()));
            row.setCandidateUuid(token.getCandidateUuid());
            row.setStorageTarget(OnboardingUploadSubmission.StorageTarget.S3);
            row.setS3FileUuid(fileUuid);
        } else {
            DriveItem driveItem = uploadToSharePoint(token.getUserUuid(), safeFilename, bytes);
            row.setUserUuid(token.getUserUuid());
            row.setStorageTarget(OnboardingUploadSubmission.StorageTarget.SHAREPOINT);
            row.setSharepointDriveItemId(driveItem.id());
            row.setSharepointWebUrl(driveItem.webUrl());
        }

        row.persist();
        log.infof("Onboarding upload stored token=%s type=%s storage=%s submission=%s",
                tokenUuid, type, row.getStorageTarget(), row.getUuid());

        // 7. If we now have every required type, fire the HR notification.
        List<OnboardingUploadSubmission> all = OnboardingUploadSubmission.findByToken(tokenUuid);
        if (allRequiredTypesSubmitted(token, all)) {
            try {
                recruitmentHrSlackNotifier.notifyOnboardingComplete(token, all);
            } catch (Exception e) {
                // Notification is best-effort — never roll back a stored upload.
                log.errorf(e, "Slack onboarding-complete notification failed for token=%s", tokenUuid);
            }
        }

        return buildResponse(token, all);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static boolean isTypeAllowedByToken(OnboardingUploadToken token, OnboardingDocumentType type) {
        return switch (type) {
            case DRIVERS_LICENSE -> token.isShowDriversLicense();
            case HEALTH_INSURANCE -> token.isShowHealthInsurance();
            case CRIMINAL_RECORD -> token.isShowCriminalRecord();
        };
    }

    private static boolean allRequiredTypesSubmitted(OnboardingUploadToken token,
                                                     List<OnboardingUploadSubmission> submissions) {
        Set<OnboardingDocumentType> have = new HashSet<>();
        for (OnboardingUploadSubmission s : submissions) {
            have.add(s.getDocumentType());
        }
        if (token.isShowDriversLicense() && !have.contains(OnboardingDocumentType.DRIVERS_LICENSE)) return false;
        if (token.isShowHealthInsurance() && !have.contains(OnboardingDocumentType.HEALTH_INSURANCE)) return false;
        if (token.isShowCriminalRecord() && !have.contains(OnboardingDocumentType.CRIMINAL_RECORD)) return false;
        return true;
    }

    private DriveItem uploadToSharePoint(String userUuid, String filename, byte[] bytes) {
        User user = User.findById(userUuid);
        if (user == null) {
            log.errorf("Onboarding upload: user %s not found", userUuid);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        UserStatus status = user.getUserStatus(LocalDate.now());
        if (status == null || status.getCompany() == null || status.getCompany().getUuid() == null) {
            log.errorf("Onboarding upload: user %s has no active company", userUuid);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        String companyUuid = status.getCompany().getUuid();
        SharePointLocationEntity location = SharePointLocationEntity.findByCompanyAndType(
                companyUuid, SharePointLocationType.EMPLOYEE);
        if (location == null) {
            log.errorf("Onboarding upload: no EMPLOYEE SharePoint location configured for company %s", companyUuid);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        String username = user.getUsername();
        if (username == null || username.isBlank()) {
            log.errorf("Onboarding upload: user %s has no username", userUuid);
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
        String basePath = location.getFolderPath() == null ? "" : location.getFolderPath();
        String folderPath = (basePath.isEmpty() ? "" : stripTrailingSlash(basePath) + "/")
                + sanitisePathSegment(username) + "/Onboarding";

        return sharePointService.uploadFile(
                location.getSiteUrl(), location.getDriveName(), folderPath, filename, bytes);
    }

    private static BadRequestException badRequest(String code) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + code + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    /**
     * Positive allowlist filename sanitiser: keep only ASCII alphanumerics,
     * dot, underscore, hyphen, and space. Collapses any run of dots so
     * "{@code ..}" cannot resurface from a unicode look-alike. Returns ""
     * if nothing usable remains.
     */
    static String sanitiseFilename(String raw) {
        if (raw == null) return "";
        String filtered = raw.replaceAll("[^a-zA-Z0-9._\\- ]", "");
        // Collapse any run of dots to a single dot — a positive allowlist
        // already blocks unicode look-alikes for `.`, this guards against
        // `....pdf`-style obfuscation.
        filtered = filtered.replaceAll("\\.{2,}", ".");
        return filtered.trim();
    }

    /** Same rules as {@link #sanitiseFilename} but for an arbitrary path segment. */
    static String sanitisePathSegment(String raw) {
        return sanitiseFilename(raw);
    }

    /** Strip optional charset suffix and lowercase. */
    private static String normaliseContentType(String contentType) {
        if (contentType == null) return "";
        int semi = contentType.indexOf(';');
        String bare = semi >= 0 ? contentType.substring(0, semi) : contentType;
        return bare.trim().toLowerCase();
    }

    /**
     * Verify the first bytes of the upload match the asserted MIME type.
     * Defense against a caller that lies about Content-Type to slip an
     * unintended file format past the allowlist.
     *
     * <ul>
     *   <li>PDF: {@code 25 50 44 46} ("%PDF")</li>
     *   <li>JPEG: {@code FF D8 FF}</li>
     *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}</li>
     * </ul>
     */
    static boolean magicMatches(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return switch (contentType) {
            case "application/pdf" ->
                    bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46;
            case "image/jpeg" ->
                    (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            default -> false;
        };
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private OnboardingValidateResponse buildResponse(OnboardingUploadToken token,
                                                     List<OnboardingUploadSubmission> submissions) {
        boolean dl = false, hi = false, cr = false;
        for (OnboardingUploadSubmission s : submissions) {
            switch (s.getDocumentType()) {
                case DRIVERS_LICENSE -> dl = true;
                case HEALTH_INSURANCE -> hi = true;
                case CRIMINAL_RECORD -> cr = true;
            }
        }
        return new OnboardingValidateResponse(
                true,
                false,
                new OnboardingValidateResponse.FieldFlags(
                        token.isShowDriversLicense(),
                        token.isShowHealthInsurance(),
                        token.isShowCriminalRecord()),
                new OnboardingValidateResponse.Submitted(dl, hi, cr));
    }
}
