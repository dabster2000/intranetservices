package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.ai.CvrCandidateFinder;
import dk.trustworks.intranet.aggregates.crm.enrichment.ai.SectorClassifier;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.SectorEnrichmentStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The sector check for one client filed under {@code OTHER} — run asynchronously after a
 * create or edit, and nightly over the backlog.
 *
 * <p>The verdict is applied without a person (decision of 2026-09-14): a non-OTHER answer
 * changes {@code client.segment} at once, with the same activity-log entry a person's edit
 * would leave, and the row records the model's reason and confidence so the account page
 * can say why. {@code CONFIRMED_OTHER} is terminal until the client's name, CVR or
 * industry changes; {@code HUMAN_OVERRIDE} — a person put an AI-set client back to OTHER —
 * is terminal full stop, which is what keeps the job and a person from taking turns.
 */
@JBossLog
@ApplicationScoped
public class SectorEnrichmentService {

    record Snapshot(String uuid, String name, ClientSegment segment, String cvr, Integer industryCode,
                    String industryDesc, String city, SectorEnrichmentStatus status) {}

    @Inject
    SectorClassifier classifier;

    @Inject
    ClientEnrichmentRepository repository;

    @Inject
    ClientActivityLogService activityLog;

    /** Runs outside any transaction; returns the status filed, or empty when the client is gone. */
    public Optional<SectorEnrichmentStatus> verify(String clientUuid) {
        Snapshot s = QuarkusTransaction.requiringNew().call(() -> snapshot(clientUuid));
        if (s == null) return Optional.empty();
        if (s.segment() != null && s.segment() != ClientSegment.OTHER) {
            // Somebody chose a sector between the trigger and now. Nothing to check.
            if (s.status() == SectorEnrichmentStatus.PENDING) {
                return Optional.of(record(s.uuid(), SectorEnrichmentStatus.HUMAN_SET, null, false));
            }
            return Optional.of(s.status());
        }
        if (s.status() == SectorEnrichmentStatus.HUMAN_OVERRIDE) {
            return Optional.of(s.status());
        }

        Optional<SectorClassifier.Verdict> verdict = classifier.classify(
                s.name(), s.cvr(), s.industryCode(), s.industryDesc(), s.city());
        if (verdict.isEmpty()) {
            return Optional.of(record(s.uuid(), SectorEnrichmentStatus.FAILED, null, true));
        }
        SectorClassifier.Verdict v = verdict.get();
        if (v.segment() == ClientSegment.OTHER) {
            return Optional.of(record(s.uuid(), SectorEnrichmentStatus.CONFIRMED_OTHER, v, true));
        }
        QuarkusTransaction.requiringNew().run(() -> {
            Client client = Client.findById(s.uuid());
            if (client == null) return;
            if (client.getSegment() != null && client.getSegment() != ClientSegment.OTHER) {
                return; // changed under us — a person's choice stands
            }
            ClientSegment before = client.getSegment() == null ? ClientSegment.OTHER : client.getSegment();
            client.setSegment(v.segment());
            activityLog.logFieldChange(s.uuid(), ClientActivityLog.TYPE_CLIENT, s.uuid(), client.getName(),
                    "segment", before.name(), v.segment().name());
            ClientEnrichment row = repository.ensure(s.uuid());
            if (row == null) return;
            fill(row, SectorEnrichmentStatus.CHANGED, v, true);
        });
        log.infof("Sector set by AI: client=%s segment=%s confidence=%.2f", s.uuid(), v.segment(), v.confidence());
        return Optional.of(SectorEnrichmentStatus.CHANGED);
    }

    SectorEnrichmentStatus record(String clientUuid, SectorEnrichmentStatus status, SectorClassifier.Verdict verdict, boolean countAttempt) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) return;
            fill(row, status, verdict, countAttempt);
        });
        log.infof("Sector check filed: client=%s status=%s", clientUuid, status);
        return status;
    }

    private static void fill(ClientEnrichment row, SectorEnrichmentStatus status, SectorClassifier.Verdict verdict, boolean countAttempt) {
        row.setSector(status);
        row.setSectorCheckedAt(LocalDateTime.now());
        if (verdict != null) {
            row.setSectorAiSegment(verdict.segment().name());
            row.setSectorConfidence(BigDecimal.valueOf(verdict.confidence()).setScale(3, RoundingMode.HALF_UP));
            row.setSectorReason(verdict.reason() == null ? null : CvrCandidateFinder.truncate(verdict.reason(), 500));
        }
        if (countAttempt) row.setSectorAttempts(row.getSectorAttempts() + 1);
    }

    static Snapshot snapshot(String clientUuid) {
        Client c = Client.findById(clientUuid);
        if (c == null) return null;
        SectorEnrichmentStatus status = ClientEnrichment.<ClientEnrichment>findByIdOptional(clientUuid)
                .map(ClientEnrichment::sectorStatus)
                .orElse(SectorEnrichmentStatus.PENDING);
        return new Snapshot(c.getUuid(), c.getName(), c.getSegment(), c.getCvr(), c.getIndustryCode(),
                c.getIndustryDesc(), c.getBillingCity(), status);
    }
}
