package dk.trustworks.intranet.expenseservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.expenseservice.dto.AIConfigHistoryEntryDTO;
import dk.trustworks.intranet.expenseservice.dto.AIParameterDTO;
import dk.trustworks.intranet.expenseservice.dto.AIPromptTemplateDTO;
import dk.trustworks.intranet.expenseservice.dto.AIRuleDTO;
import dk.trustworks.intranet.expenseservice.model.AIConfigHistory;
import dk.trustworks.intranet.expenseservice.model.AIPromptTemplate;
import dk.trustworks.intranet.expenseservice.model.AIRuleCatalog;
import dk.trustworks.intranet.expenseservice.model.AIValidationParameter;
import dk.trustworks.intranet.expenseservice.services.AIConfigService;
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
    }

    private AIRuleDTO toDTO(AIRuleCatalog r) {
        return new AIRuleDTO(r.ruleId, r.displayName, r.description, r.severity,
            r.resolutionType, r.priority, r.active,
            r.updatedAt.atOffset(ZoneOffset.UTC), r.updatedBy);
    }
}
