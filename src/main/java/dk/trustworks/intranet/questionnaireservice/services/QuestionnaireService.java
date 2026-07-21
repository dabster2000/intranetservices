package dk.trustworks.intranet.questionnaireservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.Practice;
import dk.trustworks.intranet.services.PracticeService;
import dk.trustworks.intranet.questionnaireservice.dto.CreateQuestionRequest;
import dk.trustworks.intranet.questionnaireservice.dto.CreateQuestionnaireRequest;
import dk.trustworks.intranet.questionnaireservice.dto.CreateSubmissionRequest;
import dk.trustworks.intranet.questionnaireservice.dto.QuestionnaireStatsResponse;
import dk.trustworks.intranet.questionnaireservice.dto.SubmissionAnswerRequest;
import dk.trustworks.intranet.questionnaireservice.model.Questionnaire;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireAnswer;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireQuestion;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireSubmission;
import dk.trustworks.intranet.userservice.model.TeamRole;
import dk.trustworks.intranet.userservice.services.TeamRoleService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class QuestionnaireService {

    @Inject
    TeamRoleService teamRoleService;

    @Inject
    PracticeService practiceService;

    public List<Questionnaire> listAll() {
        List<Questionnaire> all = Questionnaire.listAll();
        all.forEach(this::withDerivedTargetPractices);
        return all;
    }

    public Questionnaire findByUuid(String uuid) {
        Questionnaire q = Questionnaire.findByUuid(uuid);
        if (q == null) {
            throw new WebApplicationException("Questionnaire not found: " + uuid, Response.Status.NOT_FOUND);
        }
        return withDerivedTargetPractices(q);
    }

    /**
     * Populates the wire-compatibility {@code targetPractices} field (a JSON
     * array of registry CODES, transient since Phase 5A) from the persisted
     * uuid array, element order preserved. Codes follow registry renames
     * automatically. A uuid without a registry row (impossible for real data —
     * the writer validates and UD targets are rejected) is skipped with a
     * warning rather than failing the read.
     */
    private Questionnaire withDerivedTargetPractices(Questionnaire q) {
        String uuidArray = q.getTargetPracticeUuids();
        if (uuidArray == null || uuidArray.isBlank()) return q;
        try {
            ObjectMapper mapper = new ObjectMapper();
            List<String> uuids = mapper.readValue(uuidArray,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
            List<String> codes = new ArrayList<>(uuids.size());
            for (String practiceUuid : uuids) {
                Practice row = Practice.<Practice>find("uuid", practiceUuid).firstResult();
                if (row == null) {
                    log.warnf("Questionnaire %s targets unknown practice uuid %s — omitted from targetPractices",
                            q.getUuid(), practiceUuid);
                    continue;
                }
                codes.add(row.getCode());
            }
            q.setTargetPractices(mapper.writeValueAsString(codes));
        } catch (Exception e) {
            log.warnf("Invalid target_practice_uuids JSON for questionnaire %s", q.getUuid());
        }
        return q;
    }

    public List<QuestionnaireSubmission> getSubmissions(String questionnaireUuid) {
        return QuestionnaireSubmission.findByQuestionnaire(questionnaireUuid);
    }

    public List<QuestionnaireSubmission> getMySubmissions(String questionnaireUuid, String userUuid) {
        return QuestionnaireSubmission.findByQuestionnaireAndUser(questionnaireUuid, userUuid);
    }

    public QuestionnaireStatsResponse getStats(String questionnaireUuid) {
        Questionnaire q = findByUuid(questionnaireUuid);

        long totalSubmissions = QuestionnaireSubmission.count("questionnaireUuid", questionnaireUuid);

        long uniqueClients = QuestionnaireSubmission.find("questionnaireUuid", questionnaireUuid)
                .stream()
                .map(s -> ((QuestionnaireSubmission) s).getClientUuid())
                .distinct()
                .count();

        long totalActiveClients = Client.count("active", true);

        int coveragePercent = totalActiveClients > 0
                ? (int) Math.round((double) uniqueClients / totalActiveClients * 100)
                : 0;

        long daysRemaining = q.getDeadline() != null
                ? Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(), q.getDeadline()))
                : 0;

        return new QuestionnaireStatsResponse(
                totalSubmissions, uniqueClients, totalActiveClients, coveragePercent, daysRemaining
        );
    }

    @Transactional
    public QuestionnaireSubmission createSubmission(String questionnaireUuid, String userUuid,
                                                     CreateSubmissionRequest request) {
        Questionnaire q = findByUuid(questionnaireUuid);

        if (!q.isOpen()) {
            throw new BadRequestException("Questionnaire is closed or past deadline");
        }

        if (request.getClientUuid() == null || request.getClientUuid().isBlank()) {
            throw new BadRequestException("clientUuid is required");
        }

        Client client = Client.findById(request.getClientUuid());
        if (client == null || !client.isActive()) {
            throw new BadRequestException("Client not found or inactive: " + request.getClientUuid());
        }

        QuestionnaireSubmission existing = QuestionnaireSubmission.findExisting(
                questionnaireUuid, request.getClientUuid(), userUuid);
        if (existing != null) {
            throw new WebApplicationException(
                    "You have already submitted for this client",
                    Response.Status.CONFLICT
            );
        }

        List<QuestionnaireQuestion> questions = q.getQuestions();
        if (request.getAnswers() == null || request.getAnswers().size() != questions.size()) {
            throw new BadRequestException("All " + questions.size() + " questions must be answered");
        }

        for (SubmissionAnswerRequest answer : request.getAnswers()) {
            if (answer.getAnswerText() == null || answer.getAnswerText().isBlank()) {
                throw new BadRequestException("All questions must have a non-empty answer");
            }
        }

        QuestionnaireSubmission submission = new QuestionnaireSubmission();
        submission.setUuid(UUID.randomUUID().toString());
        submission.setQuestionnaireUuid(questionnaireUuid);
        submission.setClientUuid(request.getClientUuid());
        submission.setUserUuid(userUuid);
        submission.setSubmittedAt(LocalDateTime.now());
        submission.persist();

        for (SubmissionAnswerRequest answerReq : request.getAnswers()) {
            QuestionnaireAnswer answer = new QuestionnaireAnswer();
            answer.setUuid(UUID.randomUUID().toString());
            answer.setSubmission(submission);
            answer.setQuestionUuid(answerReq.getQuestionUuid());
            answer.setAnswerText(answerReq.getAnswerText());
            answer.setAnswerJson(answerReq.getAnswerJson());
            answer.setCreatedAt(LocalDateTime.now());
            answer.persist();
        }

        log.infof("Questionnaire submission created: questionnaire=%s, client=%s, user=%s",
                questionnaireUuid, request.getClientUuid(), userUuid);

        return QuestionnaireSubmission.findById(submission.getUuid());
    }

    @Transactional
    public Questionnaire createQuestionnaire(CreateQuestionnaireRequest request) {
        if (request.getTitle() == null || request.getTitle().isBlank()) {
            throw new BadRequestException("Title is required");
        }
        if (request.getQuestions() == null || request.getQuestions().isEmpty()) {
            throw new BadRequestException("At least one question is required");
        }

        Questionnaire q = new Questionnaire();
        q.setUuid(UUID.randomUUID().toString());
        q.setTitle(request.getTitle().trim());
        q.setDescription(request.getDescription());
        q.setStartDate(request.getStartDate());
        q.setDeadline(request.getDeadline());
        q.setStatus(Questionnaire.QuestionnaireStatus.ACTIVE);
        q.setReminderEnabled(request.isReminderEnabled());
        q.setReminderCooldownDays(request.getReminderCooldownDays() > 0 ? request.getReminderCooldownDays() : 3);
        q.setCreatedAt(LocalDateTime.now());
        q.setUpdatedAt(LocalDateTime.now());

        try {
            ObjectMapper mapper = new ObjectMapper();
            if (request.getTargetPractices() != null && !request.getTargetPractices().isEmpty()) {
                // Phase 3 (spec §4.3): validate each targeting code against the
                // registry — retired codes and unknown values cannot enter.
                // Phase 5A: only the uuid array is persisted (the code array
                // column is dropped by V428); the wire field derives on read.
                List<String> uuids = new ArrayList<>(request.getTargetPractices().size());
                for (String code : request.getTargetPractices()) {
                    // Phase 4: a 'UD' target would silently match nobody — no
                    // operational row stores it anymore (a NULL-practice user
                    // matches only untargeted questionnaires, by design). Reject
                    // it so the dead-letter target cannot be created; the
                    // no-practice population is reachable via "everyone" or
                    // team targeting. No existing row targeted UD at flip time.
                    if (practiceService.normalizeNoPracticeAlias(code) == null) {
                        throw new BadRequestException(
                                "targetPractices cannot contain the no-practice value ('UD'/null) — "
                                + "target everyone or use targetTeams instead");
                    }
                    practiceService.validateUserPracticeAssignable(code);
                    Practice registryRow = Practice.findById(code);
                    if (registryRow == null) {
                        throw new BadRequestException("Unknown practice code in targetPractices: " + code);
                    }
                    uuids.add(registryRow.getUuid());
                }
                q.setTargetPracticeUuids(mapper.writeValueAsString(uuids));
            }
            if (request.getTargetTeams() != null && !request.getTargetTeams().isEmpty()) {
                q.setTargetTeams(mapper.writeValueAsString(request.getTargetTeams()));
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Invalid practices or teams format");
        }

        q.persist();

        for (CreateQuestionRequest qr : request.getQuestions()) {
            QuestionnaireQuestion question = new QuestionnaireQuestion();
            question.setUuid(UUID.randomUUID().toString());
            question.setQuestionnaire(q);
            question.setQuestionText(qr.getQuestionText());
            question.setQuestionType(QuestionnaireQuestion.QuestionType.valueOf(qr.getQuestionType()));
            question.setSortOrder(qr.getSortOrder());
            question.setConfigJson(qr.getConfigJson());
            question.setCreatedAt(LocalDateTime.now());
            question.persist();
        }

        log.infof("Questionnaire created: uuid=%s, title=%s, questions=%d",
                q.getUuid(), q.getTitle(), request.getQuestions().size());

        return withDerivedTargetPractices(Questionnaire.findByUuid(q.getUuid()));
    }

    public List<Questionnaire> getActiveReminders(String userUuid) {
        LocalDate today = LocalDate.now();

        // The user's practice identity — the registry uuid is the matching key
        // (Phase 3 §4.5; code-array fallback removed in Phase 5A with the
        // legacy column).
        User user = User.findById(userUuid);
        String userPracticeUuid = user != null ? user.getPracticeUuid() : null;

        // Get user's current team UUIDs
        List<TeamRole> teamRoles = teamRoleService.listAll(userUuid);
        List<String> userTeamUuids = teamRoles.stream()
                .filter(tr -> (tr.getEnddate() == null || !tr.getEnddate().isBefore(today))
                        && (tr.getStartdate() == null || !tr.getStartdate().isAfter(today)))
                .map(TeamRole::getTeamuuid)
                .distinct()
                .toList();

        // Get user's existing submission questionnaire UUIDs
        List<String> submittedQuestionnaireUuids = QuestionnaireSubmission
                .find("userUuid", userUuid)
                .stream()
                .map(s -> ((QuestionnaireSubmission) s).getQuestionnaireUuid())
                .distinct()
                .toList();

        ObjectMapper mapper = new ObjectMapper();

        return Questionnaire.findAllActive().stream()
                .filter(q -> q.isOpen() && q.isReminderEnabled())
                .filter(q -> !submittedQuestionnaireUuids.contains(q.getUuid()))
                .filter(q -> {
                    // Phase 3 reader switch (§4.3): target_practice_uuids is the
                    // matching key — since Phase 5A the ONLY key (the code array
                    // is unmapped; its column is dropped by V428). NULL/blank =
                    // targets everyone; a '[]' literal matches nobody (preserved
                    // asymmetry). NULL-practice users match only untargeted
                    // questionnaires, by design.
                    String uuidArray = q.getTargetPracticeUuids();
                    if (uuidArray == null || uuidArray.isBlank()) {
                        return true;
                    }
                    try {
                        if (userPracticeUuid == null) return false;
                        List<String> practiceUuids = mapper.readValue(uuidArray,
                                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        return practiceUuids.contains(userPracticeUuid);
                    } catch (Exception e) {
                        log.warnf("Invalid target_practice_uuids JSON for questionnaire %s", q.getUuid());
                        return false;
                    }
                })
                .filter(q -> {
                    if (q.getTargetTeams() == null || q.getTargetTeams().isBlank()) {
                        return true;
                    }
                    try {
                        List<String> targetTeams = mapper.readValue(q.getTargetTeams(),
                                mapper.getTypeFactory().constructCollectionType(List.class, String.class));
                        return userTeamUuids.stream().anyMatch(targetTeams::contains);
                    } catch (Exception e) {
                        log.warnf("Invalid target_teams JSON for questionnaire %s", q.getUuid());
                        return false;
                    }
                })
                .map(this::withDerivedTargetPractices)
                .toList();
    }
}
