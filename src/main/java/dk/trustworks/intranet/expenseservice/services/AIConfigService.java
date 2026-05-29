package dk.trustworks.intranet.expenseservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.model.AIConfigHistory;
import dk.trustworks.intranet.expenseservice.model.AIPromptTemplate;
import dk.trustworks.intranet.expenseservice.model.AIRuleCatalog;
import dk.trustworks.intranet.expenseservice.model.AIValidationParameter;
import io.vertx.mutiny.core.eventbus.EventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class AIConfigService {

    @Inject ObjectMapper mapper;
    @Inject EventBus bus;
    @Inject AIConfigSnapshot snapshot;

    @Transactional
    public AIRuleCatalog createRule(AIRuleCatalog r, String actor) {
        r.uuid = UUID.randomUUID().toString();
        r.updatedAt = LocalDateTime.now();
        r.updatedBy = actor;
        r.persist();
        appendHistory("RULE", r.ruleId, "CREATED", snapshotOf(r), actor);
        publishRefresh();
        return r;
    }

    @Transactional
    public AIRuleCatalog updateRule(AIRuleCatalog incoming, String actor) {
        AIRuleCatalog existing = AIRuleCatalog.<AIRuleCatalog>find("ruleId", incoming.ruleId).firstResult();
        if (existing == null) throw new NotFoundException();
        String prior = snapshotOf(existing);
        existing.displayName    = incoming.displayName;
        existing.description    = incoming.description;
        existing.severity       = incoming.severity;
        existing.resolutionType = incoming.resolutionType;
        existing.priority       = incoming.priority;
        existing.active         = incoming.active;
        existing.outcomeMode = incoming.outcomeMode;
        existing.confidenceThreshold = incoming.confidenceThreshold;
        existing.updatedAt = LocalDateTime.now();
        existing.updatedBy = actor;
        appendHistory("RULE", existing.ruleId,
            existing.active ? "UPDATED" : "DEACTIVATED", prior, actor);
        publishRefresh();
        return existing;
    }

    @Transactional
    public AIValidationParameter updateParameter(AIValidationParameter incoming, String actor) {
        AIValidationParameter existing = AIValidationParameter.findById(incoming.parameterKey);
        if (existing == null) throw new NotFoundException();
        String prior = snapshotOf(existing);
        existing.parameterValue = incoming.parameterValue;
        existing.updatedAt = LocalDateTime.now();
        existing.updatedBy = actor;
        appendHistory("PARAMETER", existing.parameterKey, "UPDATED", prior, actor);
        publishRefresh();
        return existing;
    }

    @Transactional
    public AIPromptTemplate updatePrompt(AIPromptTemplate incoming, String actor) {
        AIPromptTemplate existing = AIPromptTemplate.findById(incoming.templateKey);
        if (existing == null) throw new NotFoundException();
        String prior = snapshotOf(existing);
        existing.body = incoming.body;
        existing.currentVersion = existing.currentVersion + 1;
        existing.updatedAt = LocalDateTime.now();
        existing.updatedBy = actor;
        appendHistory("PROMPT", existing.templateKey, "UPDATED", prior, actor);
        publishRefresh();
        return existing;
    }

    private void appendHistory(String kind, String key, String action, String snapshotJson, String actor) {
        AIConfigHistory h = new AIConfigHistory();
        h.uuid = UUID.randomUUID().toString();
        h.entityKind = kind;
        h.entityKey = key;
        h.changeAction = action;
        h.snapshotJson = snapshotJson;
        h.changedAt = LocalDateTime.now();
        h.changedBy = actor;
        h.persist();
    }

    private String snapshotOf(Object entity) {
        try {
            return mapper.writeValueAsString(entity);
        } catch (Exception e) {
            log.warnf(e, "Failed to serialize entity for ai_config_history snapshot: type=%s",
                entity == null ? "null" : entity.getClass().getSimpleName());
            return "{\"error\":\"serialize_failed\"}";
        }
    }

    private void publishRefresh() {
        bus.publish("ai-config.refresh", "");
        snapshot.reload();
    }
}
