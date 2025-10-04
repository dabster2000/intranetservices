package dk.trustworks.intranet.apigateway.resources;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.sender.AggregateEventSender;
import dk.trustworks.intranet.contracts.dto.ValidationReport;
import dk.trustworks.intranet.contracts.events.ModifyContractConsultantEvent;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.ContractProject;
import dk.trustworks.intranet.contracts.model.ContractTypeItem;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.contracts.services.ContractValidationService;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.KeyValueDTO;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static dk.trustworks.intranet.utils.DateUtils.dateIt;
import static dk.trustworks.intranet.utils.DateUtils.stringIt;
import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;

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
    ContractValidationService validationService;

    @Inject
    WorkService workService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    AggregateEventSender aggregateEventSender;

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
    @Path("/{contractuuid}/user/{useruuid}/work")
    public List<WorkFull> findContractWorkByUser(@PathParam("contractuuid") String contractuuid, @PathParam("useruuid") String useruuid, @QueryParam("fromdate") Optional<String> fromdate, @QueryParam("todate") Optional<String> todate) {
        return workService.findByContractAndUserByPeriod(contractuuid, useruuid, dateIt(fromdate.orElse("2014-02-01")), dateIt(todate.orElse(stringIt(LocalDate.now()))));
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
    @CacheInvalidateAll(cacheName = "employee-budgets")
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
        List<ContractConsultant> contractConsultants = contractService.getContractConsultants(contractuuid);
        contractService.delete(contractuuid);
        contractConsultants.forEach(contractConsultant -> {
            aggregateEventSender.handleEvent(new ModifyContractConsultantEvent(contractConsultant.getUseruuid(), contractConsultant));
        });
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
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void addConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid, ContractConsultant contractConsultant) {
        contractService.addConsultant(contractuuid, consultantuuid, contractConsultant);
        aggregateEventSender.handleEvent(new ModifyContractConsultantEvent(contractConsultant.getUseruuid(), contractConsultant));
    }

    @PUT
    @Path("/{contractuuid}/consultants/{consultantuuid}")
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void updateConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid, ContractConsultant contractConsultant) {
        ContractConsultant existingContractConsultant = ContractConsultant.findById(contractConsultant.getUuid());
        contractService.updateConsultant(contractConsultant);
        aggregateEventSender.handleEvent(new ModifyContractConsultantEvent(contractConsultant.getUseruuid(), existingContractConsultant));
        aggregateEventSender.handleEvent(new ModifyContractConsultantEvent(contractConsultant.getUseruuid(), contractConsultant));
    }

    @DELETE
    @Path("/{contractuuid}/consultants/{consultantuuid}")
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void removeConsultant(@PathParam("contractuuid") String contractuuid, @PathParam("consultantuuid") String consultantuuid) {
        ContractConsultant contractConsultant = ContractConsultant.findById(consultantuuid);
        contractService.removeConsultant(contractuuid, consultantuuid);
        aggregateEventSender.handleEvent(new ModifyContractConsultantEvent(contractuuid, contractConsultant));
    }

    @POST
    @Path("/{contractuuid}/contracttypeitems")
    public void addContractTypeItem(@PathParam("contractuuid") String contractuuid, ContractTypeItem contractTypeItem) {
        contractService.addContractTypeItem(contractuuid, contractTypeItem);
    }

    @PUT
    @Path("/{contractuuid}/contracttypeitems")
    public void updateContractTypeItem(@PathParam("contractuuid") String contractuuid, ContractTypeItem contractTypeItem) {
        contractService.updateContractTypeItem(contractTypeItem);
    }

    /**
     * Validate a contract before saving or activating.
     * Returns a validation report with errors and warnings.
     */
    @POST
    @Path("/validate")
    public ValidationReport validateContract(Contract contract) {
        log.debug("ContractResource.validateContract");
        return validationService.validateContractActivation(contract);
    }

    /**
     * Validate a consultant before adding or updating.
     * Returns a validation report with overlaps and conflicts.
     */
    @POST
    @Path("/{contractuuid}/consultants/validate")
    public ValidationReport validateConsultant(@PathParam("contractuuid") String contractuuid, ContractConsultant consultant) {
        log.debug("ContractResource.validateConsultant for contract: " + contractuuid);
        consultant.setContractuuid(contractuuid);
        return validationService.validateContractConsultant(consultant);
    }

    /**
     * Validate a project linkage before adding.
     * Returns a validation report with consultant conflicts.
     */
    @POST
    @Path("/{contractuuid}/projects/{projectuuid}/validate")
    public ValidationReport validateProjectLinkage(@PathParam("contractuuid") String contractuuid, @PathParam("projectuuid") String projectuuid) {
        log.debug("ContractResource.validateProjectLinkage for contract: " + contractuuid + ", project: " + projectuuid);
        ContractProject projectLink = new ContractProject(contractuuid, projectuuid);
        return validationService.validateContractProject(projectLink);
    }

    /**
     * Batch endpoint to get registered amounts for multiple contracts.
     * This is much more efficient than calling the individual endpoint multiple times.
     * @param contractUuids List of contract UUIDs to fetch amounts for
     * @return Map of contract UUID to registered amount
     */
    @POST
    @Path("/batch/registeredamounts")
    public Map<String, Double> calcRegisteredAmountsForContracts(List<String> contractUuids) {
        log.debug("ContractResource.calcRegisteredAmountsForContracts for " + contractUuids.size() + " contracts");
        if (contractUuids == null || contractUuids.isEmpty()) {
            return new HashMap<>();
        }
        return workService.findAmountsForContracts(contractUuids);
    }

    /**
     * Batch endpoint to get invoices for multiple contracts.
     * This is much more efficient than calling the individual endpoint multiple times.
     * @param contractUuids List of contract UUIDs to fetch invoices for
     * @return Map of contract UUID to list of invoices
     */
    @POST
    @Path("/batch/invoices")
    public Map<String, List<Invoice>> findInvoicesForContracts(List<String> contractUuids) {
        log.debug("ContractResource.findInvoicesForContracts for " + contractUuids.size() + " contracts");
        if (contractUuids == null || contractUuids.isEmpty()) {
            return new HashMap<>();
        }
        return invoiceService.findInvoicesForContracts(contractUuids);
    }
}
