package dk.trustworks.intranet.hrletters.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;
import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.documentservice.model.TemplateDocumentEntity;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentSource;
import dk.trustworks.intranet.documentservice.model.enums.TemplateUsage;
import dk.trustworks.intranet.documentservice.security.TemplateAccessPolicy;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService;
import dk.trustworks.intranet.documentservice.services.EmployeeDocumentService.StoreCommand;
import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.hrletters.dto.HrLetterDTO;
import dk.trustworks.intranet.hrletters.model.HrLetter;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterStatus;
import dk.trustworks.intranet.hrletters.model.enums.HrLetterType;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.utils.dto.signing.PreviewTemplateResponse;
import dk.trustworks.intranet.vacationservice.model.enums.VacationEntrySource;
import dk.trustworks.intranet.vacationservice.services.VacationLedgerService;
import dk.trustworks.intranet.utils.services.SigningService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HR letters: signature-less salary-regulation notices and vacation-transfer
 * agreements. See {@code V528__Hr_letters.sql} for the flow and legal basis.
 *
 * <p>Salary drafts are appended from {@link
 * dk.trustworks.intranet.aggregates.users.services.SalaryService}'s write
 * path (in the same transaction, but guarded so a letter problem never
 * breaks a salary save). Vacation requests are created by the employee.
 * Approval renders the chosen EMPLOYEE_SIGNING document template through
 * the existing Word→PDF engine, files the result in the employee-documents
 * S3 store and announces it in a Slack DM.</p>
 */
@JBossLog
@ApplicationScoped
public class HrLetterService {

    static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");
    private static final Locale DANISH = Locale.of("da", "DK");
    private static final DateTimeFormatter STAMP_FORMAT =
            DateTimeFormatter.ofPattern("d. MMMM yyyy 'kl.' HH.mm", DANISH);

    /** Placeholder keys the vacation agreement template renders as the agreement facts. */
    static final String KEY_TRANSFER_DAYS = "TRANSFER_DAYS";
    static final String KEY_FROM_VACATION_YEAR = "FROM_VACATION_YEAR";
    static final String KEY_TO_VACATION_YEAR = "TO_VACATION_YEAR";
    static final String KEY_REQUESTED_STAMP = "REQUESTED_STAMP";
    static final String KEY_APPROVED_STAMP = "APPROVED_STAMP";

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    SigningService signingService;

    @Inject
    EmployeeDocumentService employeeDocumentService;

    @Inject
    TemplateAccessPolicy templateAccessPolicy;

    @Inject
    UserService userService;

    @Inject
    SlackService slackService;

    @Inject
    VacationLedgerService vacationLedgerService;

    @ConfigProperty(name = "dk.trustworks.hrletters.base-url", defaultValue = "https://intra.trustworks.dk")
    String frontendBaseUrl;

    // ── Salary draft hooks (called inside SalaryService's transaction) ─────

    /**
     * Draft a salary-regulation letter for a newly created salary row.
     * Skipped for the user's first salary row (the employment contract is
     * the written notice there) and for rows that do not change the amount
     * (benefit-only edits). Never throws — a letter problem must not break
     * the salary save.
     */
    public void onSalaryCreated(Salary salary) {
        try {
            Optional<Salary> prior = priorSalary(salary.getUseruuid(), salary.getActivefrom(), salary.getUuid());
            if (prior.isEmpty()) {
                log.debugf("hr-letters: first salary row for user %s — no letter draft", salary.getUseruuid());
                return;
            }
            if (prior.get().getSalary() == salary.getSalary()) {
                log.debugf("hr-letters: salary unchanged for user %s — no letter draft", salary.getUseruuid());
                return;
            }
            HrLetter letter = HrLetter.findDraftBySalaryUuid(salary.getUuid()).orElseGet(() -> {
                HrLetter fresh = new HrLetter();
                fresh.setUseruuid(salary.getUseruuid());
                fresh.setLetterType(HrLetterType.SALARY_REGULATION);
                fresh.setStatus(HrLetterStatus.DRAFT);
                fresh.setSalaryUuid(salary.getUuid());
                fresh.setRequestedBy(currentActorOrSystem());
                return fresh;
            });
            letter.setPayload(salaryPayload(prior.get(), salary));
            letter.persist();
            // Amounts deliberately not logged — salary data must not reach CloudWatch.
            log.infof("hr-letters: drafted salary letter %s for user %s (salary row %s)",
                    letter.getUuid(), salary.getUseruuid(), salary.getUuid());
        } catch (Exception e) {
            log.errorf(e, "hr-letters: failed to draft salary letter for user %s (salary row %s) — salary save unaffected",
                    salary.getUseruuid(), salary.getUuid());
        }
    }

    /**
     * Keep an existing DRAFT in sync when its salary row is edited. If the
     * edit removes the reason for a letter (no prior row, or no amount
     * change) the stale draft is auto-dismissed. Never throws.
     */
    public void onSalaryUpdated(Salary salary) {
        try {
            Optional<HrLetter> draft = HrLetter.findDraftBySalaryUuid(salary.getUuid());
            if (draft.isEmpty()) {
                return;
            }
            HrLetter letter = draft.get();
            Optional<Salary> prior = priorSalary(salary.getUseruuid(), salary.getActivefrom(), salary.getUuid());
            if (prior.isEmpty() || prior.get().getSalary() == salary.getSalary()) {
                letter.setStatus(HrLetterStatus.DISMISSED);
                letter.setDismissedBy("system");
                letter.setDismissReason("Salary row edited — no longer a salary change");
                log.infof("hr-letters: auto-dismissed stale salary draft %s (salary row %s)",
                        letter.getUuid(), salary.getUuid());
                return;
            }
            letter.setPayload(salaryPayload(prior.get(), salary));
            log.infof("hr-letters: refreshed salary draft %s from edited salary row %s",
                    letter.getUuid(), salary.getUuid());
        } catch (Exception e) {
            log.errorf(e, "hr-letters: failed to refresh salary draft for salary row %s — salary save unaffected",
                    salary.getUuid());
        }
    }

    // ── Vacation transfer request (employee self-service) ─────────────────

    /**
     * Create or replace the employee's pending vacation-transfer request.
     * One DRAFT per employee: re-requesting overwrites the pending request.
     */
    @Transactional
    public HrLetterDTO createVacationTransferRequest(String actorUuid, double days, Integer fromYearOrNull) {
        validateTransferDays(days);
        int fromYear = fromYearOrNull != null ? fromYearOrNull : defaultFromStartYear(LocalDate.now(COPENHAGEN));
        validateFromYear(fromYear, LocalDate.now(COPENHAGEN));

        HrLetter letter = HrLetter.findDraftVacationRequest(actorUuid).orElseGet(() -> {
            HrLetter fresh = new HrLetter();
            fresh.setUseruuid(actorUuid);
            fresh.setLetterType(HrLetterType.VACATION_TRANSFER);
            fresh.setStatus(HrLetterStatus.DRAFT);
            fresh.setRequestedBy(actorUuid);
            return fresh;
        });
        letter.setPayload(vacationPayload(days, fromYear));
        letter.persist();
        log.infof("hr-letters: vacation transfer requested by %s — %s days, %s -> %s",
                actorUuid, formatDays(days), vacationYearLabel(fromYear), vacationYearLabel(fromYear + 1));
        return toDTO(letter);
    }

    /** Employee withdraws their own pending vacation request. */
    @Transactional
    public void withdrawOwn(String letterUuid, String actorUuid) {
        int updated = HrLetter.update(
                "status = ?1, dismissedBy = ?2, dismissReason = ?3, updatedAt = ?4 " +
                        "WHERE uuid = ?5 AND useruuid = ?6 AND letterType = ?7 AND status = ?8",
                HrLetterStatus.DISMISSED, actorUuid, "Trukket tilbage af medarbejderen",
                LocalDateTime.now(), letterUuid, actorUuid,
                HrLetterType.VACATION_TRANSFER, HrLetterStatus.DRAFT);
        if (updated == 0) {
            throw new NotFoundException("No pending vacation request to withdraw");
        }
    }

    // ── Queue reads ────────────────────────────────────────────────────────

    /** HR console list, newest first. */
    public List<HrLetterDTO> listAll(HrLetterStatus status, HrLetterType type) {
        StringBuilder query = new StringBuilder("1 = 1");
        List<Object> params = new ArrayList<>();
        if (status != null) {
            params.add(status);
            query.append(" AND status = ?").append(params.size());
        }
        if (type != null) {
            params.add(type);
            query.append(" AND letterType = ?").append(params.size());
        }
        query.append(" ORDER BY createdAt DESC");
        List<HrLetter> letters = HrLetter.find(query.toString(), params.toArray())
                .page(0, 500).list();
        return letters.stream().map(this::toDTO).toList();
    }

    /**
     * The employee's own letters. Salary drafts are invisible until sent —
     * a raise the employee has not been told about yet must not leak
     * through this surface.
     */
    public List<HrLetterDTO> listOwn(String actorUuid) {
        return HrLetter.findForUser(actorUuid).stream()
                .filter(letter -> letter.getLetterType() == HrLetterType.VACATION_TRANSFER
                        || letter.getStatus() == HrLetterStatus.SENT
                        || letter.getStatus() == HrLetterStatus.ACKNOWLEDGED)
                .map(this::toDTO)
                .toList();
    }

    // ── Approve / dismiss / acknowledge ────────────────────────────────────

    /**
     * Approve a draft: render the chosen template to PDF, file it in the
     * employee-documents store, mark the letter SENT and DM the employee.
     *
     * <p>Deliberately NOT one transaction: PDF rendering and the S3 upload
     * happen outside, the status flip is a conditional update so a
     * concurrent approval loses cleanly (its documents are compensated
     * away and it gets a 409), and the Slack DM is best-effort after
     * everything else has committed.</p>
     */
    public HrLetterDTO approveAndSend(String letterUuid, String actorUuid,
                                      String templateUuid, Map<String, String> formValues) {
        HrLetter letter = requireLetter(letterUuid);
        if (letter.getStatus() != HrLetterStatus.DRAFT) {
            throw new WebApplicationException("Letter is not a draft", Response.Status.CONFLICT);
        }

        DocumentTemplateEntity template = requireEmployeeSigningTemplate(templateUuid);
        List<TemplateDocumentEntity> templateDocuments = requireTemplateDocuments(template);

        Map<String, String> effectiveValues = new HashMap<>(formValues != null ? formValues : Map.of());
        if (letter.getLetterType() == HrLetterType.VACATION_TRANSFER) {
            applyVacationAgreementFacts(letter, actorUuid, effectiveValues);
        }

        List<PreviewTemplateResponse.PreviewDocumentDTO> rendered = signingService.generatePreviewDocuments(
                templateDocuments.stream().map(HrLetterService::toTemplateDocumentDTO).toList(),
                effectiveValues,
                template.getUuid());

        LocalDate today = LocalDate.now(COPENHAGEN);
        EmployeeDocumentCategory category = letter.getLetterType() == HrLetterType.SALARY_REGULATION
                ? EmployeeDocumentCategory.SALARY
                : EmployeeDocumentCategory.VACATION;
        List<String> storedDocumentUuids = new ArrayList<>();
        try {
            for (PreviewTemplateResponse.PreviewDocumentDTO doc : rendered) {
                byte[] pdfBytes = Base64.getDecoder().decode(doc.pdfBase64());
                String filename = letterFilename(letter.getLetterType(), doc.documentName(), today);
                var stored = employeeDocumentService.store(new StoreCommand(
                        letter.getUseruuid(), pdfBytes, filename, null, "application/pdf",
                        category, letterLabel(letter), EmployeeDocumentSource.MANUAL_HR,
                        null, null, false, false, actorUuid, null, false));
                storedDocumentUuids.add(stored.getUuid());
            }

            int claimed = markSent(letter.getUuid(), actorUuid, template.getUuid(), storedDocumentUuids.get(0));
            if (claimed == 0) {
                throw new WebApplicationException(
                        "Letter was handled by someone else in the meantime", Response.Status.CONFLICT);
            }
        } catch (Exception e) {
            compensateStoredDocuments(storedDocumentUuids, actorUuid);
            throw e;
        }

        if (letter.getLetterType() == HrLetterType.VACATION_TRANSFER) {
            postVacationTransferBestEffort(letter, actorUuid);
        }

        notifyEmployeeBestEffort(letter);
        return toDTO(requireLetter(letterUuid));
    }

    @Transactional
    public void dismiss(String letterUuid, String actorUuid, String reason) {
        String trimmedReason = reason == null || reason.isBlank() ? null : reason.trim();
        if (trimmedReason != null && trimmedReason.length() > 500) {
            trimmedReason = trimmedReason.substring(0, 500);
        }
        int updated = HrLetter.update(
                "status = ?1, dismissedBy = ?2, dismissReason = ?3, updatedAt = ?4 WHERE uuid = ?5 AND status = ?6",
                HrLetterStatus.DISMISSED, actorUuid, trimmedReason,
                LocalDateTime.now(), letterUuid, HrLetterStatus.DRAFT);
        if (updated == 0) {
            requireLetter(letterUuid);
            throw new WebApplicationException("Letter is not a draft", Response.Status.CONFLICT);
        }
    }

    /** The employee's read-receipt on a delivered letter. */
    @Transactional
    public HrLetterDTO acknowledge(String letterUuid, String actorUuid) {
        int updated = HrLetter.update(
                "status = ?1, acknowledgedAt = ?2, updatedAt = ?2 WHERE uuid = ?3 AND useruuid = ?4 AND status = ?5",
                HrLetterStatus.ACKNOWLEDGED, LocalDateTime.now(), letterUuid, actorUuid, HrLetterStatus.SENT);
        if (updated == 0) {
            throw new NotFoundException("No delivered letter to acknowledge");
        }
        return toDTO(requireLetter(letterUuid));
    }

    // ── Internals ──────────────────────────────────────────────────────────

    @Transactional
    int markSent(String letterUuid, String actorUuid, String templateUuid, String employeeDocumentUuid) {
        return HrLetter.update(
                "status = ?1, approvedBy = ?2, templateUuid = ?3, employeeDocumentUuid = ?4, sentAt = ?5, updatedAt = ?5 " +
                        "WHERE uuid = ?6 AND status = ?7",
                HrLetterStatus.SENT, actorUuid, templateUuid, employeeDocumentUuid,
                LocalDateTime.now(), letterUuid, HrLetterStatus.DRAFT);
    }

    private HrLetter requireLetter(String letterUuid) {
        HrLetter letter = HrLetter.findById(letterUuid);
        if (letter == null) {
            throw new NotFoundException("Letter not found");
        }
        return letter;
    }

    private DocumentTemplateEntity requireEmployeeSigningTemplate(String templateUuid) {
        if (templateUuid == null || templateUuid.isBlank()) {
            throw new BadRequestException("A document template is required");
        }
        DocumentTemplateEntity template = DocumentTemplateEntity.findById(templateUuid.trim());
        if (template == null || !template.isActive()
                || template.getTemplateUsage() != TemplateUsage.EMPLOYEE_SIGNING) {
            throw new BadRequestException("Template is not available for employee letters");
        }
        return template;
    }

    private List<TemplateDocumentEntity> requireTemplateDocuments(DocumentTemplateEntity template) {
        List<TemplateDocumentEntity> documents = TemplateDocumentEntity.findByTemplateUuid(template.getUuid());
        List<TemplateDocumentEntity> usable = documents.stream()
                .filter(doc -> doc.getFileUuid() != null && !doc.getFileUuid().isBlank())
                .toList();
        if (usable.isEmpty()) {
            throw new BadRequestException("The template has no Word document uploaded");
        }
        // Legacy file uuids were not unique; refuse a file that is also owned
        // by a recruitment/dossier template (mirrors SigningResource).
        for (TemplateDocumentEntity doc : usable) {
            boolean restrictedOwner = TemplateDocumentEntity.findByFileUuid(doc.getFileUuid()).stream()
                    .anyMatch(owner -> templateAccessPolicy.isRecruitmentTemplate(owner.getTemplate()));
            if (restrictedOwner) {
                throw new BadRequestException("Template document is not available for employee letters");
            }
        }
        return usable;
    }

    /**
     * The agreement facts and both consent stamps are server-authoritative:
     * the employee consented to exactly the requested days/years, and the
     * stamps are the signature replacement — none of it is HR-editable.
     */
    private void applyVacationAgreementFacts(HrLetter letter, String approverUuid, Map<String, String> values) {
        JsonNode payload = readPayload(letter);
        double days = payload.path("days").asDouble();
        int fromYear = payload.path("fromYear").asInt();

        values.put(KEY_TRANSFER_DAYS, formatDays(days));
        values.put(KEY_FROM_VACATION_YEAR, vacationYearLabel(fromYear));
        values.put(KEY_TO_VACATION_YEAR, vacationYearLabel(fromYear + 1));
        values.put(KEY_REQUESTED_STAMP, consentStamp("Anmodet af", letter.getUseruuid(),
                letter.getCreatedAt().atZone(ZoneId.systemDefault()).withZoneSameInstant(COPENHAGEN)));
        values.put(KEY_APPROVED_STAMP, consentStamp("Godkendt af", approverUuid, ZonedDateTime.now(COPENHAGEN)));
    }

    private String consentStamp(String verb, String userUuid, ZonedDateTime at) {
        String name = userUuid;
        try {
            User user = userService.findById(userUuid, true);
            if (user != null && user.getFullname() != null && !user.getFullname().isBlank()) {
                name = user.getFullname();
            }
        } catch (Exception e) {
            log.warnf("hr-letters: could not resolve name for %s: %s", userUuid, e.getMessage());
        }
        return verb + " " + name + " den " + STAMP_FORMAT.format(at) + " via intranettet";
    }

    /**
     * The signed agreement is the source of truth; the ledger follows it.
     * Idempotent per letter uuid (a retried approval never double-posts), and
     * a posting failure must not undo the approval — it is logged loudly for
     * a manual posting instead.
     */
    private void postVacationTransferBestEffort(HrLetter letter, String actorUuid) {
        try {
            JsonNode payload = readPayload(letter);
            double days = payload.path("days").asDouble();
            int fromYear = payload.path("fromYear").asInt();
            vacationLedgerService.applyTransfer(letter.getUseruuid(), fromYear, days,
                    VacationEntrySource.HR_LETTER, letter.getUuid(),
                    "Ferieoverførsel " + vacationYearLabel(fromYear) + " → " + vacationYearLabel(fromYear + 1),
                    actorUuid);
        } catch (Exception e) {
            log.errorf(e, "hr-letters: letter %s is approved but the vacation ledger posting FAILED — post the transfer manually",
                    letter.getUuid());
        }
    }

    private void compensateStoredDocuments(List<String> documentUuids, String actorUuid) {
        for (String docUuid : documentUuids) {
            try {
                employeeDocumentService.delete(docUuid, actorUuid);
            } catch (Exception cleanupError) {
                log.errorf(cleanupError, "hr-letters: failed to clean up document %s after aborted approval", docUuid);
            }
        }
    }

    private void notifyEmployeeBestEffort(HrLetter letter) {
        try {
            User user = userService.findById(letter.getUseruuid(), true);
            if (user == null) {
                return;
            }
            String what = letter.getLetterType() == HrLetterType.SALARY_REGULATION
                    ? "Lønregulering"
                    : "Aftale om ferieoverførsel";
            String message = ":page_facing_up: Du har fået et nyt dokument på din profil: *" + what + "*.\n" +
                    "Se dokumentet og kvittér for modtagelsen under fanen *Documents* på din profil: " +
                    frontendBaseUrl + "/profile";
            slackService.sendMessage(user, message);
        } catch (Exception e) {
            log.warnf("hr-letters: Slack notification for letter %s failed (document is delivered on the profile): %s",
                    letter.getUuid(), e.getMessage());
        }
    }

    private static TemplateDocumentDTO toTemplateDocumentDTO(TemplateDocumentEntity entity) {
        return TemplateDocumentDTO.builder()
                .uuid(entity.getUuid())
                .documentName(entity.getDocumentName())
                .fileUuid(entity.getFileUuid())
                .originalFilename(entity.getOriginalFilename())
                .displayOrder(entity.getDisplayOrder())
                .build();
    }

    private Optional<Salary> priorSalary(String useruuid, LocalDate activefrom, String excludeUuid) {
        return Salary.<Salary>find(
                        "useruuid = ?1 AND activefrom < ?2 AND uuid <> ?3 ORDER BY activefrom DESC",
                        useruuid, activefrom, excludeUuid == null ? "" : excludeUuid)
                .firstResultOptional();
    }

    private String salaryPayload(Salary prior, Salary current) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("oldSalary", prior.getSalary());
        node.put("newSalary", current.getSalary());
        node.put("adjustment", current.getSalary() - prior.getSalary());
        node.put("effectiveDate", current.getActivefrom().toString());
        node.put("salaryType", current.getType() != null ? current.getType().name() : null);
        node.put("previousType", prior.getType() != null ? prior.getType().name() : null);
        return node.toString();
    }

    private String vacationPayload(double days, int fromYear) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("days", days);
        node.put("fromYear", fromYear);
        node.put("toYear", fromYear + 1);
        return node.toString();
    }

    private JsonNode readPayload(HrLetter letter) {
        try {
            return objectMapper.readTree(letter.getPayload());
        } catch (Exception e) {
            throw new WebApplicationException("Letter payload is unreadable", Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private HrLetterDTO toDTO(HrLetter letter) {
        return new HrLetterDTO(
                letter.getUuid(),
                letter.getUseruuid(),
                letter.getLetterType(),
                letter.getStatus(),
                readPayload(letter),
                letter.getSalaryUuid(),
                letter.getTemplateUuid(),
                letter.getEmployeeDocumentUuid(),
                letter.getRequestedBy(),
                letter.getApprovedBy(),
                letter.getDismissedBy(),
                letter.getDismissReason(),
                letter.getSentAt(),
                letter.getAcknowledgedAt(),
                letter.getCreatedAt(),
                letter.getUpdatedAt());
    }

    private String currentActorOrSystem() {
        String actor = requestHeaderHolder.getUserUuid();
        return actor == null || actor.isBlank() ? "system" : actor;
    }

    // ── Pure helpers (package-private for DB-free unit tests) ──────────────

    /**
     * The September start-year of the vacation year whose holding period
     * ends 31 December of the given date's calendar year: at any date in
     * year Y that is the vacation year running 1 Sep (Y-1) – 31 Aug Y.
     */
    static int defaultFromStartYear(LocalDate today) {
        return today.getYear() - 1;
    }

    /** "2024/2025"-style label for the vacation year starting 1 September of {@code startYear}. */
    static String vacationYearLabel(int startYear) {
        return startYear + "/" + (startYear + 1);
    }

    /** Days rendered Danish-style: whole numbers bare, halves with a decimal comma. */
    static String formatDays(double days) {
        if (days == Math.rint(days)) {
            return String.valueOf((long) days);
        }
        return String.valueOf(days).replace('.', ',');
    }

    static void validateTransferDays(double days) {
        boolean halfDayMultiple = Math.rint(days * 2) == days * 2;
        if (!(days > 0) || days > 10 || !halfDayMultiple) {
            throw new BadRequestException("Days must be between 0.5 and 10 in half-day steps");
        }
    }

    static void validateFromYear(int fromYear, LocalDate today) {
        int defaultYear = defaultFromStartYear(today);
        if (fromYear < defaultYear - 1 || fromYear > defaultYear + 1) {
            throw new BadRequestException("From-year must be within one year of the current vacation year");
        }
    }

    /** e.g. {@code 2026-08-23_SALARY_loenregulering.pdf} — mirrors the existing corpus naming. */
    static String letterFilename(HrLetterType type, String documentName, LocalDate date) {
        String prefix = type == HrLetterType.SALARY_REGULATION ? "SALARY" : "VACATION";
        String base = documentName == null ? "" : documentName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            base = base.substring(0, base.length() - 4);
        }
        String slug = base.toLowerCase(DANISH)
                .replace("æ", "ae").replace("ø", "oe").replace("å", "aa")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isBlank()) {
            slug = prefix.toLowerCase(Locale.ROOT);
        }
        return date + "_" + prefix + "_" + slug + ".pdf";
    }

    private String letterLabel(HrLetter letter) {
        JsonNode payload = readPayload(letter);
        if (letter.getLetterType() == HrLetterType.SALARY_REGULATION) {
            return "Lønregulering pr. " + payload.path("effectiveDate").asText();
        }
        return "Ferieoverførsel " + vacationYearLabel(payload.path("fromYear").asInt())
                + " → " + vacationYearLabel(payload.path("fromYear").asInt() + 1);
    }
}
