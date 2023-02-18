package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregateservices.BudgetService;
import dk.trustworks.intranet.contracts.model.Budget;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.services.InvoiceService;
import dk.trustworks.intranet.utils.DateUtils;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@JBossLog
@Tag(name = "crm")
@Path("/projects")
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"USER", "EXTERNAL", "EDITOR", "CXO", "SALES", "VTV", "ACCOUNTING", "MANAGER", "PARTNER", "ADMIN"})
public class ProjectResource {

    @Inject
    BudgetService budgetService;

    @Inject
    ContractService contractAPI;

    @Inject
    ProjectService projectAPI;

    @Inject
    InvoiceService invoiceAPI;

    @Inject
    WorkService workAPI;

    @GET
    public List<Project> listAll() {
        return projectAPI.listAll();
    }

    @GET
    @Path("/{uuid}")
    @SecurityRequirement(name = "jwt", scopes = {})
    public Project findByUuid(@PathParam("uuid") String uuid) {
        return projectAPI.findByUuid(uuid);
    }

    @GET
    @Path("/active")
    public List<Project> findByActiveTrue() {
        return projectAPI.findByActiveTrue();
    }

    @GET
    @Path("/workedon/count")
    public KeyValueDTO projectsBeingWorkedOn(@QueryParam("fromdate") String fromDate, @QueryParam("todate") String toDate) {
        Set<String> currentProjectSet = new TreeSet<>();
        for (WorkFull work : workAPI.findByPeriod(dateIt(fromDate), dateIt(toDate))) {
            //Double rate = contractAPI.findConsultantRateByWork(work, ContractStatus.TIME, ContractStatus.SIGNED, ContractStatus.CLOSED);
            if(work.getRate() > 0 && work.getWorkduration() > 0) {
                currentProjectSet.add(work.getProjectuuid());
            }
        }
        return new KeyValueDTO("Projects being worked on", currentProjectSet.size()+"");
    }

    @GET
    @Path("/locked")
    public List<Project> findByLocked(@QueryParam("locked") boolean locked) {
        return ProjectService.findByLocked(true);
    }

    @GET
    @Path("/{uuid}/tasks")
    public List<Task> findByProjectUuid(@PathParam("uuid") String projectuuid) {
        return projectAPI.findByProjectUuid(projectuuid);
    }

    @GET
    @Path("/{uuid}/work")
    public List<WorkFull> findWorkByTaskFilterByUseruuidAndRegistered(@PathParam("uuid") String uuid, @QueryParam("fromdate") Optional<String> fromdate, @QueryParam("todate") Optional<String> todate) {
        return workAPI.findByPeriodAndProject(fromdate.orElse("2014-02-01"), todate.orElse(DateUtils.stringIt(LocalDate.now())), uuid);
    }

    @GET
    @Path("/{projectuuid}/contracts")
    public List<Contract> findContract(@PathParam("projectuuid") String projectuuid) {
        return contractAPI.findByProjectuuid(projectuuid);
    }

    @GET
    @Path("/{projectuuid}/users/{useruuid}/rates")
    public KeyValueDTO findRateByProjectAndUserAndDate(@PathParam("projectuuid") String projectuuid, @PathParam("useruuid") String useruuid, @QueryParam("date") String stringdate) {
        return new KeyValueDTO("rate", Double.toString(contractAPI.findRateByProjectuuidAndUseruuidAndDate(projectuuid, useruuid, stringdate).getRate()));
    }

    @GET
    @Path("/{projectuuid}/invoices")
    public List<Invoice> findProjectInvoices(@PathParam("projectuuid") String projectuuid) {
        return invoiceAPI.findProjectInvoices(projectuuid);
    }

    @GET
    @Path("/{projectuuid}/budgets")
    public List<Budget> findByConsultantAndProject(@PathParam("projectuuid") String projectuuid, @QueryParam("consultantuuid") String consultantuuid) {
        return budgetService.findByConsultantAndProject(projectuuid, consultantuuid);
    }

    @POST
    @Path("/{projectuuid}/budgets")
    @Transactional
    public void saveBudget(@PathParam("projectuuid") String projectuuid, Budget budget) {
        log.info("ProjectResource.saveBudget");
        log.info("projectuuid = " + projectuuid + ", budget = " + budget);
        if(!budget.getProjectuuid().equalsIgnoreCase(projectuuid)) {
            log.warn("Budget '"+budget.getProjectuuid()+"' and projectuuid '"+projectuuid+"' doesn't match for: "+budget.getUuid());
            return;
        }
        budgetService.saveBudget(budget);
    }

    @POST
    public Project save(Project project) {
        return projectAPI.save(project);
    }

    @PUT
    public void updateOne(Project project) {
        projectAPI.updateOne(project);
    }

    @DELETE
    @Path("/{uuid}")
    public void delete(@PathParam("uuid") String uuid) {
        projectAPI.delete(uuid);
    }
}