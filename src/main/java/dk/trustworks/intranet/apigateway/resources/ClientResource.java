package dk.trustworks.intranet.apigateway.resources;


import dk.trustworks.intranet.aggregates.client.events.CreateClientEvent;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.aggregateservices.RevenueService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.BudgetDocument;
import dk.trustworks.intranet.dto.GraphKeyValue;
import dk.trustworks.intranet.dto.KeyValueDTO;
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
import java.util.*;

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
    @Path("/revenue")
    public List<KeyValueDTO> revenuePerClient(@QueryParam("clientuuids") String clientuuids) {
        return revenueService.getRegisteredRevenuePerClient(Arrays.stream(clientuuids.split(",")).toList());
    }

    @GET
    @Path("/budgets/{fiscalyear}")
    public List<GraphKeyValue> getClientBudgetSum(@PathParam("fiscalyear") int fiscalYear) {
        LocalDate startDate = DateUtils.getCurrentFiscalStartDate().withYear(fiscalYear);
        LocalDate endDate = startDate.plusYears(1).minusMonths(1);
        List<BudgetDocument> budgetDocumentList = budgetService.getBudgetDataByPeriod(startDate, endDate);
        Map<String, GraphKeyValue> clientBudgets = new HashMap<>();
        for (BudgetDocument budgetDocument : budgetDocumentList) {
            Client client = budgetDocument.getClient();
            clientBudgets.putIfAbsent(client.getUuid(), new GraphKeyValue(client.getUuid(), client.getName(), 0.0));
            clientBudgets.get(client.getUuid()).addValue(budgetDocument.getRate()*budgetDocument.getBudgetHours());
        }
        return new ArrayList<>(clientBudgets.values());
    }
}
