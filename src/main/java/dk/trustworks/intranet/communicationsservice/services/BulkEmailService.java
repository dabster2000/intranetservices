package dk.trustworks.intranet.communicationsservice.services;

import dk.trustworks.intranet.communicationsservice.model.*;
import jakarta.enterprise.context.ApplicationScoped;
import dk.trustworks.intranet.aggregates.conference.dto.UnsubscribeFooter;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceRecipientResolver.ResolvedRecipient;
import jakarta.transaction.Transactional;
import jakarta.inject.Inject;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceMailDispatch;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * Service for creating and managing bulk email jobs.
 * Bulk emails are processed asynchronously by the 'bulk-mail-send' JBeret batch job.
 */
@JBossLog
@ApplicationScoped
public class BulkEmailService {

    @Inject ConferenceMailDispatch conferenceDispatch;

    /**
     * Create a new bulk email job with recipients and attachments.
     * The job will be processed asynchronously by the batch scheduler.
     *
     * @param request Bulk email request with subject, body, recipients, and optional attachments
     * @return The created bulk email job with UUID for tracking
     */
    @Transactional
    public BulkEmailJob createBulkEmailJob(BulkEmailRequest request) {
        log.info("Creating bulk email job: subject='" + request.getSubject() +
                 "', recipients=" + request.getRecipients().size() +
                 ", attachments=" + (request.hasAttachments() ? request.getAttachments().size() : 0));

        // Create the job
        String jobUuid = UUID.randomUUID().toString();
        BulkEmailJob job = new BulkEmailJob(jobUuid, request.getSubject(), request.getBody());
        job.setMailOrigin("SYSTEM");
        job.setTotalRecipients(request.getRecipients().size());
        job.persist();

        // Create recipient records
        for (String email : request.getRecipients()) {
            BulkEmailRecipient recipient = new BulkEmailRecipient(jobUuid, email.trim());
            recipient.persist();
        }

        // Create attachment records (if any)
        if (request.hasAttachments()) {
            for (EmailAttachment attachment : request.getAttachments()) {
                BulkEmailAttachment bulkAttachment = new BulkEmailAttachment(
                    jobUuid,
                    attachment.getFilename(),
                    attachment.getContentType(),
                    attachment.getContent()
                );
                bulkAttachment.persist();
            }
        }

        log.info("Bulk email job created: uuid=" + jobUuid +
                 ", recipients=" + request.getRecipients().size());

        return job;
    }

    @Transactional
    public BulkEmailJob createConferenceJob(String conferenceUuid, List<ResolvedRecipient> recipients,
            String subject, String body, UnsubscribeFooter footer, List<EmailAttachment> attachments) {
        if (conferenceUuid == null || recipients.isEmpty()) throw new IllegalArgumentException("Conference context required");
        BulkEmailJob job = new BulkEmailJob(UUID.randomUUID().toString(), subject, body);
        job.setConferenceUuid(conferenceUuid);
        job.setMailOrigin("CONFERENCE");
        job.setUnsubscribeFooter(footer);
        job.setStatus(BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING);
        job.setTotalRecipients(recipients.size());
        job.persist();
        for (ResolvedRecipient target : recipients) {
            BulkEmailRecipient recipient = new BulkEmailRecipient(job.getUuid(), target.email());
            recipient.setConferenceUuid(conferenceUuid);
            recipient.setParticipantUuid(target.participantUuid());
            recipient.setRecipientName(target.name());
            recipient.setNormalizedEmail(target.normalizedEmail());
            recipient.persist();
        }
        if (attachments != null) for (EmailAttachment attachment : attachments) {
            new BulkEmailAttachment(job.getUuid(), attachment.getFilename(), attachment.getContentType(),
                    attachment.getContent()).persist();
        }
        return job;
    }

    /**
     * Get a bulk email job by UUID
     */
    public BulkEmailJob findByUuid(String uuid) {
        return BulkEmailJob.findById(uuid);
    }

    /**
     * Get all pending bulk email jobs
     */
    public List<BulkEmailJob> findPendingJobs() {
        return BulkEmailJob.list("status in ?1 ORDER BY createdAt", conferenceDispatch.isEnabled()
                ? List.of(BulkEmailJob.BulkEmailJobStatus.PENDING, BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING)
                : List.of(BulkEmailJob.BulkEmailJobStatus.PENDING));
    }

    /** Only call behind the scheduler's no-running-bulk-execution guard. Terminal recipients stay terminal. */
    @Transactional
    public void recoverInterruptedConferenceJobs() {
        BulkEmailJob.update("status = ?1 where status = ?2 and mailOrigin = ?3",
                BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING, BulkEmailJob.BulkEmailJobStatus.POLICY_PROCESSING, "CONFERENCE");
    }

    /**
     * Get pending recipients for a job
     */
    public List<BulkEmailRecipient> findPendingRecipients(String jobUuid) {
        return BulkEmailRecipient.list("jobUuid = ?1 AND status = ?2",
                                       jobUuid,
                                       BulkEmailRecipient.RecipientStatus.PENDING);
    }

    /**
     * Get attachments for a job
     */
    public List<BulkEmailAttachment> findAttachments(String jobUuid) {
        return BulkEmailAttachment.list("jobUuid", jobUuid);
    }

    /**
     * Update job status
     */
    @Transactional
    public void updateJobStatus(String jobUuid, BulkEmailJob.BulkEmailJobStatus status) {
        BulkEmailJob job = BulkEmailJob.findById(jobUuid);
        if (job != null) {
            job.setStatus(status);
            if ((status == BulkEmailJob.BulkEmailJobStatus.PROCESSING || status == BulkEmailJob.BulkEmailJobStatus.POLICY_PROCESSING) && job.getStartedAt() == null) {
                job.setStartedAt(java.time.LocalDateTime.now());
            } else if ((status == BulkEmailJob.BulkEmailJobStatus.COMPLETED ||
                       status == BulkEmailJob.BulkEmailJobStatus.FAILED) &&
                       job.getCompletedAt() == null) {
                job.setCompletedAt(java.time.LocalDateTime.now());
            }
            job.persist();
        }
    }

    /**
     * Update recipient counts for a job
     */
    @Transactional
    public void updateJobCounts(String jobUuid) {
        BulkEmailJob job = BulkEmailJob.findById(jobUuid);
        if (job != null) {
            long sent = BulkEmailRecipient.count("jobUuid = ?1 AND status = ?2",
                                                  jobUuid,
                                                  BulkEmailRecipient.RecipientStatus.SENT);
            long failed = BulkEmailRecipient.count("jobUuid = ?1 AND status = ?2",
                                                    jobUuid,
                                                    BulkEmailRecipient.RecipientStatus.FAILED);
            long skipped = BulkEmailRecipient.count("jobUuid = ?1 AND status = ?2", jobUuid,
                    BulkEmailRecipient.RecipientStatus.SKIPPED);
            job.setSkippedCount((int) skipped);
            job.setSentCount((int) sent);
            job.setFailedCount((int) failed);
            job.persist();
        }
    }
}
