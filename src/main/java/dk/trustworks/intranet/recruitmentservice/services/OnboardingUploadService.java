package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentHrSlackNotifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Application service that orchestrates the public onboarding upload
 * endpoint. Validates the token, routes the bytes to the candidate's S3
 * staging space (candidate flow) or the employee document store (user
 * flow), persists an audit row, and triggers a single HR Slack
 * notification once all required types are received.
 *
 * <h3>Transactional shape</h3>
 * <p>The method itself is <b>not</b> {@code @Transactional}. We deliberately
 * keep the slow S3 upload (1–5 s round trip) <i>outside</i> any
 * DB transaction so we don't hold a connection while waiting on a
 * remote HTTP API. The narrow audit-row insert runs in its own
 * transaction via {@link OnboardingSubmissionPersister}. The AI vision
 * call (3–8 s typical latency) likewise runs outside any transaction,
 * for the same reason — we never hold a JDBC connection while waiting
 * on a remote HTTP API.</p>
 *
 * <p><b>Order of operations:</b></p>
 * <ol>
 *   <li>Read-only validation (token lookup, expiry, type-allowed,
 *       existsForTokenAndType, MIME / size / magic-byte / filename) — no TX.</li>
 *   <li>AI document validation gate — synchronous OpenAI vision call
 *       against the type-specific prompt; fail-closed (rejection throws
 *       <b>422 AI_REJECTED</b>); still no DB transaction, so the slow
 *       remote call does not hold a connection.</li>
 *   <li>Pre-resolve display name + link URL for the eventual Slack message
 *       so the notifier needs no further DB reads.</li>
 *   <li>Upload bytes to S3 — <i>still no TX</i>.</li>
 *   <li>Persist the audit row in a small REQUIRED tx via
 *       {@link OnboardingSubmissionPersister#persist}.</li>
 *   <li>If persist fails (e.g. concurrent uploads racing on
 *       {@code uk_ous_token_doctype}) → compensating delete of the
 *       just-uploaded storage object + translate to <b>409
 *       ALREADY_SUBMITTED</b>.</li>
 *   <li>Re-read submissions and run the HR notifier (best-effort).</li>
 * </ol>
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
     *
     * <p>PDF was dropped when AI document validation landed — the vision
     * API takes images only, and the client-side accept list now matches.</p>
     */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of(
            "image/jpeg",
            "image/png"
    );

    /** Hard size cap. 10 MiB matches the client-side check. */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Inject
    RecruitmentS3StorageService recruitmentS3StorageService;

    @Inject
    dk.trustworks.intranet.documentservice.services.EmployeeDocumentService employeeDocumentService;

    @Inject
    RecruitmentHrSlackNotifier recruitmentHrSlackNotifier;

    @Inject
    OnboardingSubmissionPersister onboardingSubmissionPersister;

    @Inject
    OnboardingDocumentValidationService documentValidationService;

    @ConfigProperty(name = "recruitment.hr.slack.dossier-base-url",
            defaultValue = "https://intra.trustworks.dk/recruitment/candidates")
    String dossierBaseUrl;

    /**
     * Persist a single uploaded identity document for the given token,
     * routing to candidate staging or the employee store based on token
     * ownership. Returns a
     * fresh {@link OnboardingValidateResponse} reflecting the post-upload
     * submitted-state so the caller can lock the relevant zone in one
     * round trip.
     *
     * <p>Not annotated {@code @Transactional} — see class-level Javadoc.</p>
     */
    public OnboardingValidateResponse handleUpload(String tokenUuid,
                                                   OnboardingDocumentType type,
                                                   String filename,
                                                   String contentType,
                                                   byte[] bytes) {
        // ── 1. Read-only validation (no DB writes, no transaction) ───────
        OnboardingUploadToken token = OnboardingUploadToken.findById(tokenUuid);
        if (token == null || token.isExpired()) {
            // Same silence rule as /validate (403, no leak).
            throw new ForbiddenException();
        }

        // Token-flag gate: the type must be one the token explicitly asked for.
        if (!isTypeAllowedByToken(token, type)) {
            throw badRequest(OnboardingErrorCodes.DOCUMENT_TYPE_NOT_ALLOWED);
        }

        // Single-shot per type — first-pass check (race-safe DB unique key
        // is the actual source of truth and is enforced again at persist).
        if (OnboardingUploadSubmission.existsForTokenAndType(tokenUuid, type)) {
            throw alreadySubmitted();
        }

        // Bytes / MIME / size sanity check.
        if (bytes == null || bytes.length == 0) {
            throw badRequest(OnboardingErrorCodes.EMPTY_FILE);
        }
        if (bytes.length > MAX_BYTES) {
            throw badRequest(OnboardingErrorCodes.FILE_TOO_LARGE);
        }
        String normalisedContentType = normaliseContentType(contentType);
        if (!ALLOWED_MIME_TYPES.contains(normalisedContentType)) {
            throw badRequest(OnboardingErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }
        if (!magicMatches(normalisedContentType, bytes)) {
            // Asserted MIME and actual bytes disagree — refuse rather than
            // trust the public-facing Content-Type header.
            throw badRequest(OnboardingErrorCodes.UNSUPPORTED_MEDIA_TYPE);
        }

        // ── 1b. AI validation gate. Synchronous; fail-closed. ────────────
        // Runs after structural checks but before any storage write so
        // rejected documents never reach S3.
        OnboardingDocumentValidationService.ValidationDecision aiDecision =
                documentValidationService.validate(type, bytes, normalisedContentType);
        if (!aiDecision.approved()) {
            log.infof("Onboarding upload AI-rejected token=%s type=%s reasonLen=%d",
                    tokenUuid, type, aiDecision.reason() == null ? 0 : aiDecision.reason().length());
            throw aiRejected(sanitiseReasonForBody(aiDecision.reason()));
        }

        // Sanitise filename — never trust public input as a path component.
        String safeFilename = sanitiseFilename(filename);
        if (safeFilename.isBlank()) {
            throw badRequest(OnboardingErrorCodes.INVALID_FILENAME);
        }

        // Branch on token ownership (XOR enforced both at DB and here).
        boolean candidateFlow = token.getCandidateUuid() != null;
        boolean userFlow = token.getUserUuid() != null;
        if (candidateFlow == userFlow) {
            // Either both null or both set — token row is corrupt; fail closed.
            log.errorf("Onboarding token %s has invalid owner state (candidate=%s,user=%s)",
                    tokenUuid, token.getCandidateUuid(), token.getUserUuid());
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }

        // ── 2. Pre-resolve Slack notification context (no further reads
        //       needed once we hand off to the notifier). ────────────────
        NotificationContext notifyCtx = candidateFlow
                ? buildCandidateNotificationContext(token.getCandidateUuid())
                : buildUserNotificationContextFromDb(token.getUserUuid());

        // ── 3. Upload bytes — outside any DB transaction. ────────────────
        OnboardingUploadSubmission row = new OnboardingUploadSubmission();
        row.setTokenUuid(tokenUuid);
        row.setDocumentType(type);
        row.setOriginalFilename(safeFilename);
        row.setContentType(normalisedContentType);
        row.setFileSizeBytes(bytes.length);

        // Storage handles for compensating delete on persist failure.
        String uploadedS3FileUuid = null;
        String uploadedEmployeeDocumentUuid = null;

        if (candidateFlow) {
            String fileUuid = recruitmentS3StorageService.storeIdentityDocument(
                    bytes, safeFilename, UUID.fromString(token.getCandidateUuid()));
            row.setCandidateUuid(token.getCandidateUuid());
            row.setS3FileUuid(fileUuid);
            uploadedS3FileUuid = fileUuid;
        } else {
            // Employee-documents path (spec §6.5.4): straight into the
            // user's employee file — category IDENTITY, source ONBOARDING,
            // system writer (no interactive actor).
            dk.trustworks.intranet.documentservice.model.EmployeeDocument doc =
                    employeeDocumentService.store(new dk.trustworks.intranet.documentservice.services
                            .EmployeeDocumentService.StoreCommand(
                            // No display name on purpose: the label below is
                            // already a human one ("Passport"), and both UIs
                            // fall back to it. Naming from the filename would
                            // downgrade "Passport" to "Img 3317".
                            token.getUserUuid(), bytes, safeFilename, null, normalisedContentType,
                            dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory.IDENTITY,
                            humanDocumentTypeLabel(type),
                            dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource.ONBOARDING,
                            // hrOnly=true: a driver's licence, sundhedskort or
                            // straffeattest is HR-only by nature. Onboarding used
                            // to write these with hr_only=0, which was only safe
                            // while employee_documents.ui.self-service stayed off
                            // — an accident waiting on a feature flag. Applies to
                            // new uploads only; rows already stored keep hr_only=0.
                            null, null, true, false, null, null, false));
            row.setUserUuid(token.getUserUuid());
            row.setEmployeeDocumentUuid(doc.getUuid());
            uploadedEmployeeDocumentUuid = doc.getUuid();
        }

        // ── 4. Persist audit row in a narrow REQUIRED tx. ────────────────
        try {
            onboardingSubmissionPersister.persist(row);
        } catch (RuntimeException pe) {
            // Concurrent racer beat us to (token, type). Compensate the
            // just-uploaded storage object (orphan otherwise) and translate
            // to 409 ALREADY_SUBMITTED — what the second caller would have
            // received from the precheck.
            if (isUniqueViolation(pe)) {
                compensateStorageUpload(uploadedS3FileUuid, uploadedEmployeeDocumentUuid, tokenUuid, type);
                throw alreadySubmitted();
            }
            // Any other persistence failure → still try to compensate (we
            // would otherwise leave an orphan in storage with no audit row),
            // then re-throw so the resource maps it to a 5xx.
            compensateStorageUpload(uploadedS3FileUuid, uploadedEmployeeDocumentUuid, tokenUuid, type);
            throw pe;
        }

        log.infof("Onboarding upload stored token=%s type=%s storage=%s submission=%s",
                tokenUuid, type, candidateFlow ? "CANDIDATE_S3" : "EMPLOYEE_DOCS", row.getUuid());

        // ── 5. Re-read submissions outside the persist tx and notify. ────
        List<OnboardingUploadSubmission> all = OnboardingUploadSubmission.findByToken(tokenUuid);
        if (allRequiredTypesSubmitted(token, all)) {
            try {
                recruitmentHrSlackNotifier.notifyOnboardingComplete(
                        token, all, notifyCtx.displayName(), notifyCtx.linkUrl());
            } catch (Exception e) {
                // Notification is best-effort — never roll back a stored upload.
                log.errorf(e, "Slack onboarding-complete notification failed for token=%s", tokenUuid);
            }
        }

        return buildResponse(token, all);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Pre-resolved context for the HR Slack notifier — see the
     * {@link RecruitmentHrSlackNotifier#notifyOnboardingComplete} signature.
     */
    private record NotificationContext(String displayName, String linkUrl) { }

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

    private NotificationContext buildCandidateNotificationContext(String candidateUuid) {
        String displayName = "unknown";
        try {
            RecruitmentCandidate c = RecruitmentCandidate.findById(candidateUuid);
            if (c != null) {
                String full = (nullSafe(c.getFirstName()) + " " + nullSafe(c.getLastName())).trim();
                if (!full.isEmpty()) displayName = full;
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve candidate name for uuid=%s", candidateUuid);
        }
        String linkUrl = stripTrailingSlash(dossierBaseUrl) + "/" + candidateUuid;
        return new NotificationContext(displayName, linkUrl);
    }

    /**
     * User-flow display context for the employee-documents path. No link
     * URL: HR finds the documents on the employee's HR Documents tab.
     */
    private NotificationContext buildUserNotificationContextFromDb(String userUuid) {
        String displayName = "unknown";
        try {
            User user = User.findById(userUuid);
            if (user != null) {
                String full = (nullSafe(user.getFirstname()) + " " + nullSafe(user.getLastname())).trim();
                String username = nullSafe(user.getUsername());
                displayName = full.isEmpty() ? username : full + " (" + username + ")";
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve user name for uuid=%s", userUuid);
        }
        return new NotificationContext(displayName, "");
    }

    /**
     * Masked name of the token owner, for the public upload page's
     * "you are uploading for …" check. {@code null} when the owner row is
     * gone or carries no name — the page then renders as it did before the
     * check existed rather than showing an empty confirmation.
     *
     * <p>Never throws: {@code /validate} answers 200 for every valid token
     * today, and a name lookup is not a reason to start failing it.</p>
     *
     * <p>PII boundary (see class Javadoc): the resolved name is returned but
     * never logged.</p>
     */
    public String resolveMaskedOwnerName(OnboardingUploadToken token) {
        if (token == null) return null;
        try {
            if (token.getCandidateUuid() != null) {
                RecruitmentCandidate c = RecruitmentCandidate.findById(token.getCandidateUuid());
                return c == null ? null : maskDisplayName(c.getFirstName(), c.getLastName());
            }
            if (token.getUserUuid() != null) {
                User u = User.findById(token.getUserUuid());
                return u == null ? null : maskDisplayName(u.getFirstname(), u.getLastname());
            }
        } catch (RuntimeException e) {
            log.debugf(e, "Could not resolve masked owner name for token=%s", token.getUuid());
        }
        return null;
    }

    /**
     * Given name plus the initial of the surname — "Henrik Falch Midtgaard"
     * becomes "Henrik F.".
     *
     * <p>The initial comes from the <i>first</i> token of the surname field.
     * Danish records commonly carry middle names there ("Falch Midtgaard"),
     * and the first token is what a recipient recognises from their own
     * paperwork.</p>
     *
     * <p>Degrades rather than emitting something meaningless: a record with
     * no given name yields the first surname token in full (a bare initial
     * would tell the visitor nothing), a record with no surname yields the
     * given name alone, and an empty record yields {@code null}.</p>
     */
    static String maskDisplayName(String firstname, String lastname) {
        String first = nullSafe(firstname).trim();
        String last = nullSafe(lastname).trim();
        if (first.isEmpty() && last.isEmpty()) return null;
        if (first.isEmpty()) return firstNameToken(last);
        if (last.isEmpty()) return first;
        String initial = letterInitialOf(firstNameToken(last));
        return initial.isEmpty() ? first : first + " " + initial + ".";
    }

    /** First whitespace-delimited token of a name field, or "" if there is none. */
    private static String firstNameToken(String name) {
        for (String part : name.split("\\s+")) {
            if (!part.isEmpty()) return part;
        }
        return "";
    }

    /**
     * Uppercased first character of {@code token}, or "" when it does not
     * start with a letter. Reads a full code point so a surrogate pair is
     * never split in half, and folds with {@link Locale#ROOT} so a Turkish
     * locale on the server cannot turn "I" into "ı".
     */
    private static String letterInitialOf(String token) {
        if (token.isEmpty()) return "";
        int cp = token.codePointAt(0);
        if (!Character.isLetter(cp)) return "";
        return new String(Character.toChars(cp)).toUpperCase(Locale.ROOT);
    }

    /** Human label for the identity-document type (used as the document label). */
    static String humanDocumentTypeLabel(OnboardingDocumentType type) {
        return switch (type) {
            case DRIVERS_LICENSE -> "Driver's license";
            case HEALTH_INSURANCE -> "Health insurance card";
            case CRIMINAL_RECORD -> "Criminal record certificate";
        };
    }

    /**
     * Best-effort compensating delete of a just-uploaded storage object.
     * Failures are logged at ERROR with all metadata needed to manually
     * clean up the orphan; we never propagate a compensation failure to
     * the caller — they already have a 409/500 to deal with.
     */
    private void compensateStorageUpload(String s3FileUuid, String employeeDocumentUuid,
                                         String tokenUuid, OnboardingDocumentType type) {
        if (employeeDocumentUuid != null) {
            try {
                employeeDocumentService.delete(employeeDocumentUuid, null);
                log.infof("Compensating delete OK token=%s type=%s storage=EMPLOYEE_DOCS uuid=%s",
                        tokenUuid, type, employeeDocumentUuid);
            } catch (RuntimeException e) {
                log.errorf(e, "ORPHAN: compensating employee-document delete FAILED token=%s type=%s uuid=%s",
                        tokenUuid, type, employeeDocumentUuid);
            }
            return;
        }
        if (s3FileUuid != null) {
            try {
                recruitmentS3StorageService.deleteGeneratedPdf(s3FileUuid);
                log.infof("Compensating delete OK token=%s type=%s storage=S3 fileUuid=%s",
                        tokenUuid, type, s3FileUuid);
            } catch (RuntimeException e) {
                log.errorf(e, "ORPHAN: compensating S3 delete FAILED token=%s type=%s fileUuid=%s",
                        tokenUuid, type, s3FileUuid);
            }
        }
    }

    /**
     * True if the given exception (or any of its causes) is a Hibernate / JPA
     * unique-constraint violation. Hibernate sometimes wraps the underlying
     * {@code org.hibernate.exception.ConstraintViolationException} inside a
     * {@link PersistenceException}; some Quarkus paths rewrap further.
     */
    static boolean isUniqueViolation(Throwable t) {
        Throwable cur = t;
        for (int depth = 0; cur != null && depth < 8; depth++) {
            String name = cur.getClass().getName();
            if ("org.hibernate.exception.ConstraintViolationException".equals(name)) {
                return true;
            }
            // SQLIntegrityConstraintViolationException is the JDBC layer.
            if ("java.sql.SQLIntegrityConstraintViolationException".equals(name)) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private static WebApplicationException alreadySubmitted() {
        return new WebApplicationException(
                Response.status(Response.Status.CONFLICT)
                        .entity("{\"error\":\"" + OnboardingErrorCodes.ALREADY_SUBMITTED + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    private static BadRequestException badRequest(String code) {
        return new BadRequestException(
                Response.status(Response.Status.BAD_REQUEST)
                        .entity("{\"error\":\"" + code + "\"}")
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    /**
     * Build a {@code 422 Unprocessable Entity} response carrying the AI
     * validator's user-facing reason. Distinct from the existing 400/409
     * codes so the frontend can render the AI reason in-zone rather than
     * the generic "could not save" copy.
     *
     * <p>The reason is the model's own text. We escape JSON specials but
     * never log it at WARN — the field is part of the public response body.</p>
     */
    private static WebApplicationException aiRejected(String reason) {
        String safe = reason == null ? "" : reason
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
        String body = "{\"error\":\"" + OnboardingErrorCodes.AI_REJECTED + "\",\"reason\":\"" + safe + "\"}";
        return new WebApplicationException(
                Response.status(422)
                        .entity(body)
                        .type(MediaType.APPLICATION_JSON)
                        .build());
    }

    /**
     * Strip ASCII control characters (U+0000–U+001F) from an AI-supplied
     * reason before it is embedded into the JSON response body. The model
     * is prompted for "one short sentence", but a misbehaving model could
     * still emit a stray newline or tab, which would produce invalid JSON
     * if pasted as-is into a string literal. Replace each control char
     * with a space; collapse the result to a single line.
     */
    private static String sanitiseReasonForBody(String reason) {
        if (reason == null) return "";
        return reason.replaceAll("[\\x00-\\x1F]", " ").trim();
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
     *   <li>JPEG: {@code FF D8 FF}</li>
     *   <li>PNG: {@code 89 50 4E 47 0D 0A 1A 0A}</li>
     * </ul>
     */
    static boolean magicMatches(String contentType, byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        return switch (contentType) {
            case "image/jpeg" ->
                    (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8 && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4e && bytes[3] == 0x47
                    && bytes[4] == 0x0d && bytes[5] == 0x0a && bytes[6] == 0x1a && bytes[7] == 0x0a;
            default -> false;
        };
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
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
                new OnboardingValidateResponse.Submitted(dl, hi, cr),
                resolveMaskedOwnerName(token));
    }
}
