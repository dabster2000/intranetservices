package dk.trustworks.intranet.apigateway.resources;


import dk.trustworks.intranet.aggregates.budgets.model.EmployeeBudgetPerMonth;
import dk.trustworks.intranet.aggregates.budgets.services.BudgetService;
import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.revenue.services.RevenueService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.ClientActivityLogDTO;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
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

    @GET
    public List<Client> findAll() {
        return clientAPI.listAllClients();
    }

    @GET
    @Path("/{uuid}")
    public Client findByUuid(@PathParam("uuid") String uuid) {
        return clientAPI.findByUuid(uuid);
    }

    @GET
    @Path("/active")
    public List<Client> findByActiveTrue() {
        return clientAPI.findByActiveTrue();
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
    @Path("/{clientuuid}/clientdata")
    public List<Clientdata> findClientdataByClientuuid(@PathParam("clientuuid") String clientuuid) {
        return clientAPI.listAllClientData(clientuuid);
    }

    @GET
    @Path("/{clientuuid}/contracts")
    public List<Contract> findContractByClientUuid(@PathParam("clientuuid") String clientuuid) {
        System.out.println("ClientResource.findContractByClientUuid");
        System.out.println("clientuuid = " + clientuuid);
        return contractService.findByClientuuid(clientuuid);
    }

    @POST
    @RolesAllowed({"crm:write"})
    public Response save(Client client) {
        Client created = clientAPI.save(client);
        CreateClientEvent createClientEvent = new CreateClientEvent(created.getUuid(), created);
        aggregateEventSender.handleEvent(createClientEvent);

        // Log activity
        activityLogService.logCreated(created.getUuid(),
                ClientActivityLog.TYPE_CLIENT, created.getUuid(), created.getName());

        return Response.status(Response.Status.CREATED).entity(created).build();
    }

    @PUT
    @RolesAllowed({"crm:write"})
    public void updateOne(Client client) {
        log.debug("Updating client:\n"+client);

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
}
