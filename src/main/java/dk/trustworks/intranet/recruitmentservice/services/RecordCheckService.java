package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventBuilder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventRecorder;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentRecordCheck;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecordCheckOutcome;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentHiringTrack;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventVisibility;
import dk.trustworks.intranet.services.AppSettingService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * ISAE 3000 criminal-record sampling (V481).
 * <p>
 * Every candidate FIRST entering the OFFER stage gets exactly one draw:
 * deterministic (SHA-256 of the candidate uuid mod 100 &lt; rate), at the
 * rate configured in app_settings — so the draw is idempotent, auditable
 * and immune to re-rolls. Selected candidates present their straffeattest
 * to HR, who records the OUTCOME ONLY (never the document). Draw rows
 * survive candidate anonymization pseudonymized (the FK RESTRICTs; the
 * anonymizer redacts the candidate row in place).
 */
@JBossLog
@ApplicationScoped
public class RecordCheckService {

    /** app_settings key for the sample rate (percent, 0–100). */
    public static final String RATE_SETTING_KEY = "recruitment.record-check.sample-rate";
    public static final String RATE_SETTING_CATEGORY = "recruitment";
    public static final int DEFAULT_RATE_PERCENT = 20;

    @Inject
    AppSettingService appSettingService;

    @Inject
    RecruitmentEventRecorder eventRecorder;

    // ---- Draw ------------------------------------------------------------------

    /**
     * Draw for the candidate if no draw exists yet. Called from the stage
     * move into OFFER, same transaction — the draw is part of the move.
     * Idempotent: re-entries into OFFER (and offers on other positions)
     * never redraw.
     */
    @Transactional
    public void drawOnOfferEntry(RecruitmentApplication application,
                                 RecruitmentPosition position, UUID actor) {
        String candidateUuid = application.getCandidateUuid();
        long existing = RecruitmentRecordCheck.count("candidateUuid", candidateUuid);
        if (existing > 0) {
            return;
        }

        int rate = sampleRatePercent();
        boolean selected = deterministicDraw(candidateUuid, rate);

        RecruitmentRecordCheck check = new RecruitmentRecordCheck();
        check.setCandidateUuid(candidateUuid);
        check.setApplicationUuid(application.getUuid());
        check.setDrawnAt(LocalDateTime.now(ZoneOffset.UTC));
        check.setRateApplied(rate);
        check.setSelected(selected);
        check.persist();

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.RECORD_CHECK_DRAWN)
                .candidate(candidateUuid)
                .application(application.getUuid())
                .position(application.getPositionUuid())
                .actorUser(actor.toString())
                .payload("selected", selected)
                .payload("rate_applied", rate)
                .payload("check_uuid", check.getUuid());
        if (position == null || position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            event.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        eventRecorder.record(event);

        log.infof("Record-check draw for candidate=%s: selected=%s rate=%d%%",
                candidateUuid, selected, rate);
    }

    /**
     * Deterministic selection: SHA-256 of the candidate uuid, first two
     * bytes mod 100 &lt; rate. The same candidate always draws the same
     * result for a given rate — no re-roll by re-entering OFFER, and an
     * auditor can recompute every historical draw.
     */
    static boolean deterministicDraw(String candidateUuid, int ratePercent) {
        if (ratePercent <= 0) {
            return false;
        }
        if (ratePercent >= 100) {
            return true;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(candidateUuid.getBytes(StandardCharsets.UTF_8));
            int bucket = ((digest[0] & 0xFF) << 8 | (digest[1] & 0xFF)) % 100;
            return bucket < ratePercent;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandatory in every JVM; unreachable.
            throw new IllegalStateException(e);
        }
    }

    // ---- Outcome ---------------------------------------------------------------

    /**
     * HR records what they saw. Only meaningful for selected draws;
     * recording on an unselected draw is a client error.
     */
    @Transactional
    public RecruitmentRecordCheck recordOutcome(String candidateUuid,
                                                RecordCheckOutcome outcome, UUID actor) {
        if (outcome == null || outcome == RecordCheckOutcome.PENDING) {
            throw badRequest("INVALID_OUTCOME");
        }
        RecruitmentRecordCheck check = RecruitmentRecordCheck
                .<RecruitmentRecordCheck>find("candidateUuid", candidateUuid)
                .firstResult();
        if (check == null) {
            throw new NotFoundException();
        }
        if (!check.isSelected()) {
            throw badRequest("NOT_SELECTED");
        }
        check.setOutcome(outcome);
        check.setVerifiedBy(actor.toString());
        check.setVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));

        RecruitmentEventBuilder event = RecruitmentEventBuilder
                .event(RecruitmentEventType.RECORD_CHECK_OUTCOME_RECORDED)
                .candidate(candidateUuid)
                .application(check.getApplicationUuid())
                .actorUser(actor.toString())
                .payload("outcome", outcome.name())
                .payload("check_uuid", check.getUuid());

        // Preserve the source application's partner boundary on this later
        // candidate-wide compliance event. Otherwise a named owner who reads
        // the dossier through a separate ordinary application can learn that
        // a hidden partner route exists. Broken linkage fails closed.
        RecruitmentApplication application = check.getApplicationUuid() == null
                ? null : RecruitmentApplication.findById(check.getApplicationUuid());
        RecruitmentPosition position = application == null
                ? null : RecruitmentPosition.findById(application.getPositionUuid());
        if (application != null) {
            event.position(application.getPositionUuid());
        }
        if (position == null || position.getHiringTrack() == RecruitmentHiringTrack.PARTNER) {
            event.visibility(RecruitmentEventVisibility.CIRCLE);
        }
        eventRecorder.record(event);
        return check;
    }

    /** The candidate's draw row, if any. */
    public Optional<RecruitmentRecordCheck> statusFor(String candidateUuid) {
        return Optional.ofNullable(RecruitmentRecordCheck
                .<RecruitmentRecordCheck>find("candidateUuid", candidateUuid)
                .firstResult());
    }

    // ---- Rate setting ----------------------------------------------------------

    /** The configured sample rate, clamped to 0–100; default 20. */
    public int sampleRatePercent() {
        return appSettingService.findByKey(RATE_SETTING_KEY)
                .map(AppSetting::getSettingValue)
                .map(RecordCheckService::parseRate)
                .orElse(DEFAULT_RATE_PERCENT);
    }

    /** Persist a new rate (0–100). Callers gate to HR/recruitment admins. */
    @Transactional
    public void updateSampleRate(int ratePercent, UUID actor) {
        if (ratePercent < 0 || ratePercent > 100) {
            throw badRequest("INVALID_RATE");
        }
        appSettingService.saveSetting(RATE_SETTING_KEY, String.valueOf(ratePercent),
                RATE_SETTING_CATEGORY, actor.toString());
        log.infof("Record-check sample rate set to %d%% by %s", ratePercent, actor);
    }

    private static int parseRate(String raw) {
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_RATE_PERCENT;
        }
    }

    private static WebApplicationException badRequest(String code) {
        return new WebApplicationException(Response
                .status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"" + code + "\"}")
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
