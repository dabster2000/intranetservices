package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.airtable.AirtableImportRecord;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactorDeadLetter;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentReactorDelivery;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossier;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierAppendix;
import dk.trustworks.intranet.recruitmentservice.model.CandidateDossierRevision;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadSubmission;
import dk.trustworks.intranet.recruitmentservice.model.OnboardingUploadToken;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplicationAnswer;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityConstraint;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentAvailabilityEvidence;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCalendarHold;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidateDeletion;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentConsent;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentDiscussionThread;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentInterview;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentOptionBatch;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPendingEmail;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentProposedSlot;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentRecordCheck;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentReferral;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingOutbox;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSchedulingRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentScorecard;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentSlotApproval;
import dk.trustworks.intranet.recruitmentservice.notifications.RecruitmentSlackThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The row-deletion half of the ADMIN candidate hard delete (change C3/C4):
 * every child of one candidate, then the candidate, then the ledger row —
 * in ONE transaction.
 *
 * <h3>Why the order is the order</h3>
 * Every foreign key into {@code recruitment_candidates} is
 * {@code ON DELETE RESTRICT} except {@code recruitment_discussion_threads}
 * (V482, CASCADE), so a bare {@code DELETE FROM recruitment_candidates} fails
 * today on seven tables. Children go before parents, all the way down the
 * transitive chains (scorecard → interview → application; hold/approval →
 * slot → scheduling request; appendix/revision → dossier). Verified against
 * the migrations, not against a summary: the eight direct FKs are V312/V315
 * (dossiers), V436 (applications, consents), V437 (answers), V438
 * (referrals), V446 (pending emails), V481 (record checks) and V482
 * (discussion threads).
 *
 * <h3>Bulk operations only — deliberately</h3>
 * Everything here is a JPQL bulk {@code delete}/{@code update}, which
 * executes immediately and therefore in source order. Mutating managed
 * entities instead would defer the SQL to flush time, where Hibernate — not
 * this method — decides the order, and an UPDATE that nulls a RESTRICTing FK
 * could land after the DELETE it exists to unblock. The two legs that DO use
 * managed entities (referrals, Airtable records) are followed by an explicit
 * {@code flush()} for the same reason.
 *
 * <h3>What is NOT deleted, and why</h3>
 * <ul>
 *   <li>{@code recruitment_referrals} — the referral is another employee's
 *       record ("My referrals"). Its candidate FK is nulled and its own PII
 *       columns are scrubbed exactly as
 *       {@link RecruitmentAnonymizerService} does; the row survives.</li>
 *   <li>{@code recruitment_airtable_records} — the import audit row and the
 *       cross-run idempotency key (V483). Deleting it would make a re-import
 *       recreate the candidate we just removed. Its {@code candidate_uuid} is
 *       nulled; the row survives.</li>
 *   <li>{@code recruitment_signing_completed_cases} — its
 *       {@code candidate_uuid} is {@code NOT NULL} (V441:48), so it cannot be
 *       nulled, and deleting it would drop a NextSign idempotency claim. It
 *       needs no handling here because a candidate with such a row is
 *       refused up front with {@code SIGNED}
 *       ({@link RecruitmentCandidateHardDeleteService}).</li>
 *   <li>{@code files} rows and their S3 objects — deleted AFTER this
 *       transaction commits (a DB rollback is possible, an S3 undelete is
 *       not: {@code RecruitmentAnonymizerService}'s ordering rationale).</li>
 *   <li>{@code mail} — that table has no candidate key at all
 *       (V446 / {@code RecruitmentEmailService}); recorded as residue.</li>
 * </ul>
 *
 * <h3>The event stream</h3>
 * This is the second class ever permitted to mutate {@code recruitment_events}
 * (the first is the anonymizer), and the first permitted to DELETE from it.
 * The exemption in {@code RecruitmentEventSingleWriterArchTest} was widened
 * deliberately: the alternative — a native {@code DELETE} that the ArchUnit
 * rule does not match — would have hidden the destructive path from the rule
 * that exists to make it visible.
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCandidateDeleteCascade {

    /**
     * Every table with a real foreign key onto {@code recruitment_candidates}.
     * Pinned by {@code RecruitmentCandidateCascadeCoverageTest}, which
     * re-derives the same set from the migration files: a future migration
     * that adds a ninth FK fails the build until this cascade handles it.
     */
    public static final Set<String> DIRECT_CANDIDATE_FK_TABLES = Set.of(
            "candidate_dossiers",
            "recruitment_applications",
            "recruitment_consents",
            "recruitment_application_answers",
            "recruitment_referrals",
            "recruitment_pending_emails",
            "recruitment_record_checks",
            "recruitment_discussion_threads",
            "employee_agreements");

    /** What the cascade produced, for the response and the post-commit legs. */
    public record CascadeResult(String ledgerUuid, Map<String, Integer> deletedCounts,
                                List<Long> deletedEventSeqs) {
    }

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    /** {@code deleted_counts} is NOT NULL; an opened row has deleted nothing yet. */
    private static final String EMPTY_JSON = "{}";

    /**
     * Open the ledger row, in its own committed transaction, BEFORE anything
     * irreversible happens.
     *
     * <p>This is the ordering the hard delete's safety rests on. Microsoft
     * Graph cancellations and Slack rewrites cannot be undone and they have to
     * run before the cascade (they read rows it deletes, and remote I/O may
     * not happen inside a recruitment transaction — the 2026-08-11 reactor
     * deadlock rule). Committing this row first means those actions are
     * recorded even when the cascade later rolls back; if this insert fails,
     * the caller aborts and no external call is ever made.</p>
     *
     * @return the ledger row uuid, threaded through every later step
     */
    @Transactional
    public String openLedger(String candidateUuid, String actorUuid, String reason) {
        RecruitmentCandidateDeletion row = new RecruitmentCandidateDeletion();
        row.setUuid(UUID.randomUUID().toString());
        row.setCandidateUuid(candidateUuid);
        row.setActorUuid(actorUuid);
        row.setDeletedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setReason(reason);
        row.setOutcome(RecruitmentCandidateDeletion.OUTCOME_ATTEMPTED);
        row.setDeletedCounts(EMPTY_JSON);
        row.persist();
        return row.getUuid();
    }

    /**
     * Record what the external legs actually did — and did not do — and commit
     * it, still before the destructive transaction opens. After this returns,
     * a rollback can no longer hide the fact that invitations were cancelled
     * and Slack cards rewritten.
     */
    @Transactional
    public void recordExternalRedaction(String ledgerUuid, Map<String, Object> externallyRedacted,
                                        Map<String, Object> residue) {
        RecruitmentCandidateDeletion row = requireLedger(ledgerUuid);
        row.setOutcome(RecruitmentCandidateDeletion.OUTCOME_EXTERNAL_REDACTED);
        row.setExternalRedaction(externallyRedacted == null || externallyRedacted.isEmpty()
                ? null : toJson(externallyRedacted));
        row.setResidue(residue == null || residue.isEmpty() ? null : toJson(residue));
        row.persist();
    }

    /**
     * The cascade threw. The candidate still exists; the external redaction
     * already happened. Mark the row so it is findable, keeping the
     * {@code external_redaction} block written before the attempt.
     *
     * <p>Only the exception's CLASS NAME is stored — a message can quote a
     * column value and this table may hold no candidate identifier.</p>
     */
    @Transactional
    public void markRolledBack(String ledgerUuid, String failureClassName) {
        RecruitmentCandidateDeletion row = RecruitmentCandidateDeletion.findById(ledgerUuid);
        if (row == null) {
            log.errorf("Deletion ledger row %s vanished before a rollback could be recorded — "
                    + "external redaction for that delete is now undocumented", ledgerUuid);
            return;
        }
        row.setOutcome(RecruitmentCandidateDeletion.OUTCOME_ROLLED_BACK);
        Map<String, Object> residue = new LinkedHashMap<>();
        residue.put("cascadeFailure", failureClassName);
        residue.put("cascadeFailureNote", "The row deletion rolled back: the candidate STILL "
                + "EXISTS. Everything under external_redaction was already done out in "
                + "Microsoft Graph and Slack and cannot be undone — the Outlook invitations "
                + "are cancelled and the Slack cards read as redacted for a candidate who is "
                + "still in the system. Re-run the delete or repair by hand.");
        row.setResidue(mergeResidueJson(row.getResidue(), residue));
        row.persist();
    }

    /**
     * Delete one candidate and everything that hangs off it, and stamp the
     * ledger row opened by {@link #openLedger}. All-or-nothing: the counts and
     * the {@code COMPLETED} outcome commit with the deletes, so a row that
     * says COMPLETED is a row whose candidate really is gone.
     *
     * @param candidateUuid the candidate to remove
     * @param ledgerUuid    the row {@link #openLedger} returned. A missing row
     *                      aborts the whole transaction: deleting a candidate
     *                      with no surviving record of the deletion is the one
     *                      thing this design exists to prevent.
     */
    @Transactional
    public CascadeResult deleteCandidate(String candidateUuid, String ledgerUuid) {
        RecruitmentCandidateDeletion ledger = requireLedger(ledgerUuid);
        Map<String, Integer> counts = new LinkedHashMap<>();

        // ---- Resolve the child key sets INSIDE the transaction -------------
        List<String> applicationUuids = RecruitmentApplication
                .<RecruitmentApplication>list("candidateUuid = ?1", candidateUuid).stream()
                .map(RecruitmentApplication::getUuid).toList();
        List<String> interviewUuids = applicationUuids.isEmpty() ? List.of()
                : RecruitmentInterview.<RecruitmentInterview>list("applicationUuid in ?1", applicationUuids)
                        .stream().map(RecruitmentInterview::getUuid).toList();
        List<String> requestUuids = applicationUuids.isEmpty() ? List.of()
                : RecruitmentSchedulingRequest
                        .<RecruitmentSchedulingRequest>list("applicationUuid in ?1", applicationUuids)
                        .stream().map(RecruitmentSchedulingRequest::getUuid).toList();
        List<String> slotUuids = requestUuids.isEmpty() ? List.of()
                : RecruitmentProposedSlot.<RecruitmentProposedSlot>list("requestUuid in ?1", requestUuids)
                        .stream().map(RecruitmentProposedSlot::getUuid).toList();
        List<String> evidenceUuids = requestUuids.isEmpty() ? List.of()
                : RecruitmentAvailabilityEvidence
                        .<RecruitmentAvailabilityEvidence>list("requestUuid in ?1", requestUuids)
                        .stream().map(RecruitmentAvailabilityEvidence::getUuid).toList();
        List<String> dossierUuids = CandidateDossier
                .<CandidateDossier>list("candidateUuid = ?1", candidateUuid).stream()
                .map(CandidateDossier::getUuid).toList();
        List<String> referralUuids = RecruitmentReferral
                .<RecruitmentReferral>list("candidateUuid = ?1", candidateUuid).stream()
                .map(RecruitmentReferral::getUuid).toList();
        List<Long> eventSeqs = RecruitmentEvent
                .<RecruitmentEvent>list("candidateUuid = ?1", candidateUuid).stream()
                .map(RecruitmentEvent::getSeq).toList();

        // ---- Legs that must survive as rows: null the link, scrub the PII ---
        // Managed-entity updates, flushed immediately so the nulled FKs are in
        // the database before anything below tries to delete their target.
        counts.put("recruitment_referrals(unlinked)", scrubAndUnlinkReferrals(candidateUuid));
        counts.put("recruitment_airtable_records(unlinked)", unlinkAirtableRecords(candidateUuid));
        em.flush();

        // ---- Interview family ----------------------------------------------
        counts.put("recruitment_scorecards", interviewUuids.isEmpty() ? 0
                : (int) RecruitmentScorecard.delete("interviewUuid in ?1", interviewUuids));
        counts.put("recruitment_interviews", applicationUuids.isEmpty() ? 0
                : (int) RecruitmentInterview.delete("applicationUuid in ?1", applicationUuids));

        // ---- Method B scheduling family (V498 / V500) -----------------------
        counts.put("recruitment_calendar_hold", slotUuids.isEmpty() ? 0
                : (int) RecruitmentCalendarHold.delete("slotUuid in ?1", slotUuids));
        counts.put("recruitment_slot_approval", slotUuids.isEmpty() ? 0
                : (int) RecruitmentSlotApproval.delete("slotUuid in ?1", slotUuids));
        counts.put("recruitment_proposed_slot", requestUuids.isEmpty() ? 0
                : (int) RecruitmentProposedSlot.delete("requestUuid in ?1", requestUuids));
        counts.put("recruitment_availability_constraint", evidenceUuids.isEmpty() ? 0
                : (int) RecruitmentAvailabilityConstraint.delete("evidenceUuid in ?1", evidenceUuids));
        counts.put("recruitment_availability_evidence", requestUuids.isEmpty() ? 0
                : (int) RecruitmentAvailabilityEvidence.delete("requestUuid in ?1", requestUuids));
        counts.put("recruitment_option_batch", requestUuids.isEmpty() ? 0
                : (int) RecruitmentOptionBatch.delete("requestUuid in ?1", requestUuids));
        counts.put("recruitment_scheduling_outbox", requestUuids.isEmpty() ? 0
                : (int) RecruitmentSchedulingOutbox.delete("requestUuid in ?1", requestUuids));
        counts.put("recruitment_scheduling_request", applicationUuids.isEmpty() ? 0
                : (int) RecruitmentSchedulingRequest.delete("applicationUuid in ?1", applicationUuids));

        // ---- Answers: BOTH legs of the V437 XOR -----------------------------
        int answers = (int) RecruitmentApplicationAnswer.delete("candidateUuid = ?1", candidateUuid);
        if (!applicationUuids.isEmpty()) {
            answers += (int) RecruitmentApplicationAnswer
                    .delete("applicationUuid in ?1", applicationUuids);
        }
        counts.put("recruitment_application_answers", answers);

        // ---- Slack projection row (soft FK, V451) ---------------------------
        counts.put("recruitment_slack_threads", applicationUuids.isEmpty() ? 0
                : (int) RecruitmentSlackThread.delete("applicationUuid in ?1", applicationUuids));

        // ---- Applications ---------------------------------------------------
        counts.put("recruitment_applications",
                (int) RecruitmentApplication.delete("candidateUuid = ?1", candidateUuid));

        // ---- Dossier family --------------------------------------------------
        counts.put("candidate_dossier_appendices", dossierUuids.isEmpty() ? 0
                : (int) CandidateDossierAppendix.delete("dossierUuid in ?1", dossierUuids));
        counts.put("candidate_dossier_revisions", dossierUuids.isEmpty() ? 0
                : (int) CandidateDossierRevision.delete("dossierUuid in ?1", dossierUuids));
        counts.put("candidate_dossiers",
                (int) CandidateDossier.delete("candidateUuid = ?1", candidateUuid));

        // ---- Remaining direct children ----------------------------------------
        counts.put("recruitment_consents",
                (int) RecruitmentConsent.delete("candidateUuid = ?1", candidateUuid));
        counts.put("recruitment_pending_emails",
                (int) RecruitmentPendingEmail.delete("candidateUuid = ?1", candidateUuid));
        counts.put("recruitment_record_checks",
                (int) RecruitmentRecordCheck.delete("candidateUuid = ?1", candidateUuid));
        // Would CASCADE on its own (V482 — the module's only one). Deleted
        // explicitly anyway so the ledger can report a count and the cascade
        // does not silently depend on a schema detail.
        counts.put("recruitment_discussion_threads",
                (int) RecruitmentDiscussionThread.delete("candidateUuid = ?1", candidateUuid));
        // Would CASCADE on its own too (V547). In practice always 0 here:
        // recorder-written rows imply a completed signing case, which the
        // hard delete refuses up front with SIGNED, and rows re-keyed at
        // HIRED conversion no longer reference the candidate. Deleted
        // explicitly for the same ledger/count reasons as above.
        counts.put("employee_agreements",
                (int) dk.trustworks.intranet.agreementservice.model.EmployeeAgreement
                        .delete("candidateUuid = ?1", candidateUuid));

        // ---- Onboarding upload family (soft FKs, V322/V327/V561) ---------------
        // Order matters: onboarding_upload_attempts is keyed by token_uuid, not
        // candidate_uuid, so its rows have to be found through the tokens while
        // those still exist. Deleting the tokens first would strand them.
        List<String> onboardingTokenUuids = OnboardingUploadToken
                .<OnboardingUploadToken>list("candidateUuid = ?1", candidateUuid)
                .stream().map(OnboardingUploadToken::getUuid).toList();
        counts.put("onboarding_upload_attempts", onboardingTokenUuids.isEmpty() ? 0
                : em.createNativeQuery(
                        "DELETE FROM onboarding_upload_attempts WHERE token_uuid IN (:tokens)")
                .setParameter("tokens", onboardingTokenUuids)
                .executeUpdate());
        counts.put("onboarding_upload_submissions",
                (int) OnboardingUploadSubmission.delete("candidateUuid = ?1", candidateUuid));
        counts.put("onboarding_upload_tokens",
                (int) OnboardingUploadToken.delete("candidateUuid = ?1", candidateUuid));

        // ---- The event stream and its reactor bookkeeping ----------------------
        counts.put("recruitment_events(referral-leg pii scrubbed)",
                rewriteReferralEventPii(referralUuids));
        counts.put("recruitment_events",
                (int) RecruitmentEvent.delete("candidateUuid = ?1", candidateUuid));
        // Soft cleanup, NOT an FK requirement — there is no foreign key from
        // either table to recruitment_events (V433/V490 say so in as many
        // words). Left behind, a dead-letter row would point at an event that
        // no longer resolves and its replay would fail forever.
        counts.put("recruitment_reactor_deliveries", eventSeqs.isEmpty() ? 0
                : (int) RecruitmentReactorDelivery.delete("eventSeq in ?1", eventSeqs));
        counts.put("recruitment_reactor_dead_letters", eventSeqs.isEmpty() ? 0
                : (int) RecruitmentReactorDeadLetter.delete("eventSeq in ?1", eventSeqs));

        // ---- The candidate ------------------------------------------------------
        counts.put("recruitment_candidates",
                (int) RecruitmentCandidate.delete("uuid = ?1", candidateUuid));

        // ---- The ledger: the only surviving trace --------------------------------
        // Stamped, not inserted: the row was committed before the external
        // redaction ran. COMPLETED commits with the deletes above.
        ledger.setDeletedCounts(toJson(counts));
        ledger.setOutcome(RecruitmentCandidateDeletion.OUTCOME_COMPLETED);
        ledger.persist();

        log.infof("Hard-deleted candidate %s by actor %s: %s",
                candidateUuid, ledger.getActorUuid(), counts);
        return new CascadeResult(ledgerUuid, counts, eventSeqs);
    }

    /**
     * Attach the post-commit outcome (S3, reporting projection) to the ledger
     * row in its own transaction — those legs only run after the cascade has
     * committed, so their residue cannot be known when the row is written.
     * Best-effort: a failure here loses residue detail, never the delete.
     */
    @Transactional
    public void recordResidue(String ledgerUuid, Map<String, Object> residue) {
        RecruitmentCandidateDeletion row = RecruitmentCandidateDeletion.findById(ledgerUuid);
        if (row == null) {
            log.warnf("Deletion ledger row %s vanished before residue could be recorded", ledgerUuid);
            return;
        }
        row.setResidue(toJson(residue));
        row.persist();
    }

    // ------------------------------------------------------------------
    // Legs that keep the row
    // ------------------------------------------------------------------

    /**
     * The referral belongs to the referring employee, not to the candidate —
     * it is what "My referrals" renders. Null the candidate link and scrub the
     * candidate's own columns, byte-for-byte the values
     * {@code RecruitmentAnonymizerService.scrubReferrals} uses, so the two
     * paths leave the table in the same state.
     */
    private int scrubAndUnlinkReferrals(String candidateUuid) {
        List<RecruitmentReferral> referrals =
                RecruitmentReferral.list("candidateUuid = ?1", candidateUuid);
        for (RecruitmentReferral referral : referrals) {
            referral.setCandidateUuid(null);
            referral.setCandidateName("Anonymized Candidate");
            referral.setLinkedinUrl(null);
            referral.setEmail(null);
            referral.setWhyText(RecruitmentAnonymizerService.SCRUBBED_TEXT);
            referral.setExternalReferrerName(null);
        }
        return referrals.size();
    }

    /**
     * The Airtable import row is the cross-run idempotency key (V483): delete
     * it and the next import re-creates the candidate that was just removed.
     * Null the link, keep the audit row.
     */
    private int unlinkAirtableRecords(String candidateUuid) {
        List<AirtableImportRecord> records =
                AirtableImportRecord.list("candidateUuid = ?1", candidateUuid);
        for (AirtableImportRecord record : records) {
            record.setCandidateUuid(null);
        }
        return records.size();
    }

    /**
     * Referral-era events ({@code REFERRAL_SUBMITTED} and friends) predate the
     * candidate row and carry {@code candidate_uuid IS NULL}, so the delete
     * above does not reach them — but their {@code pii} block holds the name
     * and email of the person being deleted. Rewrite it, using the anonymizer's
     * exact native pattern (JPQL has no JSON extraction).
     */
    private int rewriteReferralEventPii(List<String> referralUuids) {
        int rewritten = 0;
        for (String referralUuid : referralUuids) {
            rewritten += em.createNativeQuery("""
                            UPDATE recruitment_events
                            SET pii = :piiJson, pii_state = 'ANONYMIZED'
                            WHERE candidate_uuid IS NULL
                              AND pii_state = 'PRESENT'
                              AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.referral_uuid')) = :referral
                            """)
                    .setParameter("piiJson", RecruitmentAnonymizerService.ANONYMIZED_PII_JSON)
                    .setParameter("referral", referralUuid)
                    .executeUpdate();
        }
        return rewritten;
    }

    // ------------------------------------------------------------------
    // The ledger
    // ------------------------------------------------------------------

    private static RecruitmentCandidateDeletion requireLedger(String ledgerUuid) {
        RecruitmentCandidateDeletion row = RecruitmentCandidateDeletion.findById(ledgerUuid);
        if (row == null) {
            throw new IllegalStateException("Deletion ledger row " + ledgerUuid + " is missing — "
                    + "refusing to delete a candidate with no surviving record of the deletion");
        }
        return row;
    }

    /**
     * Keep whatever the pre-cascade step recorded and add the new keys. The
     * residue written before the attempt says what the external legs could not
     * clean up; losing it here would throw away half the picture.
     */
    private String mergeResidueJson(String existingJson, Map<String, Object> additions) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                merged.putAll(objectMapper.readValue(existingJson,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
            } catch (Exception e) {
                log.warnf(e, "Could not re-read deletion ledger residue; keeping it verbatim "
                        + "under a nested key: %s", e.getMessage());
                merged.put("residueBeforeRollback", existingJson);
            }
        }
        merged.putAll(additions);
        return toJson(merged);
    }

    private String toJson(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Never fail a committed delete over its own bookkeeping format.
            log.warnf(e, "Could not serialize deletion ledger JSON: %s", e.getMessage());
            return "{\"serialization_failed\":true}";
        }
    }
}
