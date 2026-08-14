package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.enums.SchedulingOutboxAction;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;

/**
 * DELETE_EVIDENCE_IMAGE (plan §13.3, D10): remove one evidence image
 * from S3 once its row left PENDING, stamp {@code s3_deleted_at} and
 * record the {@code AVAILABILITY_IMAGE_DELETED} audit event. The outbox
 * owns retries; the bucket's 7-day lifecycle rule catches the failure
 * tail. S3 deletes are idempotent, so a replay after a crash between
 * delete and bookkeeping converges.
 */
@JBossLog
@ApplicationScoped
public class SchedulingEvidenceImageDeleteExecutor implements SchedulingOutboxExecutor {

    @Inject
    SchedulingEvidenceStorageService storageService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public SchedulingOutboxAction action() {
        return SchedulingOutboxAction.DELETE_EVIDENCE_IMAGE;
    }

    @Override
    public void execute(RecruitmentSchedulingOutbox row) throws Exception {
        JsonNode payload = objectMapper.readTree(
                row.getPayloadJson() == null ? "{}" : row.getPayloadJson());
        String evidenceUuid = payload.path("evidenceUuid").asText(null);
        if (evidenceUuid == null) {
            return;
        }
        boolean due = QuarkusTransaction.requiringNew().call(() -> {
            RecruitmentAvailabilityEvidence evidence =
                    RecruitmentAvailabilityEvidence.findById(evidenceUuid);
            return evidence != null && evidence.getS3DeletedAt() == null;
        });
        if (!due) {
            return; // already deleted (or the row vanished) — done
        }
        storageService.delete(evidenceUuid);
        QuarkusTransaction.requiringNew().run(() -> {
            RecruitmentAvailabilityEvidence evidence =
                    RecruitmentAvailabilityEvidence.findById(evidenceUuid);
            if (evidence == null || evidence.getS3DeletedAt() != null) {
                return;
            }
            evidence.setS3DeletedAt(LocalDateTime.now());
            RecruitmentSchedulingRequest request =
                    RecruitmentSchedulingRequest.findById(evidence.getRequestUuid());
            RecruitmentApplication application = request == null ? null
                    : RecruitmentApplication.findById(request.getApplicationUuid());
            eventRecorder.record(RecruitmentEventBuilder
                    .event(RecruitmentEventType.AVAILABILITY_IMAGE_DELETED)
                    .application(request != null ? request.getApplicationUuid() : null)
                    .candidate(application != null ? application.getCandidateUuid() : null)
                    .position(application != null ? application.getPositionUuid() : null)
                    .actorScheduler()
                    .payload("request_uuid", evidence.getRequestUuid())
                    .payload("evidence_uuid", evidence.getUuid())
                    .payload("file_sha256", evidence.getFileSha256())
                    .payload("trigger_status", evidence.getConfirmationStatus().name()));
        });
    }
}
