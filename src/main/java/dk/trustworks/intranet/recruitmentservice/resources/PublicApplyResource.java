package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.dto.PublicApplyFormResponse;
import dk.trustworks.intranet.recruitmentservice.dto.PublicApplySubmission;
import dk.trustworks.intranet.recruitmentservice.dto.PublicApplySubmission.UploadedDocument;
import dk.trustworks.intranet.recruitmentservice.dto.PublicUnsolicitedFormResponse;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.services.PublicApplyQuestions;
import dk.trustworks.intranet.recruitmentservice.services.PublicApplyService;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentFeatureFlag;
import dk.trustworks.intranet.recruitmentservice.util.PublicApplyDocuments;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The P5 public application surface — the ONLY recruitment endpoints an
 * anonymous caller can reach ({@code @PermitAll}, mirroring
 * {@link OnboardingResource}). Thin by convention: flag gate → explicit
 * validation ({@code @Valid} is inert in this repo) → delegate to
 * {@link PublicApplyService}.
 *
 * <h3>Route note</h3>
 * The literal {@code /apply/unsolicited} beats the template
 * {@code /apply/{slug}} per JAX-RS matching; additionally
 * {@code unsolicited}/{@code privacy}/{@code config} are reserved slugs a
 * position can never claim ({@code RecruitmentPositionService}).
 *
 * <h3>Anonymous-caller rules</h3>
 * <ul>
 *   <li>Flag off, unknown slug, slug-less or non-OPEN position: uniform
 *       {@code 404 {"error":"NOT_FOUND"}} — no distinction, no admin
 *       bypass (the surface stays dark until the flag is on).</li>
 *   <li>Validation failures: {@code 400 {"error":"<CODE>"}}, code-only —
 *       submitted values are NEVER echoed.</li>
 *   <li>Success is always the same generic {@code 201
 *       {"status":"RECEIVED"}} — including the duplicate-submission path,
 *       so an attacker cannot learn that an email already applied.</li>
 *   <li>Defense in depth: {@code PublicApplyRateLimitFilter} throttles
 *       POSTs per source IP before this class runs.</li>
 * </ul>
 */
@JBossLog
@RequestScoped
@Path("/apply")
@Produces(MediaType.APPLICATION_JSON)
public class PublicApplyResource {

    private static final Pattern EMAIL_FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final String RECEIVED_BODY = "{\"status\":\"RECEIVED\"}";

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    PublicApplyService publicApplyService;

    // ---- Form config (GET) ---------------------------------------------------

    /** Question set + active practices for the unsolicited form. */
    @GET
    @PermitAll
    @Path("/unsolicited")
    public PublicUnsolicitedFormResponse unsolicitedForm() {
        requireFlag();
        return publicApplyService.unsolicitedForm();
    }

    /** Position title + question set for a position form. */
    @GET
    @PermitAll
    @Path("/{slug}")
    public PublicApplyFormResponse positionForm(@PathParam("slug") String slug) {
        requireFlag();
        return publicApplyService.positionForm(slug);
    }

    // ---- Submissions (POST, multipart) ---------------------------------------

    @POST
    @PermitAll
    @Path("/unsolicited")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response submitUnsolicited(
            @RestForm("firstName") String firstName,
            @RestForm("lastName") String lastName,
            @RestForm("email") String email,
            @RestForm("phone") String phone,
            @RestForm("linkedinUrl") String linkedinUrl,
            @RestForm("educationLevel") String educationLevel,
            @RestForm("educationOther") String educationOther,
            @RestForm("experienceLevel") String experienceLevel,
            @RestForm("channel") String channel,
            @RestForm("selfReportedSource") String selfReportedSource,
            @RestForm("sourceFollowUp") String sourceFollowUp,
            @RestForm("answer_WHY_TRUSTWORKS") String answerWhyTrustworks,
            @RestForm("answer_BEST_TASKS") String answerBestTasks,
            @RestForm("answer_DNA_MATCH") String answerDnaMatch,
            @RestForm("answer_STRENGTHS") String answerStrengths,
            @RestForm("poolConsent") String poolConsent,
            @RestForm("desiredPracticeUuid") String desiredPracticeUuid,
            @RestForm("cv") FileUpload cv,
            @RestForm("coverLetter") FileUpload coverLetter) {
        requireFlag();
        PublicApplySubmission submission = buildSubmission(
                firstName, lastName, email, phone, linkedinUrl,
                educationLevel, educationOther, experienceLevel,
                channel, selfReportedSource, sourceFollowUp,
                answerWhyTrustworks, answerBestTasks, answerDnaMatch, answerStrengths,
                poolConsent, cv, coverLetter, desiredPracticeUuid);
        publicApplyService.submitUnsolicited(submission);
        return received();
    }

    @POST
    @PermitAll
    @Path("/{slug}")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response submitForPosition(
            @PathParam("slug") String slug,
            @RestForm("firstName") String firstName,
            @RestForm("lastName") String lastName,
            @RestForm("email") String email,
            @RestForm("phone") String phone,
            @RestForm("linkedinUrl") String linkedinUrl,
            @RestForm("educationLevel") String educationLevel,
            @RestForm("educationOther") String educationOther,
            @RestForm("experienceLevel") String experienceLevel,
            @RestForm("channel") String channel,
            @RestForm("selfReportedSource") String selfReportedSource,
            @RestForm("sourceFollowUp") String sourceFollowUp,
            @RestForm("answer_WHY_TRUSTWORKS") String answerWhyTrustworks,
            @RestForm("answer_BEST_TASKS") String answerBestTasks,
            @RestForm("answer_DNA_MATCH") String answerDnaMatch,
            @RestForm("answer_STRENGTHS") String answerStrengths,
            @RestForm("poolConsent") String poolConsent,
            @RestForm("cv") FileUpload cv,
            @RestForm("coverLetter") FileUpload coverLetter) {
        requireFlag();
        // 404 BEFORE 400: an invalid body against an unknown slug must not
        // reveal whether the slug exists.
        publicApplyService.requireOpenPosition(slug);
        PublicApplySubmission submission = buildSubmission(
                firstName, lastName, email, phone, linkedinUrl,
                educationLevel, educationOther, experienceLevel,
                channel, selfReportedSource, sourceFollowUp,
                answerWhyTrustworks, answerBestTasks, answerDnaMatch, answerStrengths,
                poolConsent, cv, coverLetter, null);
        publicApplyService.submitForPosition(slug, submission);
        return received();
    }

    // ---- Flag gate -------------------------------------------------------------

    /**
     * ALL public endpoints go dark when {@code recruitment.pipeline.enabled}
     * is off — uniform 404, no admin bypass for anonymous callers.
     */
    private void requireFlag() {
        if (!featureFlag.isPipelineEnabled()) {
            throw PublicApplyService.publicNotFound();
        }
    }

    // ---- Validation (explicit — @Valid is inert in this repo) ------------------

    private PublicApplySubmission buildSubmission(
            String firstName, String lastName, String email, String phone, String linkedinUrl,
            String educationLevel, String educationOther, String experienceLevel,
            String channel, String selfReportedSource, String sourceFollowUp,
            String answerWhyTrustworks, String answerBestTasks,
            String answerDnaMatch, String answerStrengths,
            String poolConsent, FileUpload cv, FileUpload coverLetter,
            String desiredPracticeUuid) {

        String first = requiredField(firstName, 100);
        String last = requiredField(lastName, 100);
        String mail = validatedEmail(email);
        String phoneValue = optionalField(phone, 40);
        String linkedin = optionalField(linkedinUrl, 300);
        String educationOtherValue = optionalField(educationOther, 200);
        String followUp = optionalField(sourceFollowUp, 200);

        CandidateEducationLevel education = parseOptionalEnum(
                CandidateEducationLevel.class, educationLevel);
        CandidateExperienceLevel experience = parseOptionalEnum(
                CandidateExperienceLevel.class, experienceLevel);
        CandidateSource entryChannel = parseChannel(channel);
        String selfReported = parseSelfReportedSource(selfReportedSource);

        Map<String, String> answers = collectAnswers(
                answerWhyTrustworks, answerBestTasks, answerDnaMatch, answerStrengths);

        if (!hasContent(cv)) {
            throw badRequest("FILE_REQUIRED");
        }
        UploadedDocument cvDocument = readDocument(cv, "cv");
        UploadedDocument coverLetterDocument = hasContent(coverLetter)
                ? readDocument(coverLetter, "cover-letter")
                : null;

        boolean consent = poolConsent != null && Boolean.parseBoolean(poolConsent.trim());

        return new PublicApplySubmission(
                first, last, mail, phoneValue, linkedin,
                education, educationOtherValue, experience,
                entryChannel, selfReported, followUp,
                answers, consent, cvDocument, coverLetterDocument,
                trimToNull(desiredPracticeUuid));
    }

    private static Map<String, String> collectAnswers(String whyTrustworks, String bestTasks,
                                                      String dnaMatch, String strengths) {
        Map<String, String> answers = new LinkedHashMap<>();
        putAnswer(answers, "WHY_TRUSTWORKS", whyTrustworks);
        putAnswer(answers, "BEST_TASKS", bestTasks);
        putAnswer(answers, "DNA_MATCH", dnaMatch);
        putAnswer(answers, "STRENGTHS", strengths);
        return answers;
    }

    private static void putAnswer(Map<String, String> answers, String key, String raw) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        if (trimmed.length() > PublicApplyQuestions.MAX_ANSWER_LENGTH) {
            throw badRequest("ANSWER_TOO_LONG");
        }
        answers.put(key, trimmed);
    }

    private UploadedDocument readDocument(FileUpload upload, String defaultBaseName) {
        // Reject oversize before copying anything into the heap.
        if (upload.size() > PublicApplyDocuments.MAX_BYTES) {
            throw badRequest("FILE_TOO_LARGE");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(upload.uploadedFile());
        } catch (IOException e) {
            log.errorf(e, "[PublicApplyResource] Failed to read uploaded bytes");
            throw new WebApplicationException(Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"UPLOAD_READ_FAILED\"}")
                    .type(MediaType.APPLICATION_JSON)
                    .build());
        }
        if (bytes.length == 0) {
            throw badRequest("EMPTY_FILE");
        }
        String contentType = PublicApplyDocuments.normaliseContentType(upload.contentType());
        if (!PublicApplyDocuments.ALLOWED_MIME_TYPES.contains(contentType)
                || !PublicApplyDocuments.magicMatches(contentType, bytes)) {
            // Asserted MIME and actual bytes must agree — never trust a
            // public-facing Content-Type header.
            throw badRequest("UNSUPPORTED_MEDIA_TYPE");
        }
        String rawName = upload.fileName();
        String safeName = PublicApplyDocuments.sanitiseFilename(rawName);
        if (safeName.isBlank()) {
            safeName = defaultBaseName + extensionFor(contentType);
        }
        String piiName = rawName == null || rawName.isBlank()
                ? safeName
                : (rawName.length() > 255 ? rawName.substring(0, 255) : rawName);
        return new UploadedDocument(bytes, piiName, safeName, contentType);
    }

    private static boolean hasContent(FileUpload upload) {
        return upload != null && upload.size() > 0;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> "";
        };
    }

    private static String requiredField(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw badRequest("MISSING_REQUIRED_FIELD");
        }
        if (trimmed.length() > maxLength) {
            throw badRequest("FIELD_TOO_LONG");
        }
        return trimmed;
    }

    private static String optionalField(String value, int maxLength) {
        String trimmed = trimToNull(value);
        if (trimmed != null && trimmed.length() > maxLength) {
            throw badRequest("FIELD_TOO_LONG");
        }
        return trimmed;
    }

    private static String validatedEmail(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw badRequest("MISSING_REQUIRED_FIELD");
        }
        if (trimmed.length() > 150) {
            throw badRequest("FIELD_TOO_LONG");
        }
        if (!EMAIL_FORMAT.matcher(trimmed).matches()) {
            throw badRequest("INVALID_EMAIL");
        }
        return trimmed;
    }

    /** Optional enums are strict: an unknown value is attacker input → 400. */
    private static <E extends Enum<E>> E parseOptionalEnum(Class<E> type, String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, trimmed.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("INVALID_FIELD");
        }
    }

    /** Entry channel: WEBSITE | LINKEDIN_AD | JOBINDEX; anything else/missing → WEBSITE. */
    private static CandidateSource parseChannel(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return CandidateSource.WEBSITE;
        }
        return switch (trimmed.toUpperCase(Locale.ROOT)) {
            case "LINKEDIN_AD" -> CandidateSource.LINKEDIN_AD;
            case "JOBINDEX" -> CandidateSource.JOBINDEX;
            default -> CandidateSource.WEBSITE;
        };
    }

    private static String parseSelfReportedSource(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String normalized = trimmed.toUpperCase(Locale.ROOT);
        if (!PublicApplyService.SELF_REPORTED_SOURCES.contains(normalized)) {
            throw badRequest("INVALID_FIELD");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Code-only 400 — submitted values are never echoed to anonymous callers. */
    private static WebApplicationException badRequest(String code) {
        return new WebApplicationException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }

    private static Response received() {
        return Response.status(Response.Status.CREATED)
                .entity(RECEIVED_BODY)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
