package dk.trustworks.intranet.aggregates.bonus.individual.services;

import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusAuditEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Append-only protected audit trail. Metadata is canonicalized from an explicit allow-list. */
@ApplicationScoped
public class IndividualBonusAuditService {

    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "revision", "state", "direction", "reasonCode", "issueType", "status");

    @Inject IndividualBonusCanonicalizer canonicalizer;

    public void record(String eventType, String result, String actorUuid, String userUuid, String ruleUuid,
                       String adjustmentUuid, LocalDate earningMonth, LocalDate payMonth,
                       String beforeHash, String afterHash, String proofAction, String correlationId,
                       Map<String, ?> safeMetadata) {
        IndividualBonusAuditEvent event = new IndividualBonusAuditEvent();
        event.setUuid(UUID.randomUUID().toString());
        event.setOccurredAt(LocalDateTime.now(Clock.systemUTC()));
        event.setEventType(eventType);
        event.setResult(result);
        event.setActorUuid(actorUuid);
        event.setUserUuid(userUuid);
        event.setRuleUuid(ruleUuid);
        event.setAdjustmentUuid(adjustmentUuid);
        event.setEarningMonth(earningMonth);
        event.setPayMonth(payMonth);
        event.setBeforeHash(beforeHash);
        event.setAfterHash(afterHash);
        event.setProofAction(proofAction);
        event.setCorrelationId(correlationId);
        Map<String, Object> allowed = new LinkedHashMap<>();
        if (safeMetadata != null) safeMetadata.forEach((key, value) -> {
            if (SAFE_METADATA_KEYS.contains(key)) allowed.put(key, value);
        });
        event.setMetadataJson(allowed.isEmpty() ? null : canonicalizer.canonicalizeMap(allowed));
        event.persist();
    }
}
