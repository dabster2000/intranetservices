package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.KeyValueDTO;
import dk.trustworks.intranet.invoiceservice.model.Invoice;
import dk.trustworks.intranet.invoiceservice.services.InvoiceService;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import javax.annotation.security.RolesAllowed;
import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.*;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

@Tag(name = "contract")
@Path("/contracts")
@JBossLog
@RequestScoped
@Produces(APPLICATION_JSON)
@Consumes(APPLICATION_JSON)
@SecurityRequirement(name = "jwt")
@RolesAllowed({"SYSTEM"})
public class ContractResource {

    @Inject
    ContractService contractService;

    @Inject
    WorkService workService;

    @Inject
    InvoiceService invoiceService;

    @GET
    @Path("/{contractuuid}")
    public Contract findByUuid(@PathParam("contractuuid") String contractuuid) {
        return contractService.findByUuid(contractuuid);
    }

    @GET
    public List<Contract> listAll(@QueryParam("fromdate") Optional<String> fromdate, @QueryParam("todate") Optional<String> todate) {
        log.debug("ContractResource.listAll");
        log.debug("fromdate = " + fromdate + ", todate = " + todate);
        return contractService.findByPeriod(dateIt(fromdate.orElse("2014-02-01")), dateIt(todate.orElse(stringIt(LocalDate.now()))));
    }

    /*
    @GET
    @Path("/search/findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn")
    public List<Contract> listAll(@QueryParam("fromdate") Optional<String> fromdate, @QueryParam("todate") Optional<String> todate, @QueryParam("statuslist") String statuslist) {
        log.debug("ContractResource.listAll");
        log.debug("fromdate = " + fromdate + ", todate = " + todate + ", statuslist = " + statuslist);
        List<ContractStatus> contractStatuses = Arrays.stream(statuslist.split(","))
                .map(ContractStatus::valueOf)
                .collect(Collectors.toList());
        return contractService.findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn(dateIt(fromdate.orElse("2014-02-01")), dateIt(todate.orElse(stringIt(LocalDate.now()))), contractStatuses);
    }
     */

    @GET
    @Path("/{contractuuid}/registeredamount")
    public KeyValueDTO calcRegistreredAmountOnContract(@PathParam("contractuuid") String contractUUID) {
        return new KeyValueDTO("amount", workService.findAmountUsedByContract(contractUUID)+"");
    }

    @GET
    @Path("/{contractuuid}/projects")
    public List<Project> findContractProjects(@PathParam("contractuuid") String contractuuid) {
        return contractService.findProjectsByContract(contractuuid);
    }

    @GET
    @Path("/{contractuuid}/invoices")
    public List<Invoice> findContractInvoices(@PathParam("contractuuid") String contractuuid) {
        return invoiceService.findContractInvoices(contractuuid);
    }

    @GET
    @Path("/{contractuuid}/work")
    public List<WorkFull> findContractWork(@PathParam("contractuuid") String contractuuid) {
        return workService.findByContract(contractuuid);
    }

    @GET
    @Path("/{contractuuid}/work/monthly")
    public Map<KeyValueDTO, List<DateValueDTO>> findContractWorkMonthly(@PathParam("contractuuid") String contractuuid) {
        List<WorkFull> workList = workService.findByContract(contractuuid);
        return null;
    }

    @GET
    @Path("/search/findRateByProjectuuidAndUseruuidAndDate")
    public KeyValueDTO findRateByProjectuuidAndUseruuidAndDate(@QueryParam("projectuuid") String projectuuid, @QueryParam("useruuid") String useruuid, @QueryParam("date") String date) {
        return new KeyValueDTO("rate", contractService.findRateByProjectuuidAndUseruuidAndDate(projectuuid, useruuid, date).getRate()+"");
    }

    @POST
    public void save(Contract contract) {
        contractService.save(contract);
    }

    @POST
    @Path("/{contractuuid}/extend")
    public Contract extendContract(@PathParam("contractuuid") String contractuuid) {
        log.info("ContractResource.extendContract");
        log.info("contractuuid = " + contractuuid);
        return contractService.extendContract(contractuuid);
    }

    @PUT
    public void updateContract(Contract contract) {
        contractService.update(contract);
    }

    @DELETE
    @Path("/{contractuuid}")
    public void deleteContract(@PathParam("contractuuid") String contractuuid) {
        log.info("ContractResource.deleteContract");
        log.info("contractuuid = " + contractuuid);
        contractService.delete(contractuuid);
    }

    @POST
    @Path("/{contractuuid}/projects/{projectuuid}")
    public void addProject(@PathParam("contractuuid") String contractuuid, @PathParam("projectuuid") String projectuuid) {
        contractService.addProject(contractuuid, projectuuid);
    }

    @DELETE
    @Path("/{contractuuid}/projects/{projectuuid}")
    public void removeProject(@PathParam("contractuuid") String contractuuid, @PathParam("projectuuid") String projectuuid) {
        contractService.removeProject(contractuuid, projectuuid);
    }

    @POST
    @Path("/{contractuuid}/consultants/{consultantuuid}")
    public void addConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid, ContractConsultant contractConsultant) {
        contractService.addConsultant(contractuuid, consultantuuid, contractConsultant);
    }

    @PUT
    @Path("/{contractuuid}/consultants/{consultantuuid}")
    public void updateConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid, ContractConsultant contractConsultant) {
        contractService.updateConsultant(contractuuid, consultantuuid, contractConsultant);
    }

    @DELETE
    @Path("/{contractuuid}/consultants/{consultantuuid}")
    public void removeConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid) {
        contractService.removeConsultant(contractuuid, consultantuuid);
    }
}
