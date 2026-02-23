package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.sales.model.SalesLead;
import dk.trustworks.intranet.sales.model.dto.ConsultantRecommendation;
import dk.trustworks.intranet.sales.model.dto.ConsultantRecommendationRequest;
import dk.trustworks.intranet.sales.model.dto.DescriptionSuggestionRequest;
import dk.trustworks.intranet.sales.services.SalesService;
import dk.trustworks.intranet.sales.usecases.ConsultantRecommendationUseCase;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.List;

@JBossLog
@Tag(name = "Sales")
@Path("/sales/leads")
@RequestScoped
@RolesAllowed({"SYSTEM"})
@SecurityRequirement(name = "jwt")
public class SalesResource {

    @Inject
    SalesService salesService;

    @Inject
    OpenAIService openAIService;

    @Inject
    ConsultantRecommendationUseCase consultantRecommendationUseCase;

    @GET
    @Transactional
    public List<SalesLead> findAll(
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("sort") List<String> sort,
            @QueryParam("filter") String filter,
            @QueryParam("status") String status) {
        List<SalesLead> salesLeads = salesService.findAll(offset, limit, sort, filter, status);
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
    }

    @GET
    @Path("/count")
    @Transactional
    public Long count(
            @QueryParam("filter") String filter,
            @QueryParam("status") String status) {
        return salesService.count(filter, status);
    }

    /*
    @GET
    @Transactional
    public List<SalesLead> findAll(@QueryParam("status") String status) {
        List<SalesLead> salesLeads;
        if(status!=null && !status.isEmpty()) {
            salesLeads = salesService.findByStatus(Arrays.stream(status.split(",")).map(SalesStatus::valueOf).toArray((SalesStatus[]::new)));
        } else {
            salesLeads = salesService.findAll();
        }
        for (SalesLead salesLead : salesLeads) {
            testCloseDate(salesLead);
        }
        return salesLeads;
    }

     */

    @GET
    @Path("/{uuid}")
    @Transactional
    public SalesLead findOne(@PathParam("uuid") String uuid) {
        SalesLead salesLead = salesService.findOne(uuid);
        testCloseDate(salesLead);
        return salesLead;
    }

    @GET
    @Path("/won")
    public List<SalesLead> findWon(@QueryParam("sinceDate") String sinceDate) {
        log.infof("sinceDate = %s", sinceDate);
        return salesService.findWon(DateUtils.dateIt(sinceDate));
    }

    @POST
    @Path("/{uuid}/consultant")
    public void addConsultant(@PathParam("uuid") String salesleaduuid, User user) {
        salesService.addConsultant(salesleaduuid, user);
    }

    @DELETE
    @Path("/{uuid}/consultant/{useruuid}")
    public void addConsultant(@PathParam("uuid") String salesleaduuid, @PathParam("useruuid") String useruuid) {
        salesService.removeConsultant(salesleaduuid, useruuid);
    }

    @POST
    public SalesLead persist(SalesLead salesLead) {
        return salesService.persist(salesLead);
    }

    @PUT
    public void update(SalesLead salesLead) {
        salesService.update(salesLead);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        salesService.delete(uuid);
    }

    private static void testCloseDate(SalesLead salesLead) {
        if (salesLead.getCloseDate().isBefore(LocalDate.now().withDayOfMonth(1))) {
            salesLead.setCloseDate(LocalDate.now().withDayOfMonth(1));
            salesLead.persistAndFlush();
        }
    }

    /**
     * AI-powered consultant recommendation endpoint.
     * Returns the top 5 consultant matches for a sales lead based on skills,
     * rate, and availability. Business logic lives in ConsultantRecommendationUseCase.
     *
     * @param leadUuid UUID of the sales lead
     * @param request  Optional context hint from the account manager
     * @return 200 with ranked recommendations, 400 if lead is WON/LOST, 404 if lead not found
     */
    @POST
    @Path("/{uuid}/consultant-recommendations")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConsultantRecommendations(
            @PathParam("uuid") String leadUuid,
            ConsultantRecommendationRequest request) {

        log.infof("[getConsultantRecommendations] leadUuid=%s", leadUuid);

        try {
            String contextHint = request != null ? request.contextHint() : null;
            List<ConsultantRecommendation> recommendations =
                    consultantRecommendationUseCase.recommendForLead(leadUuid, contextHint);
            return Response.ok(recommendations).build();

        } catch (ConsultantRecommendationUseCase.LeadNotFoundException e) {
            log.warnf("[getConsultantRecommendations] Lead not found: %s", leadUuid);
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("Sales lead not found: " + leadUuid)
                    .build();

        } catch (ConsultantRecommendationUseCase.LeadNotEligibleException e) {
            log.warnf("[getConsultantRecommendations] Lead not eligible: %s status=%s", leadUuid, e.status);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Cannot recommend consultants for a lead with status: " + e.status)
                    .build();

        } catch (Exception e) {
            log.errorf(e, "[getConsultantRecommendations] Unexpected error for leadUuid=%s", leadUuid);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to generate consultant recommendations")
                    .build();
        }
    }

    /**
     * AI-assisted description suggestion endpoint.
     * Generates contextual suggestions for sales lead descriptions.
     *
     * @param request The suggestion request containing current text and context
     * @return Plain text suggestion or error response
     */
    @POST
    @Path("/suggest-description")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public Response suggestDescription(DescriptionSuggestionRequest request) {
        log.infof("[suggestDescription] Received request for description suggestion");

        // Validate minimum text length
        if (request.currentText() == null || request.currentText().length() < 20) {
            log.warnf("[suggestDescription] Text too short: %d chars",
                    request.currentText() == null ? 0 : request.currentText().length());
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("Text must be at least 20 characters")
                    .build();
        }

        try {
            // Build context-aware system prompt
            String systemPrompt = buildSystemPrompt();

            // Build user message with context
            String userMessage = buildUserMessage(request);

            log.debugf("[suggestDescription] Calling OpenAI with text length: %d", request.currentText().length());

            // Call OpenAI for suggestion (using simple text response, no schema needed)
            String suggestion = openAIService.askQuestion(
                    systemPrompt + "\n\nUser input:\n" + userMessage
            );

            // Clean up the response - it may come as JSON, extract just the text
            suggestion = cleanupSuggestion(suggestion);

            if (suggestion == null || suggestion.isBlank() || suggestion.equals("{}")) {
                log.warnf("[suggestDescription] Empty suggestion returned from OpenAI");
                return Response.status(Response.Status.NO_CONTENT).build();
            }

            log.infof("[suggestDescription] Generated suggestion: %d chars", suggestion.length());
            return Response.ok(suggestion).build();

        } catch (Exception e) {
            log.errorf(e, "[suggestDescription] Error generating suggestion");
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Failed to generate suggestion")
                    .build();
        }
    }

    /**
     * Build the system prompt for AI description suggestions.
     */
    private String buildSystemPrompt() {
        return """
                You are a sales assistant helping write detailed descriptions for sales leads at an IT consulting company.
                Generate a helpful continuation or improvement for the lead description.
                Keep suggestions professional and concise (1-2 sentences).
                Focus on value propositions, technical requirements, or timeline details.
                Do not repeat what the user has already written.
                Return ONLY the suggestion text, no JSON, no markdown, no code fences.
                """;
    }

    /**
     * Build the user message with context for better suggestions.
     */
    private String buildUserMessage(DescriptionSuggestionRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append("Current description text:\n");
        sb.append(request.currentText());
        sb.append("\n\n");

        if (request.briefDescription() != null && !request.briefDescription().isBlank()) {
            sb.append("Lead title: ").append(request.briefDescription()).append("\n");
        }
        if (request.clientName() != null && !request.clientName().isBlank()) {
            sb.append("Client: ").append(request.clientName()).append("\n");
        }
        if (request.leadManagerName() != null && !request.leadManagerName().isBlank()) {
            sb.append("Lead manager: ").append(request.leadManagerName()).append("\n");
        }

        sb.append("\nSuggest a continuation for this sales lead description:");
        return sb.toString();
    }

    /**
     * Clean up the AI response to extract just the suggestion text.
     */
    private String cleanupSuggestion(String raw) {
        if (raw == null) return null;

        String cleaned = raw.trim();

        // Remove JSON wrapper if present
        if (cleaned.startsWith("{") && cleaned.endsWith("}")) {
            // Try to extract text from common JSON patterns
            if (cleaned.contains("\"suggestion\"")) {
                int start = cleaned.indexOf("\"suggestion\"");
                int colonIndex = cleaned.indexOf(":", start);
                if (colonIndex > 0) {
                    int valueStart = cleaned.indexOf("\"", colonIndex + 1);
                    int valueEnd = cleaned.lastIndexOf("\"");
                    if (valueStart > 0 && valueEnd > valueStart) {
                        cleaned = cleaned.substring(valueStart + 1, valueEnd);
                    }
                }
            } else if (cleaned.contains("\"text\"")) {
                int start = cleaned.indexOf("\"text\"");
                int colonIndex = cleaned.indexOf(":", start);
                if (colonIndex > 0) {
                    int valueStart = cleaned.indexOf("\"", colonIndex + 1);
                    int valueEnd = cleaned.lastIndexOf("\"");
                    if (valueStart > 0 && valueEnd > valueStart) {
                        cleaned = cleaned.substring(valueStart + 1, valueEnd);
                    }
                }
            }
        }

        // Remove markdown code fences if present
        if (cleaned.startsWith("```")) {
            int endFence = cleaned.lastIndexOf("```");
            if (endFence > 3) {
                // Find the end of the first line (after ```json or similar)
                int firstNewline = cleaned.indexOf("\n");
                if (firstNewline > 0 && firstNewline < endFence) {
                    cleaned = cleaned.substring(firstNewline + 1, endFence).trim();
                }
            }
        }

        // Unescape common JSON escapes
        cleaned = cleaned.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");

        return cleaned;
    }
}