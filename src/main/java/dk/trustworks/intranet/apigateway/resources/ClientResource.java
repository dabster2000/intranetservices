package dk.trustworks.intranet.apigateway.resources;


import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.invoice.economics.customer.EconomicsCustomerSyncService;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.ClientBillingValidator;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.ClientActivityLogDTO;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponses;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.*;

@Tag(name = "crm")
@JBossLog
@Path("/clients")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"crm:read"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ClientResource {

    @Inject
    ClientService clientAPI;

    @Inject
    dk.trustworks.intranet.aggregates.crm.news.ClientNewsService clientNewsService;

    /**
     * The after-write hooks of the nightly enrichment jobs: a client saved under OTHER gets
     * its sector checked asynchronously, and an edit that changes the CVR or the sector is
     * written down on the enrichment row (V610). Never on the request's critical path —
     * both hooks catch their own failures.
     */
    @Inject
    dk.trustworks.intranet.aggregates.crm.enrichment.services.ClientEnrichmentService clientEnrichmentService;

    @GET
    @Path("/{uuid}/news")
    @Produces("application/json")
    @RolesAllowed({"accounts:read"})
    public dk.trustworks.intranet.aggregates.crm.news.ClientNewsDTO news(@PathParam("uuid") String uuid) {
        return clientNewsService.get(uuid);
    }

    @Inject
    AggregateEventSender aggregateEventSender;

    @Inject
    ProjectService projectService;

    @Inject
    ContractService contractService;

    @Inject
    BudgetService budgetService;

    @Inject
    ClientActivityLogService activityLogService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EconomicsCustomerSyncService economicsCustomerSyncService;

    @GET
    @Operation(summary = "List clients filtered by type",
            description = "Lists clients filtered by `type` (default CLIENT). Accepts a comma " +
                    "list, e.g. ?type=CLIENT,PROSPECT for the accounts list and the lead form. " +
                    "PARTNERs and PROSPECTs are excluded unless explicitly requested — this " +
                    "hard-filter applies defense-in-depth at the resource layer. " +
                    "SPEC-INV-001 §3.4, §8.8; CRM relationships spec §5.")
    public List<Client> findAll(
            @QueryParam("type") @DefaultValue("CLIENT") String type) {
        return clientAPI.listByTypes(parseTypes(type));
    }

    /**
     * {@code ?type=} as a comma list.
     *
     * <p><b>The default stays CLIENT alone.</b> A third value on {@code ClientType} is
     * only safe because every existing consumer keeps excluding it until it opts in, so an
     * unparseable or empty parameter must degrade to CLIENT rather than to everything —
     * the failure mode of the other reading is a prospect in the invoice picker.
     */
    static Set<ClientType> parseTypes(String raw) {
        Set<ClientType> types = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String value = part.trim().toUpperCase(Locale.ROOT);
                if (value.isEmpty()) {
                    continue;
                }
                try {
                    types.add(ClientType.valueOf(value));
                } catch (IllegalArgumentException e) {
                    throw new BadRequestException("Unknown client type: " + part.trim());
                }
            }
        }
        return types.isEmpty() ? Set.of(ClientType.CLIENT) : types;
    }

    @GET
    @Path("/{uuid}")
    public Client findByUuid(@PathParam("uuid") String uuid) {
        return clientAPI.findByUuid(uuid);
    }

    @GET
    @Path("/{clientuuid}/projects")
    public List<Project> findByClientUuid(@PathParam("clientuuid") String clientuuid) {
        return projectService.findByClientuuid(clientuuid);
    }

    @GET
    @Path("/{clientuuid}/projects/active")
    public List<Project> findByClientAndActiveTrue(@PathParam("clientuuid") String clientuuid) {
        return projectService.findByClientAndActiveTrue(clientuuid);
    }
/*
    @GET
    @Path("/{clientuuid}/projects/locked")
    public List<Project> findByClientAndLockedTrue(@PathParam("clientuuid") String clientuuid) {
        return clientAPI.findByClientAndProjectLockedTrue(clientuuid);
    }

 */

    @GET
    @Path("/{clientuuid}/contracts")
    public List<Contract> findContractByClientUuid(@PathParam("clientuuid") String clientuuid) {
        log.debugf("findContractByClientUuid: clientuuid=%s", clientuuid);
        return contractService.findByClientuuid(clientuuid);
    }

    @POST
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Create a client", description = "Creates a new client with find-or-create deduplication. " +
            "If a CVR is provided and a client with that CVR already exists, the existing client is returned (HTTP 200) " +
            "with an X-Client-Existing header. If no CVR but a matching name exists, the client is still created (HTTP 201) " +
            "with an X-Client-Duplicate-Warning header.")
    @APIResponses({
            @APIResponse(responseCode = "201", description = "Client created"),
            @APIResponse(responseCode = "200", description = "Existing client returned (CVR match)"),
            @APIResponse(responseCode = "400", description = "Validation error")
    })
    public Response save(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        log.infof("Creating client name=%s, user=%s", client.getName(), userUuid);

        // Apply defaults for billingCountry and currency if not provided
        if (client.getBillingCountry() == null || client.getBillingCountry().isBlank()) {
            client.setBillingCountry("DK");
        }
        if (client.getCurrency() == null || client.getCurrency().isBlank()) {
            client.setCurrency("DKK");
        }

        // Validate input
        Response validationError = validateClient(client);
        if (validationError != null) {
            return validationError;
        }

        // Find-or-create: check CVR dedup
        String cvr = client.getCvr();
        if (cvr != null && !cvr.isBlank()) {
            Client existing = clientAPI.findByCvr(cvr.trim());
            if (existing != null) {
                log.infof("Client with CVR=%s already exists uuid=%s, returning existing, user=%s",
                        cvr, existing.getUuid(), userUuid);
                return Response.ok(existing).header("X-Client-Existing", "true").build();
            }
        }

        // Check name-based duplicate warning (when no CVR provided)
        String duplicateWarningUuid = null;
        if (cvr == null || cvr.isBlank()) {
            Client nameMatch = clientAPI.findByExactNameIgnoreCase(client.getName());
            if (nameMatch != null) {
                duplicateWarningUuid = nameMatch.getUuid();
                log.infof("Name match found for name=%s, existing uuid=%s, still creating, user=%s",
                        client.getName(), duplicateWarningUuid, userUuid);
            }
        }

        Client created = clientAPI.save(client);
        CreateClientEvent createClientEvent = new CreateClientEvent(created.getUuid(), created);
        aggregateEventSender.handleEvent(createClientEvent);

        // Log activity
        activityLogService.logCreated(created.getUuid(),
                ClientActivityLog.TYPE_CLIENT, created.getUuid(), created.getName());

        // Sector OTHER? The AI checks it now, off the request thread (enrichment hook).
        clientEnrichmentService.onClientCreated(created);

        log.infof("Created client uuid=%s, name=%s, user=%s", created.getUuid(), created.getName(), userUuid);

        // Fire-and-forget sync to every configured e-conomic agreement. Sync service
        // records per-agreement failures to client_economics_sync_failures for the
        // retry batchlet; defensively wrap so a transient error never fails the
        // resource response. SPEC-INV-001 §3.3, §7.2.
        //
        // NOT for a prospect. Every client created on this form used to become a customer
        // in both e-conomic agreements the same minute, which is what made "add the
        // company somebody had a coffee with" a bookkeeping act. For a prospect the sync
        // moves to graduation — the first contract, in ContractService.save.
        if (created.getType() != ClientType.PROSPECT) {
            syncClientToEconomicsSafe(created, "create");
        }

        Response.ResponseBuilder responseBuilder = Response.status(Response.Status.CREATED).entity(created);
        if (duplicateWarningUuid != null) {
            responseBuilder.header("X-Client-Duplicate-Warning", duplicateWarningUuid);
        }
        return responseBuilder.build();
    }

    @PUT
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Update a client", description = "Full update of client fields including billing and CVR registry data. " +
            "All field changes are tracked in the activity log.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Client updated"),
            @APIResponse(responseCode = "400", description = "Validation error")
    })
    public Response updateOne(Client client) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        log.infof("Updating client uuid=%s, name=%s, user=%s", client.getUuid(), client.getName(), userUuid);

        // Apply defaults for billingCountry and currency if not provided
        if (client.getBillingCountry() == null || client.getBillingCountry().isBlank()) {
            client.setBillingCountry("DK");
        }
        if (client.getCurrency() == null || client.getCurrency().isBlank()) {
            client.setCurrency("DKK");
        }

        // Validate input
        Response validationError = validateClient(client);
        if (validationError != null) {
            return validationError;
        }

        // Load old state for change logging
        Client oldClient = clientAPI.findByUuid(client.getUuid());

        // PROSPECT → CLIENT is a promotion somebody may make by hand (filling the billing
        // details early is exactly what the account page offers a link for). The reverse
        // is refused: a company we have billed is a customer for ever, and a form that
        // could quietly un-bill one would be the `active` flag all over again.
        if (oldClient != null
                && oldClient.getType() != ClientType.PROSPECT
                && client.getType() == ClientType.PROSPECT) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "A client cannot be turned back into a prospect"))
                    .build();
        }

        clientAPI.updateOne(client);

        // What the person decided about the CVR and the sector, for the enrichment jobs —
        // and the sector check now, if the row is (still) OTHER.
        clientEnrichmentService.onClientUpdated(oldClient, client);

        // Log field-level changes
        if (oldClient != null) {
            String clientUuid = client.getUuid();
            String entityName = oldClient.getName();

            if (!Objects.equals(oldClient.getName(), client.getName())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "name", oldClient.getName(), client.getName());
            }
            if (!Objects.equals(oldClient.getContactname(), client.getContactname())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "contactname", oldClient.getContactname(), client.getContactname());
            }
            if (oldClient.getSegment() != client.getSegment()) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "segment", String.valueOf(oldClient.getSegment()), String.valueOf(client.getSegment()));
            }
            if (!Objects.equals(oldClient.getAccountmanager(), client.getAccountmanager())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "accountmanager", oldClient.getAccountmanager(), client.getAccountmanager());
            }
            // Log changes for billing and CVR registry fields
            if (!Objects.equals(oldClient.getCvr(), client.getCvr())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "cvr", oldClient.getCvr(), client.getCvr());
            }
            if (!Objects.equals(oldClient.getEan(), client.getEan())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "ean", oldClient.getEan(), client.getEan());
            }
            if (!Objects.equals(oldClient.getBillingAddress(), client.getBillingAddress())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "billingAddress", oldClient.getBillingAddress(), client.getBillingAddress());
            }
            if (!Objects.equals(oldClient.getBillingZipcode(), client.getBillingZipcode())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "billingZipcode", oldClient.getBillingZipcode(), client.getBillingZipcode());
            }
            if (!Objects.equals(oldClient.getBillingCity(), client.getBillingCity())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "billingCity", oldClient.getBillingCity(), client.getBillingCity());
            }
            if (!Objects.equals(oldClient.getBillingCountry(), client.getBillingCountry())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "billingCountry", oldClient.getBillingCountry(), client.getBillingCountry());
            }
            if (!Objects.equals(oldClient.getBillingEmail(), client.getBillingEmail())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "billingEmail", oldClient.getBillingEmail(), client.getBillingEmail());
            }
            if (!Objects.equals(oldClient.getCurrency(), client.getCurrency())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "currency", oldClient.getCurrency(), client.getCurrency());
            }
            if (!Objects.equals(oldClient.getPhone(), client.getPhone())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "phone", oldClient.getPhone(), client.getPhone());
            }
            if (!Objects.equals(oldClient.getIndustryCode(), client.getIndustryCode())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "industryCode", String.valueOf(oldClient.getIndustryCode()), String.valueOf(client.getIndustryCode()));
            }
            if (!Objects.equals(oldClient.getIndustryDesc(), client.getIndustryDesc())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "industryDesc", oldClient.getIndustryDesc(), client.getIndustryDesc());
            }
            if (!Objects.equals(oldClient.getCompanyCode(), client.getCompanyCode())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "companyCode", String.valueOf(oldClient.getCompanyCode()), String.valueOf(client.getCompanyCode()));
            }
            if (!Objects.equals(oldClient.getCompanyDesc(), client.getCompanyDesc())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "companyDesc", oldClient.getCompanyDesc(), client.getCompanyDesc());
            }
        }

        // Propagate field updates to every configured e-conomic agreement.
        // Non-blocking — failures are captured in client_economics_sync_failures.
        // SPEC-INV-001 §3.3, §7.2. A prospect has no e-conomic customer to update.
        if (client.getType() != ClientType.PROSPECT) {
            syncClientToEconomicsSafe(client, "update");
        }

        return Response.ok().build();
    }

    /**
     * Invokes {@link EconomicsCustomerSyncService#syncToAllCompanies} with a
     * defensive try/catch so a transient sync failure never propagates to the
     * HTTP response. The sync service already persists per-agreement failures
     * for the retry batchlet; this wrap protects against any unexpected
     * runtime error.
     */
    private void syncClientToEconomicsSafe(Client client, String op) {
        try {
            economicsCustomerSyncService.syncToAllCompanies(client);
        } catch (RuntimeException e) {
            log.warnf(e, "e-conomic customer sync failed during client %s uuid=%s; " +
                    "retry batchlet will pick up any recorded failures", op, client.getUuid());
        }
    }

    @GET
    @Path("/{clientuuid}/activity")
    public List<ClientActivityLogDTO> getClientActivity(
            @PathParam("clientuuid") String clientuuid,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return activityLogService.getActivityForClient(clientuuid, limit);
    }

    @GET
    @Path("/contract-counts")
    public List<Map<String, Object>> getContractCounts() {
        return clientAPI.getContractCounts();
    }

    @GET
    @Path("/consultants")
    public List<Map<String, String>> getClientConsultants(
            @QueryParam("fromdate") String fromDate,
            @QueryParam("todate") String toDate) {
        return clientAPI.getClientConsultants(
                DateUtils.dateIt(fromDate), DateUtils.dateIt(toDate));
    }

    @GET
    @Path("/search")
    @RolesAllowed({"crm:read"})
    @Operation(summary = "Search for clients", description = "Search clients by CVR (exact match) or name (case-insensitive partial match). " +
            "At least one of cvr or name must be provided.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "List of matching clients"),
            @APIResponse(responseCode = "400", description = "At least one search parameter is required")
    })
    public Response searchClients(
            @Parameter(description = "CVR number for exact match", example = "25674114")
            @QueryParam("cvr") String cvr,
            @Parameter(description = "Company name for case-insensitive partial match", example = "Trustworks")
            @QueryParam("name") String name) {
        if ((cvr == null || cvr.isBlank()) && (name == null || name.isBlank())) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "At least one search parameter (cvr or name) is required"))
                    .build();
        }
        List<Client> results = clientAPI.searchClients(cvr, name);
        return Response.ok(results).build();
    }

    @GET
    @Path("/budgets/{fiscalyear}")
    public List<GraphKeyValue> getClientBudgetSum(@PathParam("fiscalyear") int fiscalYear) {
        LocalDate startDate = DateUtils.getCurrentFiscalStartDate().withYear(fiscalYear);
        LocalDate endDate = startDate.plusYears(1);
        List<EmployeeBudgetPerMonth> employeeBudgetPerMonthList = budgetService.getBudgetDataByPeriod(startDate, endDate);
        Map<String, GraphKeyValue> clientBudgets = new HashMap<>();
        for (EmployeeBudgetPerMonth employeeBudgetPerMonth : employeeBudgetPerMonthList) {
            Client client = employeeBudgetPerMonth.getClient();
            clientBudgets.putIfAbsent(client.getUuid(), new GraphKeyValue(client.getUuid(), client.getName(), 0.0));
            clientBudgets.get(client.getUuid()).addValue(employeeBudgetPerMonth.getRate()* employeeBudgetPerMonth.getBudgetHours());
        }
        return new ArrayList<>(clientBudgets.values());
    }

    /**
     * Validates client billing fields. Returns a 400 Response if validation fails, or null if valid.
     *
     * <p><b>A PROSPECT is not asked for billing completeness.</b> It has never been billed
     * and may never be: requiring a CVR to write down that somebody had a coffee with a
     * company is the reason people were not writing it down. The same rules are enforced in
     * {@code ContractService.save} the moment a contract makes the row a customer, which is
     * the first minute they are true — and they are the SAME rules, from
     * {@link ClientBillingValidator}, so a company cannot pass one gate and fail the other.
     * Format checks on whatever was filled in still apply.
     */
    private Response validateClient(Client client) {
        String problem = client.getType() == ClientType.PROSPECT
                ? ClientBillingValidator.formatProblem(client)
                : ClientBillingValidator.billingProblem(client);
        if (problem == null) {
            return null;
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("error", problem))
                .build();
    }
}
