package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.dto.GdprCandidateStatusResponse;
import dk.trustworks.intranet.recruitmentservice.dto.GdprQueueResponse;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-side of the DPO surface (ATS P19, spec §6.1 {@code /recruitment/gdpr}):
 * the exception queue, KPI header and anonymization log, all computed at
 * read time — no projection tables, no sweep bookkeeping (the module's
 * event-derived posture, P12/P17 idiom).
 * <ul>
 *   <li><b>Art. 14 due</b> — notice-requiring candidates whose deadline is
 *       within {@code recruitment.gdpr.art14-warning-days} (or past) and
 *       who have no {@code ART14_NOTICE_SENT} event yet.</li>
 *   <li><b>Consent unanswered</b> — POOLED candidates inside the renewal
 *       window (deadline within {@code renewal-first-days}) whose deadline
 *       has not moved (a grant moves it out of the window by construction),
 *       with the renewal-send count derived from {@code EMAIL_SENT}
 *       events. Includes candidates the sweep CANNOT email (no address) —
 *       exactly the exception a human must handle.</li>
 *   <li><b>Open DSARs</b> — {@code DSAR_RECEIVED} events with no later
 *       {@code DSAR_EXPORTED} for the same candidate; deadline is the
 *       Art. 12(3) one-month window (30 days).</li>
 *   <li><b>Anonymization log</b> — the newest {@code CANDIDATE_ANONYMIZED}
 *       events with their per-target counts.</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentGdprQueueService {

    /** Art. 12(3): a DSAR must be answered within one month. */
    public static final int DSAR_RESPONSE_DAYS = 30;

    /** Anonymization log page size — the page shows the recent history. */
    static final int LOG_LIMIT = 50;

    private static final com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>> JSON_OBJECT =
            new com.fasterxml.jackson.core.type.TypeReference<>() {
            };

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentFeatureFlag featureFlag;

    @Inject
    RecruitmentGdprParameters parameters;

    public GdprQueueResponse queue() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<GdprQueueResponse.Art14Row> art14 = art14Due(now);
        List<GdprQueueResponse.ConsentRow> consent = consentUnanswered(now);
        List<GdprQueueResponse.DsarRow> dsars = openDsars(now);
        List<GdprQueueResponse.AnonymizationRow> log = anonymizationLog();
        long anonymizedTotal = RecruitmentCandidate.count("status", CandidateStatus.ANONYMIZED);
        return new GdprQueueResponse(
                featureFlag.isGdprEnabled(),
                new GdprQueueResponse.Kpis(art14.size(), consent.size(), dsars.size(),
                        anonymizedTotal),
                art14, consent, dsars, log);
    }

    /** Per-candidate action state for the profile GDPR tab. */
    public GdprCandidateStatusResponse candidateStatus(RecruitmentCandidate candidate) {
        boolean art14Sent = RecruitmentEvent.count(
                "candidateUuid = ?1 and eventType = ?2",
                candidate.getUuid(), RecruitmentEventType.ART14_NOTICE_SENT) > 0;
        return new GdprCandidateStatusResponse(
                candidate.getStatus().name(),
                candidate.getEmail() != null && !candidate.getEmail().isBlank(),
                Boolean.TRUE.equals(candidate.getArt14Required()),
                art14Sent,
                hasOpenDsar(candidate.getUuid()),
                candidate.getStatus() != CandidateStatus.HIRED
                        && candidate.getStatus() != CandidateStatus.ANONYMIZED);
    }

    /** True when a DSAR_RECEIVED has no later DSAR_EXPORTED for the candidate. */
    public boolean hasOpenDsar(String candidateUuid) {
        Long lastReceived = maxSeq(candidateUuid, RecruitmentEventType.DSAR_RECEIVED);
        if (lastReceived == null) {
            return false;
        }
        Long lastExported = maxSeq(candidateUuid, RecruitmentEventType.DSAR_EXPORTED);
        return lastExported == null || lastExported < lastReceived;
    }

    // ------------------------------------------------------------------
    // Queue sections
    // ------------------------------------------------------------------

    private List<GdprQueueResponse.Art14Row> art14Due(LocalDateTime now) {
        LocalDateTime horizon = now.plusDays(parameters.art14WarningDays());
        List<RecruitmentCandidate> candidates = RecruitmentCandidate.list(
                "art14Required = true and art14Deadline is not null and art14Deadline <= ?1 "
                        + "and status not in ?2 ORDER BY art14Deadline",
                horizon, List.of(CandidateStatus.HIRED, CandidateStatus.ANONYMIZED));
        if (candidates.isEmpty()) {
            return List.of();
        }
        Set<String> noticed = candidateUuidsWithEvent(RecruitmentEventType.ART14_NOTICE_SENT,
                candidates.stream().map(RecruitmentCandidate::getUuid).toList());
        List<GdprQueueResponse.Art14Row> rows = new ArrayList<>();
        for (RecruitmentCandidate candidate : candidates) {
            if (noticed.contains(candidate.getUuid())) {
                continue;
            }
            rows.add(new GdprQueueResponse.Art14Row(
                    candidate.getUuid(),
                    displayName(candidate),
                    candidate.getEmail() != null && !candidate.getEmail().isBlank(),
                    candidate.getSource() == null ? null : candidate.getSource().name(),
                    candidate.getCreatedAt(),
                    candidate.getArt14Deadline(),
                    ChronoUnit.DAYS.between(now, candidate.getArt14Deadline())));
        }
        return rows;
    }

    private List<GdprQueueResponse.ConsentRow> consentUnanswered(LocalDateTime now) {
        int firstDays = parameters.renewalFirstDays();
        List<RecruitmentCandidate> candidates = RecruitmentCandidate.list(
                "status = ?1 and retentionDeadline is not null and retentionDeadline > ?2 "
                        + "and retentionDeadline <= ?3 ORDER BY retentionDeadline",
                CandidateStatus.POOLED, now, now.plusDays(firstDays));
        if (candidates.isEmpty()) {
            return List.of();
        }
        // One event scan for all candidates in the window.
        Map<String, List<RecruitmentEvent>> emailEvents = new HashMap<>();
        RecruitmentEvent.<RecruitmentEvent>list(
                        "eventType = ?1 and candidateUuid in ?2",
                        RecruitmentEventType.EMAIL_SENT,
                        candidates.stream().map(RecruitmentCandidate::getUuid).toList())
                .forEach(e -> emailEvents
                        .computeIfAbsent(e.getCandidateUuid(), k -> new ArrayList<>())
                        .add(e));
        List<GdprQueueResponse.ConsentRow> rows = new ArrayList<>();
        for (RecruitmentCandidate candidate : candidates) {
            String deadlineKey = candidate.getRetentionDeadline().toString();
            List<LocalDateTime> sends = emailEvents
                    .getOrDefault(candidate.getUuid(), List.of()).stream()
                    .filter(e -> {
                        Map<String, Object> payload = parse(e.getPayload());
                        return RecruitmentGdprService.KEY_CONSENT_RENEWAL
                                .equals(payload.get("template_key"))
                                && deadlineKey.equals(payload.get("retention_deadline"));
                    })
                    .map(RecruitmentEvent::getOccurredAt)
                    .sorted()
                    .toList();
            rows.add(new GdprQueueResponse.ConsentRow(
                    candidate.getUuid(),
                    displayName(candidate),
                    candidate.getEmail() != null && !candidate.getEmail().isBlank(),
                    candidate.getRetentionDeadline(),
                    sends.size(),
                    sends.isEmpty() ? null : sends.getLast(),
                    ChronoUnit.DAYS.between(now, candidate.getRetentionDeadline())));
        }
        return rows;
    }

    private List<GdprQueueResponse.DsarRow> openDsars(LocalDateTime now) {
        List<RecruitmentEvent> dsarEvents = RecruitmentEvent.list(
                "eventType in ?1 ORDER BY seq",
                List.of(RecruitmentEventType.DSAR_RECEIVED, RecruitmentEventType.DSAR_EXPORTED));
        // Per candidate: the newest RECEIVED wins unless an EXPORTED follows it.
        Map<String, RecruitmentEvent> openByCandidate = new HashMap<>();
        for (RecruitmentEvent event : dsarEvents) {
            if (event.getCandidateUuid() == null) {
                continue;
            }
            if (event.getEventType() == RecruitmentEventType.DSAR_RECEIVED) {
                openByCandidate.put(event.getCandidateUuid(), event);
            } else {
                openByCandidate.remove(event.getCandidateUuid());
            }
        }
        if (openByCandidate.isEmpty()) {
            return List.of();
        }
        Map<String, RecruitmentCandidate> candidates = new HashMap<>();
        RecruitmentCandidate.<RecruitmentCandidate>list("uuid in ?1",
                        List.copyOf(openByCandidate.keySet()))
                .forEach(c -> candidates.put(c.getUuid(), c));
        return openByCandidate.values().stream()
                .map(event -> {
                    LocalDateTime deadline = event.getOccurredAt().plusDays(DSAR_RESPONSE_DAYS);
                    RecruitmentCandidate candidate = candidates.get(event.getCandidateUuid());
                    return new GdprQueueResponse.DsarRow(
                            event.getCandidateUuid(),
                            candidate == null ? "(unknown)" : displayName(candidate),
                            event.getOccurredAt(),
                            deadline,
                            ChronoUnit.DAYS.between(now, deadline));
                })
                .sorted(java.util.Comparator.comparing(GdprQueueResponse.DsarRow::deadline))
                .toList();
    }

    private List<GdprQueueResponse.AnonymizationRow> anonymizationLog() {
        List<RecruitmentEvent> events = RecruitmentEvent
                .<RecruitmentEvent>find("eventType = ?1 ORDER BY seq DESC",
                        RecruitmentEventType.CANDIDATE_ANONYMIZED)
                .page(0, LOG_LIMIT)
                .list();
        return events.stream()
                .map(event -> {
                    Map<String, Object> payload = parse(event.getPayload());
                    return new GdprQueueResponse.AnonymizationRow(
                            event.getCandidateUuid(),
                            event.getOccurredAt(),
                            String.valueOf(payload.getOrDefault("mode", "?")),
                            asInt(payload.get("events_rewritten")),
                            asInt(payload.get("answers_scrubbed")),
                            asInt(payload.get("documents_deleted")));
                })
                .toList();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Set<String> candidateUuidsWithEvent(RecruitmentEventType type,
                                                List<String> candidateUuids) {
        if (candidateUuids.isEmpty()) {
            return Set.of();
        }
        Set<String> uuids = new HashSet<>();
        RecruitmentEvent.<RecruitmentEvent>list(
                        "eventType = ?1 and candidateUuid in ?2", type, candidateUuids)
                .forEach(e -> uuids.add(e.getCandidateUuid()));
        return uuids;
    }

    private Long maxSeq(String candidateUuid, RecruitmentEventType type) {
        RecruitmentEvent newest = RecruitmentEvent
                .<RecruitmentEvent>find("candidateUuid = ?1 and eventType = ?2 ORDER BY seq DESC",
                        candidateUuid, type)
                .firstResult();
        return newest == null ? null : newest.getSeq();
    }

    private static String displayName(RecruitmentCandidate candidate) {
        String first = candidate.getFirstName() == null ? "" : candidate.getFirstName();
        String last = candidate.getLastName() == null ? "" : candidate.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? "(no name)" : name;
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
