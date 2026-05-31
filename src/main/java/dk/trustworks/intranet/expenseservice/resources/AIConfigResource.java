package dk.trustworks.intranet.expenseservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.dto.AIConfigHistoryEntryDTO;
import dk.trustworks.intranet.expenseservice.dto.AIDryRunResultDTO;
import dk.trustworks.intranet.expenseservice.dto.AIParameterDTO;
import dk.trustworks.intranet.expenseservice.dto.AIPromptTemplateDTO;
import dk.trustworks.intranet.expenseservice.dto.AIRuleDTO;
import dk.trustworks.intranet.expenseservice.dto.AIRuleVerdictDTO;
import dk.trustworks.intranet.expenseservice.events.ExpenseCreatedConsumer;
import dk.trustworks.intranet.expenseservice.model.AIConfigHistory;
import dk.trustworks.intranet.expenseservice.model.AIPromptTemplate;
import dk.trustworks.intranet.expenseservice.model.AIRuleCatalog;
import dk.trustworks.intranet.expenseservice.model.AIValidationParameter;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.services.AIConfigService;
import dk.trustworks.intranet.expenseservice.services.AIConfigSnapshot;
import dk.trustworks.intranet.expenseservice.services.ExpenseAIValidationService;
import dk.trustworks.intranet.expenseservice.services.ExpenseReviewRoutingService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;

@Path("/admin/ai-config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"admin:write"})
public class AIConfigResource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject AIConfigService svc;
    @Inject RequestHeaderHolder header;
    @Inject ExpenseCreatedConsumer consumer;
    @Inject AIConfigSnapshot snapshot;
    @Inject ExpenseReviewRoutingService router;
    @Inject dk.trustworks.intranet.expenseservice.services.ExpenseService expenseService;

    @GET
    @Path("/rules")
    public List<AIRuleDTO> listRules() {
        return AIRuleCatalog.<AIRuleCatalog>listAll().stream()
            .map(this::toDTO)
            .toList();
    }

    @POST
    @Path("/rules")
    public AIRuleDTO createRule(@Valid AIRuleDTO dto) {
        AIRuleCatalog r = new AIRuleCatalog();
        r.ruleId = dto.ruleId();
        copyFromDTO(r, dto);
        return toDTO(svc.createRule(r, header.getUserUuid()));
    }

    @PUT
    @Path("/rules/{ruleId}")
    public AIRuleDTO updateRule(@PathParam("ruleId") String ruleId, @Valid AIRuleDTO dto) {
        AIRuleCatalog r = new AIRuleCatalog();
        r.ruleId = ruleId;
        copyFromDTO(r, dto);
        return toDTO(svc.updateRule(r, header.getUserUuid()));
    }

    @GET
    @Path("/parameters")
    public List<AIParameterDTO> listParameters() {
        return AIValidationParameter.<AIValidationParameter>listAll().stream()
            .map(p -> new AIParameterDTO(p.parameterKey, p.parameterValue, p.valueType, p.description,
                p.updatedAt.atOffset(ZoneOffset.UTC), p.updatedBy))
            .toList();
    }

    public record ParameterUpdateBody(@NotBlank String value) {}

    @PUT
    @Path("/parameters/{key}")
    public AIParameterDTO updateParameter(@PathParam("key") String key, @Valid ParameterUpdateBody body) {
        AIValidationParameter p = new AIValidationParameter();
        p.parameterKey = key;
        p.parameterValue = body.value();
        AIValidationParameter saved = svc.updateParameter(p, header.getUserUuid());
        return new AIParameterDTO(saved.parameterKey, saved.parameterValue, saved.valueType,
            saved.description, saved.updatedAt.atOffset(ZoneOffset.UTC), saved.updatedBy);
    }

    @GET
    @Path("/prompts/{templateKey}")
    public AIPromptTemplateDTO getPrompt(@PathParam("templateKey") String key) {
        AIPromptTemplate t = AIPromptTemplate.findById(key);
        if (t == null) throw new NotFoundException();
        return new AIPromptTemplateDTO(t.templateKey, t.body, t.currentVersion,
            t.updatedAt.atOffset(ZoneOffset.UTC), t.updatedBy);
    }

    public record PromptUpdateBody(@NotBlank String body) {}

    @PUT
    @Path("/prompts/{templateKey}")
    public AIPromptTemplateDTO updatePrompt(@PathParam("templateKey") String key, @Valid PromptUpdateBody b) {
        AIPromptTemplate t = new AIPromptTemplate();
        t.templateKey = key;
        t.body = b.body();
        AIPromptTemplate saved = svc.updatePrompt(t, header.getUserUuid());
        return new AIPromptTemplateDTO(saved.templateKey, saved.body, saved.currentVersion,
            saved.updatedAt.atOffset(ZoneOffset.UTC), saved.updatedBy);
    }

    @GET
    @Path("/history")
    public List<AIConfigHistoryEntryDTO> history(
            @QueryParam("kind") String kind,
            @QueryParam("entityKey") String entityKey,
            @QueryParam("from") String from,
            @QueryParam("to") String to) {
        StringBuilder query = new StringBuilder("1=1");
        HashMap<String, Object> params = new HashMap<>();
        if (kind != null) {
            query.append(" and entityKind = :k");
            params.put("k", kind);
        }
        if (entityKey != null) {
            query.append(" and entityKey = :e");
            params.put("e", entityKey);
        }
        if (from != null) {
            query.append(" and changedAt >= :from");
            params.put("from", java.time.LocalDate.parse(from).atStartOfDay());
        }
        if (to != null) {
            query.append(" and changedAt < :toExclusive");
            params.put("toExclusive", java.time.LocalDate.parse(to).plusDays(1).atStartOfDay());
        }
        List<AIConfigHistory> rows = AIConfigHistory.<AIConfigHistory>find(
            query + " order by changedAt desc", params).list();
        return rows.stream().map(h -> {
            JsonNode snap = null;
            try { snap = MAPPER.readTree(h.snapshotJson); } catch (Exception ignored) {}
            return new AIConfigHistoryEntryDTO(h.uuid, h.entityKind, h.entityKey,
                h.changeAction, snap, h.changedAt.atOffset(ZoneOffset.UTC), h.changedBy);
        }).toList();
    }

    private void copyFromDTO(AIRuleCatalog r, AIRuleDTO d) {
        r.displayName = d.displayName();
        r.description = d.description();
        r.severity = d.severity();
        r.resolutionType = d.resolutionType();
        r.priority = d.priority();
        r.active = d.active();
        r.outcomeMode = d.outcomeMode() != null ? d.outcomeMode() : "BLOCK";
        r.confidenceThreshold = d.confidenceThreshold() != null ? d.confidenceThreshold() : 0.0;
    }

    private AIRuleDTO toDTO(AIRuleCatalog r) {
        return new AIRuleDTO(r.ruleId, r.displayName, r.description, r.severity,
            r.resolutionType, r.priority, r.active,
            r.outcomeMode != null ? r.outcomeMode : "BLOCK",
            r.confidenceThreshold != null ? r.confidenceThreshold : 0.0,
            r.updatedAt.atOffset(ZoneOffset.UTC), r.updatedBy);
    }

    public record DryRunBody(@jakarta.validation.constraints.NotBlank String expenseUuid) {}

    @POST
    @Path("/dry-run")
    public AIDryRunResultDTO dryRun(@Valid DryRunBody body) {
        Expense e = Expense.findById(body.expenseUuid());
        if (e == null) throw new NotFoundException("expense not found");

        ExpenseAIValidationService.AIResult result = consumer.validateExpense(e);
        java.util.List<String> firedRuleIds = result.ruleIds() != null
            ? result.ruleIds()
            : java.util.List.of();

        java.util.List<AIRuleVerdictDTO> verdicts = snapshot.getRulesByPriority().stream()
            .map(r -> new AIRuleVerdictDTO(
                r.ruleId(), r.severity(), r.resolutionType(),
                firedRuleIds.contains(r.ruleId()),
                "" // explanation: kept empty in v1
            ))
            .toList();

        String routing;
        if (result.approved()) {
            routing = "APPROVED";
        } else {
            var decision = router.route(firedRuleIds, e.getAiValidationCount());
            routing = decision.kind();
        }

        // extractedReceiptText is intentionally null in v1 — we'd need a separate call to extract;
        // v1 ships verdicts + routing only. Wire-through is a Phase 5 follow-up.
        return new AIDryRunResultDTO(null, verdicts, result.approved(), routing);
    }

    /**
     * Force-revalidate a previously-decided expense under the current rule
     * catalog / prompt. Clears AI review fields, writes an
     * {@code ADMIN_FORCE_REVALIDATE} entry to {@code expense_decision_log},
     * and publishes the {@code expense.validate} event so the consumer runs
     * the full vision + policy pipeline immediately. Returns 204 on success.
     */
    @POST
    @Path("/revalidate/{expenseUuid}")
    public jakarta.ws.rs.core.Response revalidateExpense(@PathParam("expenseUuid") String expenseUuid) {
        expenseService.adminForceRevalidate(expenseUuid, header.getUserUuid());
        return jakarta.ws.rs.core.Response.noContent().build();
    }
}
