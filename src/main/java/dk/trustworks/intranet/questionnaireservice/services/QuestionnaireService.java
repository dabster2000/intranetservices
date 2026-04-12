package dk.trustworks.intranet.questionnaireservice.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.questionnaireservice.dto.CreateSubmissionRequest;
import dk.trustworks.intranet.questionnaireservice.dto.QuestionnaireStatsResponse;
import dk.trustworks.intranet.questionnaireservice.dto.SubmissionAnswerRequest;
import dk.trustworks.intranet.questionnaireservice.model.Questionnaire;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireAnswer;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireQuestion;
import dk.trustworks.intranet.questionnaireservice.model.QuestionnaireSubmission;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class QuestionnaireService {

    public List<Questionnaire> listAll() {
        return Questionnaire.listAll();
    }

    public Questionnaire findByUuid(String uuid) {
        Questionnaire q = Questionnaire.findByUuid(uuid);
        if (q == null) {
            throw new WebApplicationException("Questionnaire not found: " + uuid, Response.Status.NOT_FOUND);
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
}
