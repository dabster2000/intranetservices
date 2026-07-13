package dk.trustworks.intranet.aggregates.bonus.individual.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.bonus.individual.config.IndividualBonusMonthlyConfig;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusAdjustmentRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusManualSettlementRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusReconciliationScanRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRunMonthlyRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusDeleteResult;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusGenerateRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusGenerateResponse;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusRuleRequest;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.ProjectedPayoutDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.dto.IndividualBonusCapabilitiesDTO;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusAdjustment;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.entity.IndividualBonusRule;
import dk.trustworks.intranet.aggregates.bonus.individual.exceptions.IndividualBonusException;
import dk.trustworks.intranet.aggregates.bonus.individual.model.FactCoverage;
import dk.trustworks.intranet.aggregates.bonus.individual.model.MonthlyCalculationResult;
import dk.trustworks.intranet.aggregates.bonus.individual.model.ProjectedPayout;
import dk.trustworks.intranet.aggregates.bonus.individual.model.StepBand;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusAdjustmentService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusMonthlyCalculationService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusMutationService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusPayoutService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusPreviewProofService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusReconciliationService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusAiService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusScheduleService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusService;
import dk.trustworks.intranet.aggregates.bonus.individual.services.IndividualBonusSpecMapper;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.security.ScopeContext;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

/**
 * REST boundary for individual bonus rules and their projected/materialised payouts. Thin — all
 * business logic lives in the injected services. Rule authoring is behind {@code bonus:write};
 * reads/projection behind {@code bonus:read}.
 */
@JBossLog
@Tag(name = "bonus")
@Path("/individual-bonuses")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"bonus:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class IndividualBonusResource {

    @Inject IndividualBonusService bonusService;
    @Inject IndividualBonusAiService aiService;
    @Inject IndividualBonusScheduleService scheduleService;
    @Inject IndividualBonusPayoutService payoutService;
    @Inject IndividualBonusMutationService mutationService;
    @Inject IndividualBonusPreviewProofService proofService;
    @Inject IndividualBonusAdjustmentService adjustmentService;
    @Inject IndividualBonusReconciliationService reconciliationService;
    @Inject IndividualBonusMonthlyCalculationService monthlyCalculationService;
    @Inject IndividualBonusSpecMapper specMapper;
    @Inject IndividualBonusMonthlyConfig monthlyConfig;
    @Inject RequestHeaderHolder requestHeaderHolder;
    @Inject ScopeContext scopeContext;
    @Inject ObjectMapper mapper;

    private static final ZoneId COPENHAGEN = ZoneId.of("Europe/Copenhagen");

    @GET
    public List<IndividualBonusRuleDTO> list(@QueryParam("userUuid") String userUuid) {
        return bonusService.listByUser(userUuid);
    }

    @POST
    @RolesAllowed({"bonus:write"})
    public Response create(@Valid IndividualBonusRuleRequest request,
                           @HeaderParam(IndividualBonusPreviewProofService.PROOF_HEADER) String proof,
                           @HeaderParam("Idempotency-Key") String idempotencyKey) {
        IndividualBonusRuleDTO created = IndividualBonusMutationService.isMonthly(request)
                ? mutationService.create(request, proof, idempotencyKey, requireHumanActor())
                : bonusService.create(request);
        return Response.created(URI.create("/individual-bonuses/" + created.uuid()))
                .entity(created)
                .build();
    }

    /**
     * Convert untrusted contract language into an UNSAVED spec proposal. The service makes one
     * no-store Structured Outputs call and applies authoritative write validation; persistence is
     * possible only through the separate create/update endpoints after human Preview.
     */
    @POST
    @Path("/generate-from-text")
    @RolesAllowed({"bonus:write"})
    public IndividualBonusGenerateResponse generateFromText(@Valid IndividualBonusGenerateRequest request) {
        return aiService.generate(request);
    }

    @PUT
    @Path("/{uuid}")
    @RolesAllowed({"bonus:write"})
    public IndividualBonusRuleDTO update(@PathParam("uuid") String uuid,
                                         @Valid IndividualBonusRuleRequest request,
                                         @HeaderParam(IndividualBonusPreviewProofService.PROOF_HEADER) String proof) {
        IndividualBonusRule persisted = IndividualBonusRule.findById(uuid);
        boolean controlled = IndividualBonusMutationService.isMonthly(request)
                || (persisted != null && mutationService.isMonthly(persisted));
        return controlled ? mutationService.update(uuid, request, proof, requireHumanActor())
                : bonusService.update(uuid, request);
    }

    /**
     * Delete a rule. GUARDED: a rule that already drove a payout is SOFT-deleted (deactivated, row kept to
     * preserve its live spec beside the immutable payout snapshot); one that never paid is HARD-deleted.
     * Returns the outcome so the caller can tell soft- from hard-delete.
     */
    @DELETE
    @Path("/{uuid}")
    @RolesAllowed({"bonus:write"})
    public IndividualBonusDeleteResult delete(@PathParam("uuid") String uuid,
                                               @QueryParam("userUuid") String userUuid) {
        if (userUuid != null && !userUuid.isBlank()) {
            IndividualBonusRule rule = IndividualBonusRule.findById(uuid);
            if (rule == null || !Objects.equals(rule.getUserUuid(), userUuid)) {
                throw new NotFoundException("Individual bonus rule not found");
            }
        }
        return bonusService.delete(uuid);
    }

    /**
     * Live dry-run: evaluate an UNSAVED rule against the employee's real data and return the projected
     * payouts, persisting nothing. Same request body as create; same response shape as {@code /projection}.
     * The {@code userUuid} query param, when present, must match the body's owner.
     */
    @POST
    @Path("/preview")
    @RolesAllowed({"bonus:read", "bonus:write"})
    public Response preview(@QueryParam("userUuid") String userUuid,
                            @QueryParam("action") String action,
                            @QueryParam("ruleUuid") String ruleUuid,
                            @HeaderParam("Idempotency-Key") String idempotencyKey,
                            @Valid IndividualBonusRuleRequest request) {
        if (userUuid != null && !userUuid.isBlank() && !userUuid.equals(request.userUuid())) {
            throw new BadRequestException("userUuid query param must match the request body userUuid");
        }
        IndividualBonusRule previewPersisted = "UPDATE".equals(action)
                && ruleUuid != null && !ruleUuid.isBlank()
                ? IndividualBonusRule.findById(ruleUuid) : null;
        boolean controlledPreview = IndividualBonusMutationService.isMonthly(request)
                || (previewPersisted != null && mutationService.isMonthly(previewPersisted));
        String actor = null;
        Long revision = null;
        String proofAction = action;
        boolean reduction = false;
        if (controlledPreview) {
            // Authorize before touching compensation facts/salary. A read-only caller must not be able to
            // infer controlled-rule state from calculation or validation failures.
            if (!scopeContext.hasScope("bonus:write")) throw new ForbiddenException();
            actor = requireHumanActor();
            if (!"CREATE".equals(action) && !"UPDATE".equals(action)) {
                throw new IndividualBonusException(400, "INVALID_PREVIEW_ACTION",
                        "Monthly Preview requires action CREATE or UPDATE", "action");
            }
            if ("CREATE".equals(action)) {
                IndividualBonusMutationService.validateMonthlyCreateContract(request, ruleUuid);
            } else {
                IndividualBonusMutationService.validateMonthlyUpdatePreviewRuleUuid(action, ruleUuid);
            }
            if ("UPDATE".equals(action)) {
                if (previewPersisted == null
                        || !Objects.equals(previewPersisted.getUserUuid(), request.userUuid())) {
                    throw new NotFoundException("Individual bonus rule not found");
                }
                revision = request.revision();
                if (revision == null || !Objects.equals(revision, previewPersisted.getRevision())) {
                    throw new IndividualBonusException(409, "RULE_REVISION_STALE",
                            "Rule was changed; reload it before continuing", "revision");
                }
                if (mutationService.isFailSafeReduction(previewPersisted, request)) {
                    proofAction = "FAIL_SAFE_REDUCTION";
                }
            }
            reduction = "FAIL_SAFE_REDUCTION".equals(proofAction);
            if (!monthlyConfig.authoringEnabled() && !reduction) {
                throw new IndividualBonusException(503, "MONTHLY_AUTHORING_DISABLED",
                        "Monthly bonus authoring is disabled");
            }
        }
        List<ProjectedPayout> projected = bonusService.preview(request);
        List<ProjectedPayoutDTO> payouts = IndividualBonusMutationService.isMonthly(request)
                ? monthlyPreviewDTOs(request, projected,
                "UPDATE".equals(action) ? ruleUuid : null)
                : projected.stream().map(this::toDTO).toList();
        Response.ResponseBuilder response = Response.ok(payouts).header("Cache-Control", "no-store");
        if (controlledPreview) {
            boolean blocked = payouts.isEmpty()
                    || payouts.stream().anyMatch(p -> "BLOCKED".equals(p.payoutStatus()));
            if (!blocked || reduction) {
                var issued = proofService.issueRuleProof(action, actor, request.userUuid(), ruleUuid,
                        revision, idempotencyKey, request, proofAction);
                response.header(IndividualBonusPreviewProofService.PROOF_HEADER, issued.token())
                        .header(IndividualBonusPreviewProofService.PROOF_EXPIRES_HEADER, issued.expiresAtUtc());
            }
        }
        return response.build();
    }

    /**
     * Read-time projection of upcoming (and committed) payouts for a user over the horizon.
     * Nothing is materialised.
     */
    @GET
    @Path("/projection")
    public List<ProjectedPayoutDTO> projection(@QueryParam("userUuid") String userUuid,
                                               @QueryParam("horizonMonths") @DefaultValue("24") int horizonMonths) {
        if (userUuid == null || userUuid.isBlank()) {
            throw new BadRequestException("userUuid is required");
        }
        LocalDate horizonEnd = LocalDate.now().plusMonths(Math.max(1, horizonMonths)).withDayOfMonth(1);
        return scheduleService.project(userUuid, horizonEnd).stream().map(this::toDTO).toList();
    }

    /** Materialise all payouts due in the given month (default: current month). Idempotent. */
    @POST
    @Path("/payouts/run")
    @RolesAllowed({"bonus:write"})
    public Response runPayouts(@QueryParam("month") String month) {
        LocalDate payMonth = parseMonth(month);
        int created = payoutService.materializeDue(payMonth);
        return Response.ok(Map.of("month", payMonth.toString(), "created", created)).build();
    }

    @GET
    @Path("/capabilities")
    public IndividualBonusCapabilitiesDTO capabilities() {
        return new IndividualBonusCapabilitiesDTO(monthlyConfig.authoringEnabled(),
                monthlyConfig.materializationEnabled(), monthlyConfig.reconciliationEnabled(),
                monthlyConfig.boundedDueLookbackMonths());
    }

    @POST
    @Path("/payouts/run-monthly")
    @RolesAllowed({"bonus:write"})
    public Response runMonthly(IndividualBonusRunMonthlyRequest request) {
        if (request == null || request.payMonth() == null || request.payMonth().getDayOfMonth() != 1) {
            throw new IndividualBonusException(400, "INVALID_PAY_MONTH",
                    "payMonth must be the first day of a month", "payMonth");
        }
        if (!request.isDryRun() && !Boolean.TRUE.equals(request.openPayrollAttestation())) {
            throw new IndividualBonusException(400, "OPEN_PAYROLL_ATTESTATION_REQUIRED",
                    "openPayrollAttestation must be true", "openPayrollAttestation");
        }
        return Response.ok(payoutService.materializeCalendarMonthDue(request.payMonth(),
                        requireHumanActor(), request.isDryRun()))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/adjustments")
    @RolesAllowed({"bonus:read"})
    public Response adjustments(@QueryParam("userUuid") String userUuid,
                                @QueryParam("state") List<String> states,
                                @QueryParam("earningMonthFrom") LocalDate earningFrom,
                                @QueryParam("earningMonthTo") LocalDate earningTo,
                                @QueryParam("page") @DefaultValue("0") int page,
                                @QueryParam("pageSize") @DefaultValue("25") int pageSize) {
        return Response.ok(adjustmentService.list(userUuid, states, earningFrom, earningTo, page, pageSize))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/adjustments/{id}")
    @RolesAllowed({"bonus:read"})
    public Response adjustment(@PathParam("id") String id, @QueryParam("userUuid") String userUuid) {
        return Response.ok(adjustmentService.detail(id, userUuid))
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/adjustments/{id}/preview")
    @RolesAllowed({"bonus:write"})
    public Response previewAdjustment(@PathParam("id") String id, @QueryParam("userUuid") String userUuid,
                                      IndividualBonusAdjustmentRequest request) {
        var result = adjustmentService.preview(id, userUuid, request, requireHumanActor());
        return Response.ok(result.body())
                .header(IndividualBonusPreviewProofService.PROOF_HEADER, result.proof().token())
                .header(IndividualBonusPreviewProofService.PROOF_EXPIRES_HEADER, result.proof().expiresAtUtc())
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/adjustments/{id}/confirm")
    @RolesAllowed({"bonus:write"})
    public Response confirmAdjustment(@PathParam("id") String id, @QueryParam("userUuid") String userUuid,
                                      @HeaderParam(IndividualBonusPreviewProofService.PROOF_HEADER) String proof,
                                      IndividualBonusAdjustmentRequest request) {
        return Response.ok(adjustmentService.confirm(id, userUuid, request, proof, requireHumanActor()))
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/adjustments/{id}/manual-settlement")
    @RolesAllowed({"bonus:write"})
    public Response settleAdjustment(@PathParam("id") String id, @QueryParam("userUuid") String userUuid,
                                     IndividualBonusManualSettlementRequest request) {
        return Response.ok(Map.of("adjustment", adjustmentService.manualSettlement(
                        id, userUuid, request, requireHumanActor())))
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/adjustments/{id}/retry")
    @RolesAllowed({"bonus:write"})
    public Response retryAdjustment(@PathParam("id") String id, @QueryParam("userUuid") String userUuid,
                                    IndividualBonusAdjustmentRequest request) {
        return Response.ok(adjustmentService.retry(id, userUuid, request, requireHumanActor()))
                .header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/reconciliation/scan")
    @RolesAllowed({"bonus:write"})
    public Response scan(IndividualBonusReconciliationScanRequest request) {
        return Response.ok(reconciliationService.scan(request, requireHumanActor()))
                .header("Cache-Control", "no-store").build();
    }

    private LocalDate parseMonth(String month) {
        if (month == null || month.isBlank()) return LocalDate.now().withDayOfMonth(1);
        try {
            return LocalDate.parse(month).withDayOfMonth(1);
        } catch (RuntimeException e) {
            throw new BadRequestException("month must be an ISO date (yyyy-MM-01)");
        }
    }

    private ProjectedPayoutDTO toDTO(ProjectedPayout p) {
        if (p.sourceReference() != null && p.sourceReference().contains(":monthly:")) {
            return toMonthlyDTO(p);
        }
        return new ProjectedPayoutDTO(p.month(), p.amount(), p.kind().name(), p.status().name(),
                p.sourceReference(), p.estimated(), p.truncatedByTermination());
    }

    private ProjectedPayoutDTO toMonthlyDTO(ProjectedPayout p) {
        String ruleUuid = null;
        YearMonth earningMonth = null;
        try {
            String[] parts = p.sourceReference().split(":");
            ruleUuid = parts[1];
            String identity = parts[3];
            earningMonth = YearMonth.of(Integer.parseInt(identity.substring(0, 4)),
                    Integer.parseInt(identity.substring(4, 6)));
            IndividualBonusRule rule = IndividualBonusRule.findById(ruleUuid);
            if (rule == null) return new ProjectedPayoutDTO(p.month(), p.amount(), p.kind().name(),
                    p.status().name(), p.sourceReference(), p.estimated(), p.truncatedByTermination());
            IndividualBonusPayout committed = IndividualBonusPayout.find("sourceReference", p.sourceReference())
                    .firstResult();
            IndividualBonusAdjustment active = IndividualBonusAdjustment.find(
                    "ruleUuid = ?1 and earningMonth = ?2 and state in ?3 order by revision desc",
                    ruleUuid, earningMonth.atDay(1), Set.of("BLOCKED", "ADJUSTMENT_REQUIRED",
                            "MANUAL_DEDUCTION_REQUIRED")).firstResult();
            if (committed != null && committed.getSnapshotVersion() != null
                    && committed.getSnapshotVersion() == 2 && committed.getCalculationSnapshot() != null) {
                return monthlyFromSnapshot(p, ruleUuid, committed, active);
            }
            MonthlyCalculationResult c = monthlyCalculationService.calculate(rule, specMapper.parse(rule.getSpec()),
                    earningMonth, LocalDate.now(COPENHAGEN));
            String failure = Boolean.TRUE.equals(rule.getActive()) ? c.blockerCode() : "RULE_INACTIVE";
            String payoutStatus = active != null ? active.getState()
                    : failure != null ? "BLOCKED" : c.payoutStatus().name();
            boolean unavailable = "BLOCKED".equals(payoutStatus)
                    || "UNKNOWN".equals(c.calculationState().name());
            ProjectedPayoutDTO.ManualAction manual = active == null && failure == null ? null
                    : new ProjectedPayoutDTO.ManualAction(failure != null ? failure : active.getIssueType(),
                    active == null ? null : active.getUuid(), "Payroll review required");
            return new ProjectedPayoutDTO(p.month(), unavailable ? null : c.finalSupplement(),
                    "MONTHLY", p.status().name(),
                    p.sourceReference(), c.calculationState().name().equals("ESTIMATED"), false,
                    c.payMonth().atDay(1), c.earningMonth().atDay(1), payoutStatus,
                    c.calculationState().name(), unavailable || c.utilization() == null
                    ? null : c.utilization().rawUtilization(),
                    unavailable ? null : c.selectedBand(), unavailable ? null : c.employmentFactor(),
                    unavailable ? null : c.effectiveBaseSalary(), unavailable ? null : c.finalSupplement(),
                    unavailable ? null : c.displayedTotalSalary(), unavailable ? null : breakdown(c),
                    failure, manual, ruleUuid, null, null,
                    active == null ? null : active.getUuid());
        } catch (RuntimeException e) {
            ProjectedPayoutDTO.ManualAction manual = new ProjectedPayoutDTO.ManualAction(
                    "CALCULATION_UNAVAILABLE", null, "Payroll review required");
            return new ProjectedPayoutDTO(p.month(), null, "MONTHLY", p.status().name(),
                    p.sourceReference(), false, false, p.month(),
                    earningMonth == null ? null : earningMonth.atDay(1), "BLOCKED", "UNKNOWN",
                    null, null, null, null, null, null, null,
                    "CALCULATION_UNAVAILABLE", manual, ruleUuid, null, null, null);
        }
    }

    private List<ProjectedPayoutDTO> monthlyPreviewDTOs(IndividualBonusRuleRequest request,
                                                        List<ProjectedPayout> projected,
                                                        String previewRuleUuid) {
        IndividualBonusRule transientRule = new IndividualBonusRule();
        transientRule.setUuid(projected.isEmpty() ? UUID.randomUUID().toString()
                : projected.get(0).sourceReference().split(":")[1]);
        transientRule.setUserUuid(request.userUuid());
        transientRule.setName(request.name());
        transientRule.setEffectiveFrom(request.effectiveFrom());
        transientRule.setEffectiveTo(request.effectiveTo());
        transientRule.setReplaces(request.replaces());
        transientRule.setActive(request.active() == null || request.active());
        transientRule.setRevision(request.revision() == null ? 0L : request.revision());
        transientRule.setSpec(specMapper.serialize(request.spec()));
        List<ProjectedPayoutDTO> result = new ArrayList<>();
        for (ProjectedPayout payout : projected) {
            String identity = payout.sourceReference().substring(payout.sourceReference().lastIndexOf(':') + 1);
            YearMonth earning = YearMonth.of(Integer.parseInt(identity.substring(0, 4)),
                    Integer.parseInt(identity.substring(4, 6)));
            MonthlyCalculationResult c = monthlyCalculationService.calculate(transientRule, request.spec(),
                    earning, LocalDate.now(COPENHAGEN));
            ProjectedPayoutDTO.ManualAction manual = c.blockerCode() == null ? null
                    : new ProjectedPayoutDTO.ManualAction(c.blockerCode(), null, "Payroll review required");
            boolean unavailable = c.payoutStatus().name().equals("BLOCKED")
                    || c.calculationState().name().equals("UNKNOWN");
            result.add(new ProjectedPayoutDTO(payout.month(), unavailable ? null : c.finalSupplement(), "MONTHLY",
                    payout.status().name(), payout.sourceReference(),
                    c.calculationState().name().equals("ESTIMATED"), false,
                    c.payMonth().atDay(1), c.earningMonth().atDay(1), c.payoutStatus().name(),
                    c.calculationState().name(), unavailable || c.utilization() == null
                    ? null : c.utilization().rawUtilization(),
                    unavailable ? null : c.selectedBand(), unavailable ? null : c.employmentFactor(),
                    unavailable ? null : c.effectiveBaseSalary(), unavailable ? null : c.finalSupplement(),
                    unavailable ? null : c.displayedTotalSalary(), unavailable ? null : breakdown(c),
                    c.blockerCode(), manual,
                    previewRuleUuid, null, null, null));
        }
        return List.copyOf(result);
    }

    private ProjectedPayoutDTO monthlyFromSnapshot(ProjectedPayout p, String ruleUuid,
                                                   IndividualBonusPayout payout,
                                                   IndividualBonusAdjustment active) {
        try {
            JsonNode root = mapper.readTree(payout.getCalculationSnapshot());
            JsonNode calc = root.path("calculation");
            JsonNode salary = root.path("salaryGuard");
            JsonNode timing = root.path("timing");
            StepBand band = calc.path("selectedBand").isMissingNode() || calc.path("selectedBand").isNull()
                    ? null : mapper.treeToValue(calc.path("selectedBand"), StepBand.class);
            FactCoverage coverage = calc.path("factCoverage").isMissingNode()
                    || calc.path("factCoverage").isNull() ? null
                    : mapper.treeToValue(calc.path("factCoverage"), FactCoverage.class);
            ProjectedPayoutDTO.Breakdown breakdown = new ProjectedPayoutDTO.Breakdown(
                    decimal(calc, "billableHours"), decimal(calc, "availableHours"),
                    decimal(calc, "grossOverlapHours"), decimal(calc, "grossFullMonthHours"), coverage,
                    decimal(calc, "rawUtilization"), decimal(calc, "selectionUtilization"),
                    new ProjectedPayoutDTO.SalaryGuard(decimal(salary, "expectedBaseSalary"),
                            decimal(salary, "effectiveBaseSalary"), "MATCH"));
            String payoutStatus = active != null ? active.getState() : payout.getMaterializationStatus();
            ProjectedPayoutDTO.ManualAction manual = active == null ? null
                    : new ProjectedPayoutDTO.ManualAction(active.getIssueType(), active.getUuid(),
                    "Payroll review required");
            BigDecimal netCommitted = committedNet(payout);
            String compatibilityStatus = "COMMITTED".equals(payout.getMaterializationStatus())
                    || (netCommitted != null && netCommitted.signum() > 0) ? "COMMITTED" : "PROJECTED";
            return new ProjectedPayoutDTO(payout.getMonth(), payout.getAmount(), "MONTHLY",
                    compatibilityStatus,
                    payout.getSourceReference(), false, false,
                    LocalDate.parse(timing.path("payMonth").asText()),
                    LocalDate.parse(timing.path("earningMonth").asText()), payoutStatus, "ACTUAL",
                    decimal(calc, "rawUtilization"), band, decimal(calc, "employmentFactor"),
                    decimal(salary, "effectiveBaseSalary"), payout.getAmount(),
                    decimal(salary, "displayedTotalSalary"), breakdown, null, manual, ruleUuid,
                    payout.getUuid(), netCommitted, active == null ? null : active.getUuid());
        } catch (Exception e) {
            return new ProjectedPayoutDTO(p.month(), payout.getAmount(), p.kind().name(), p.status().name(),
                    p.sourceReference(), false, p.truncatedByTermination());
        }
    }

    private static ProjectedPayoutDTO.Breakdown breakdown(MonthlyCalculationResult c) {
        return new ProjectedPayoutDTO.Breakdown(
                c.utilization() == null ? null : c.utilization().billableHours(),
                c.utilization() == null ? null : c.utilization().availableHours(),
                c.grossOverlapHours(), c.grossFullMonthHours(),
                c.utilization() == null ? null : c.utilization().coverage(),
                c.utilization() == null ? null : c.utilization().rawUtilization(),
                c.selectionUtilization(), new ProjectedPayoutDTO.SalaryGuard(c.expectedBaseSalary(),
                c.effectiveBaseSalary(), Objects.equals(c.expectedBaseSalary(), c.effectiveBaseSalary())
                        ? "MATCH" : "MISMATCH"));
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.decimalValue();
    }

    private static BigDecimal committedNet(IndividualBonusPayout payout) {
        BigDecimal amount = payout.getAmount();
        List<IndividualBonusAdjustment> settled = IndividualBonusAdjustment.find(
                "ruleUuid = ?1 and earningMonth = ?2 and state in ?3", payout.getRuleUuid(),
                payout.getEarningMonth(), List.of("ADJUSTMENT_COMMITTED", "MANUALLY_SETTLED")).list();
        for (IndividualBonusAdjustment adjustment : settled) {
            BigDecimal delta = "MANUALLY_SETTLED".equals(adjustment.getState())
                    ? adjustment.getSettledDeltaAmount() : adjustment.getDeltaAmount();
            if (delta != null) amount = amount.add(delta);
        }
        return amount;
    }

    private String requireHumanActor() {
        String actor = requestHeaderHolder.getUserUuid();
        try {
            return UUID.fromString(actor).toString();
        } catch (RuntimeException e) {
            throw new IndividualBonusException(400, "AUDIT_ACTOR_REQUIRED",
                    "A valid X-Requested-By user UUID is required", "X-Requested-By");
        }
    }
}
