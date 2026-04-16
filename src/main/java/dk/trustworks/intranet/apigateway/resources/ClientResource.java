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
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.utils.EanValidator;
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

    private static final Set<String> VALID_CURRENCIES = Set.of("DKK", "EUR", "NOK", "SEK", "USD", "GBP");
    private static final java.util.regex.Pattern CVR_PATTERN = java.util.regex.Pattern.compile("^\\d{8}$");
    private static final java.util.regex.Pattern COUNTRY_PATTERN = java.util.regex.Pattern.compile("^[A-Z]{2}$");

    @GET
    @Operation(summary = "List clients filtered by type",
            description = "Lists clients filtered by `type` (default CLIENT). " +
                    "PARTNERs are excluded unless explicitly requested via ?type=PARTNER — " +
                    "this hard-filter applies defense-in-depth at the resource layer. " +
                    "Optional `active` filter: omit for both active and inactive (preserves prior default), " +
                    "or pass true/false to filter. SPEC-INV-001 §3.4, §8.8.")
    public List<Client> findAll(
            @QueryParam("type")   @DefaultValue("CLIENT") ClientType type,
            @QueryParam("active")                         Boolean active) {
        return clientAPI.listByTypeAndActive(type, active);
    }

    @GET
    @Path("/{uuid}")
    public Client findByUuid(@PathParam("uuid") String uuid) {
        return clientAPI.findByUuid(uuid);
    }

    @GET
    @Path("/active")
    @Operation(summary = "List active clients filtered by type (default CLIENT)",
            description = "Backward-compatible endpoint that now also applies the CLIENT/PARTNER " +
                    "filter. Defaults to CLIENT so legacy callers see only end-customers. " +
                    "SPEC-INV-001 §3.4, §8.8.")
    public List<Client> findByActiveTrue(
            @QueryParam("type") @DefaultValue("CLIENT") ClientType type) {
        return clientAPI.listByTypeAndActive(type, true);
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

        log.infof("Created client uuid=%s, name=%s, user=%s", created.getUuid(), created.getName(), userUuid);

        // Fire-and-forget sync to every configured e-conomic agreement. Sync service
        // records per-agreement failures to client_economics_sync_failures for the
        // retry batchlet; defensively wrap so a transient error never fails the
        // resource response. SPEC-INV-001 §3.3, §7.2.
        syncClientToEconomicsSafe(created, "create");

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

        clientAPI.updateOne(client);

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
            if (oldClient.isActive() != client.isActive()) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CLIENT, clientUuid, entityName,
                        "active", String.valueOf(oldClient.isActive()), String.valueOf(client.isActive()));
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
        // SPEC-INV-001 §3.3, §7.2.
        syncClientToEconomicsSafe(client, "update");

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

    @PATCH
    @Path("/{uuid}/active")
    @RolesAllowed({"crm:write"})
    @Operation(summary = "Toggle client active status",
            description = "Updates only the active flag without full client validation.")
    @APIResponses({
            @APIResponse(responseCode = "200", description = "Status updated"),
            @APIResponse(responseCode = "404", description = "Client not found")
    })
    public Response updateActiveStatus(@PathParam("uuid") String uuid, Map<String, Object> body) {
        String userUuid = requestHeaderHolder != null ? requestHeaderHolder.getUserUuid() : null;
        boolean active = Boolean.TRUE.equals(body.get("active"));
        log.infof("Updating client active status uuid=%s, active=%s, user=%s", uuid, active, userUuid);

        Client existing = clientAPI.findByUuid(uuid);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("error", "Client not found"))
                    .build();
        }

        boolean oldActive = existing.isActive();
        clientAPI.updateActiveStatus(uuid, active);

        if (oldActive != active) {
            activityLogService.logFieldChange(uuid, ClientActivityLog.TYPE_CLIENT, uuid, existing.getName(),
                    "active", String.valueOf(oldActive), String.valueOf(active));
        }

        return Response.ok(Map.of("uuid", uuid, "active", active)).build();
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
     */
    private Response validateClient(Client client) {
        // Name is required, min 2 characters
        if (client.getName() == null || client.getName().trim().length() < 2) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Client name is required (min 2 characters)"))
                    .build();
        }

        // Validate country code format
        String country = client.getBillingCountry();
        if (country != null && !country.isBlank() && !COUNTRY_PATTERN.matcher(country).matches()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid country code"))
                    .build();
        }

        // Validate currency code
        String currency = client.getCurrency();
        if (currency != null && !currency.isBlank() && !VALID_CURRENCIES.contains(currency)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid currency code"))
                    .build();
        }

        // CVR validation for Danish clients
        boolean isDanish = "DK".equals(country);
        String cvr = client.getCvr();
        if (isDanish) {
            if (cvr == null || cvr.isBlank()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "CVR is required for Danish clients"))
                        .build();
            }
            if (!CVR_PATTERN.matcher(cvr.trim()).matches()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "CVR must be exactly 8 digits"))
                        .build();
            }
        }

        // EAN validation (GS1 Modulo 10) — optional field, but must be valid if present
        String ean = client.getEan();
        if (ean != null && !ean.isBlank() && !EanValidator.isValid(ean)) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "EAN must be exactly 13 digits and pass GS1 Modulo 10"))
                    .build();
        }

        return null;
    }
}
