package dk.trustworks.intranet.apigateway.resources;


import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.aggregateservices.model.BudgetDocumentPerDay;
import dk.trustworks.intranet.aggregateservices.v2.RevenueService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "crm")
@JBossLog
@Path("/clients")
@RequestScoped
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
@SecurityScheme(securitySchemeName = "jwt", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "jwt")
public class ClientResource {

    @Inject
    RevenueService revenueService;

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

    @GET
    public List<Client> findAll() {
        return clientAPI.listAllClients();
        //return clientAPI.listAll();
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
        return contractService.findByClientuuid(clientuuid);
    }

    @POST
    public void save(Client client) {
        CreateClientEvent createClientEvent = new CreateClientEvent(client.getUuid(), client);
        aggregateEventSender.handleEvent(createClientEvent);
    }

    @PUT
    public void updateOne(Client client) {
        log.debug("Updating client:\n"+client);
        clientAPI.updateOne(client);
    }

    @GET
    @Path("/budgets/{fiscalyear}")
    public List<GraphKeyValue> getClientBudgetSum(@PathParam("fiscalyear") int fiscalYear) {
        LocalDate startDate = DateUtils.getCurrentFiscalStartDate().withYear(fiscalYear);
        LocalDate endDate = startDate.plusYears(1).minusMonths(1);
        List<BudgetDocumentPerDay> budgetDocumentPerDayList = budgetService.getBudgetDataByPeriod(startDate, endDate);
        Map<String, GraphKeyValue> clientBudgets = new HashMap<>();
        for (BudgetDocumentPerDay budgetDocumentPerDay : budgetDocumentPerDayList) {
            Client client = budgetDocumentPerDay.getClient();
            clientBudgets.putIfAbsent(client.getUuid(), new GraphKeyValue(client.getUuid(), client.getName(), 0.0));
            clientBudgets.get(client.getUuid()).addValue(budgetDocumentPerDay.getRate()* budgetDocumentPerDay.getBudgetHours());
        }
        return new ArrayList<>(clientBudgets.values());
    }
}
