package dk.trustworks.intranet.aggregates.invoice;

import com.google.common.collect.Lists;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Clientdata;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
import dk.trustworks.intranet.dao.crm.services.ClientDataService;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.crm.services.TaskService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.ProjectSummary;
import dk.trustworks.intranet.dto.enums.ProjectSummaryType;
import dk.trustworks.intranet.exceptions.InconsistantDataException;
import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.aggregates.invoice.model.InvoiceItem;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.FinanceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.ProcessingState;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.LifecycleStatus.CREATED;

@JBossLog
@ApplicationScoped
public class InvoiceGenerator {

    @Inject
    UserService userService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    ClientDataService clientdataAPI;

    @Inject
    ContractService contractService;

    @Inject
    ClientService clientService;

    @Inject
    ProjectService projectService;

    @Inject
    TaskService taskService;

    @Inject
    WorkService workService;

    @Transactional
    public List<ProjectSummary> loadProjectSummaryByYearAndMonth(LocalDate month) {
        log.debug("InvoiceController.loadProjectSummaryByYearAndMonth");
        log.debug("month = " + month);
        log.debug("LOAD findByYearAndMonth");
        List<WorkFull> workResources = workService.findByYearAndMonth(month);
        log.debug("workResources.size() = " + workResources.size());

        Collection<Invoice> invoices = invoiceService.findInvoicesForSingleMonth(month);
        log.debug("invoices.size() = " + invoices.size());

        Map<String, ProjectSummary> projectSummaryMap = new HashMap<>();

        for (WorkFull work : workResources) {
            Project project;
            Client client;
            if(work.getProjectuuid()==null || work.getClientuuid()==null) {
                project = projectService.findByUuid(taskService.findByUuid(work.getTaskuuid()).getProjectuuid());
                client = clientService.findByUuid(project.getClientuuid());
            } else {
                project = projectService.findByUuid(work.getProjectuuid());
                client = clientService.findByUuid(work.getClientuuid());
            }
            if(!(work.getWorkduration()>0)) continue;

            double invoicedamount = 0.0;

            if(!projectSummaryMap.containsKey(work.getContractuuid() + work.getProjectuuid())) {
                int numberOfInvoicesRelatedToProject = 0;

                List<Invoice> relatedInvoices = new ArrayList<>();
                for (Invoice invoice : invoices) {
                    if(invoice.getProjectuuid().equals(work.getProjectuuid()) &&
                            invoice.getContractuuid().equals(work.getContractuuid()) && (
                            invoice.getLifecycleStatus().equals(CREATED)
                                    || invoice.getLifecycleStatus().equals(LifecycleStatus.SUBMITTED)
                                    || invoice.getType().equals(InvoiceType.CREDIT_NOTE))) {
                        numberOfInvoicesRelatedToProject++;
                        relatedInvoices.add(invoice);
                        for (InvoiceItem invoiceitem : invoice.getInvoiceitems()) {
                            invoicedamount += (invoice.getType().equals(InvoiceType.INVOICE)?
                                    (invoiceitem.getHours()*invoiceitem.getRate()):
                                    -(invoiceitem.getHours()*invoiceitem.getRate()));
                        }
                    }
                }

                List<Invoice> relatedDraftInvoices = invoices.stream().filter(invoice ->
                        invoice.getProjectuuid().equals(work.getProjectuuid()) &&
                                invoice.getContractuuid().equals(work.getContractuuid()) && (
                                invoice.getLifecycleStatus().equals(LifecycleStatus.DRAFT))
                ).collect(Collectors.toList());

                ProjectSummary projectSummary = new ProjectSummary(
                        work.getContractuuid(), work.getProjectuuid(),
                        project.getName(),
                        client,
                        client.getName(),
                        project.getCustomerreference(),
                        0,
                        invoicedamount, numberOfInvoicesRelatedToProject, ProjectSummaryType.CONTRACT);
                projectSummary.setInvoiceList(relatedInvoices);
                projectSummary.setDraftInvoiceList(relatedDraftInvoices);
                projectSummaryMap.put(work.getContractuuid() + work.getProjectuuid(), projectSummary);
            }
            if(work.getUseruuid()==null) log.debug("work u = " + work);
            if(work.getTaskuuid()==null) log.debug("work t = " + work);

            ProjectSummary projectSummary = projectSummaryMap.get(work.getContractuuid() + work.getProjectuuid());
            projectSummary.addAmount(work.getWorkduration() * work.getRate());
        }
        return Lists.newArrayList(projectSummaryMap.values());
    }

    @Transactional
    public Invoice createDraftInvoiceFromProject(String contractuuid, String projectuuid, LocalDate month, String type) {
        log.infof("createDraftInvoiceFromProject contract=%s project=%s month=%s type=%s",
                contractuuid, projectuuid, month, type);
        Invoice invoice = null;

        if (!type.equals(ProjectSummaryType.RECEIPT.toString())) {
            if (type.equals(ProjectSummaryType.CONTRACT.toString())) {
                Optional<WorkFull> anyFaultyWork = workService.findByYearAndMonthAndProject(month.getYear(), month.getMonthValue(), projectuuid).stream()
                        .filter(w -> w.getWorkduration() > 0)
                        .filter(w -> w.getContractuuid() == null || w.getProjectuuid() == null).findAny();
                if(anyFaultyWork.isPresent()) {
                    WorkFull work = anyFaultyWork.get();
                    String username = userService.findById(work.getUseruuid(), true).getUsername();
                    String clientName = clientService.findByUuid(work.getClientuuid()).getName();
                    throw new InconsistantDataException("Work done by " + username + " at " + clientName + " on " + DateUtils.stringIt(work.getRegistered(), "d. MMM yyyy") + " is missing contractuuid or projectuuid, most likely because there is no contract");
                }

                List<WorkFull> workFullList = workService.findByYearAndMonthAndProject(month.getYear(), month.getMonthValue(), projectuuid).stream()
                        .filter(w -> w.getWorkduration() > 0)
                        .filter(w -> w.getContractuuid().equals(contractuuid) && w.getProjectuuid().equals(projectuuid)).toList();
                Map<String, InvoiceItem> invoiceItemMap = new HashMap<>();

                Contract contract = contractService.findByUuid(contractuuid);

                for (WorkFull workFull : workFullList) {
                    if (workFull.getWorkduration() == 0) continue;
                    Task task = taskService.findByUuid(workFull.getTaskuuid());
                    Project project = projectService.findByUuid(task.getProjectuuid());

                    //if (!contractService.findContractByWork(workFull, ContractStatus.TIME, ContractStatus.SIGNED, ContractStatus.CLOSED).getUuid().equals(contract.getUuid()))
                    if(workFull.getContractuuid()==null || workFull.getContractuuid().equals(""))
                        continue;

                    User user = userService.findById(workFull.getUseruuid(), true);

                    if(contract.getClientdatauuid() == null || contract.getClientdatauuid().isEmpty()) {
                        log.warn("No Client Data attached to contract");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("No client contact information on the contract")
                                .type(MediaType.TEXT_PLAIN) // or MediaType.APPLICATION_JSON for JSON response
                                .build();
                        throw new WebApplicationException(response);
                        //throw new BadRequestException("No client contact information on the contract");
                    }
                    Clientdata clientdata = clientdataAPI.findByUuid(contract.getClientdatauuid());
                    if (clientdata == null) {
                        log.error("Client data UUID '" + contract.getClientdatauuid() + "' not found in database for contract '" + contract.getUuid() + "'");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Contract references invalid client contact data. The client contact information could not be found. Please update the contract with valid client contact details.")
                                .type(MediaType.TEXT_PLAIN)
                                .build();
                        throw new WebApplicationException(response);
                    }

                    // Validate that essential client data fields are present
                    String validationError = validateClientdata(clientdata);
                    if (validationError != null) {
                        log.error("Client data validation failed for contract '" + contract.getUuid() + "': " + validationError);
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Client contact data is incomplete: " + validationError + ". Please update the contract's client contact information.")
                                .type(MediaType.TEXT_PLAIN)
                                .build();
                        throw new WebApplicationException(response);
                    }

                    if (invoice == null) {
                        invoice = new Invoice();
                        invoice.setType(InvoiceType.INVOICE);
                        invoice.setLifecycleStatus(LifecycleStatus.DRAFT);
                        invoice.setFinanceStatus(FinanceStatus.NONE);
                        invoice.setProcessingState(ProcessingState.IDLE);
                        invoice.setContractuuid(contract.getUuid());
                        invoice.setProjectuuid(project.getUuid());
                        invoice.setIssuerCompanyuuid(contract.getCompany().getUuid());

                        // Bill-to address fields
                        invoice.setBillToName(clientdata.getClientname());
                        invoice.setBillToLine1(clientdata.getStreetnamenumber());
                        invoice.setBillToLine2(clientdata.getOtheraddressinfo());
                        invoice.setBillToAttn(clientdata.getContactperson());

                        // Split zip and city
                        String zipCity = clientdata.getPostalcode() + " " + clientdata.getCity();
                        String[] zipCityParts = zipCity.split(" ", 2);
                        invoice.setBillToZip(zipCityParts[0]);
                        invoice.setBillToCity(zipCityParts.length > 1 ? zipCityParts[1] : "");

                        invoice.setBillToEan(clientdata.getEan());
                        invoice.setBillToCvr(clientdata.getCvr());

                        // Dates
                        LocalDate lastDayOfMonth = LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).withDayOfMonth(LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).lengthOfMonth());
                        invoice.setInvoicedate(lastDayOfMonth);
                        invoice.setDuedate(lastDayOfMonth.plusMonths(1));

                        // Currency and money fields
                        invoice.setCurrency("DKK");
                        invoice.setVatPct(new BigDecimal("25.00"));
                        invoice.setHeaderDiscountPct(BigDecimal.ZERO);

                        // Set work period from selected month (for grouping/filtering)
                        invoice.setWorkYear(month.getYear());
                        invoice.setWorkMonth(month.getMonthValue());

                        log.info("Created new invoice: " + invoice);
                    }

                    if (workFull.getRate() == 0) {
                        log.error("Rate could not be found for user (link: " + user.getUuid() + ") and task (link: " + workFull.getTaskuuid() + ")");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Rate could not be found for " + user.getFullname() + " on the project '"+project.getName()+"' and task '" + task.getName() + "'")
                                .type(MediaType.TEXT_PLAIN) // or MediaType.APPLICATION_JSON for JSON response
                                .build();
                        throw new WebApplicationException(response);
                    }
                    if (!invoiceItemMap.containsKey(contract.getUuid() + project.getUuid() + workFull.getUseruuid() + workFull.getTaskuuid())) {
                        String invoiceItemName = (workFull.getName()!=null && !workFull.getName().isEmpty())?workFull.getName():user.getFullname();
                        if(workFull.getWorkas()!=null && !workFull.getWorkas().isEmpty()) {
                            //User workAsUser = userService.findUserByUuid(workFull.getUseruuid(), true);
                            User workAsUser = userService.findById(workFull.getWorkas(), true);
                            invoiceItemName = user.getFullname() + " (helped " + workAsUser.getFullname() + ")";
                        }
                        int nextPos = invoice.getInvoiceitems().size() + 1;
                        InvoiceItem invoiceItem = new InvoiceItem(user.getUuid(), invoiceItemName,
                                task.getName(),
                                workFull.getRate(),
                                0.0, nextPos, invoice.getUuid());
                        invoiceItem.setUuid(UUID.randomUUID().toString());
                        invoiceItemMap.put(contract.getUuid() + project.getUuid() + workFull.getUseruuid() + workFull.getTaskuuid(), invoiceItem);
                        invoice.getInvoiceitems().add(invoiceItem);
                        log.info("Created new invoice item: " + invoiceItem);
                    }
                    InvoiceItem existingItem = invoiceItemMap.get(contract.getUuid() + project.getUuid() + workFull.getUseruuid() + workFull.getTaskuuid());
                    existingItem.setHours(existingItem.getHours() + workFull.getWorkduration());
                }
            }
        }
        log.info("draftInvoice: "+invoice);

        if(invoice == null) {
            log.warn("No work found for given parameters; unable to create draft invoice");
            Response response = Response
                    .status(Response.Status.NOT_FOUND)
                    .entity("No work found for specified project and month")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
            throw new WebApplicationException(response);
        }

        if(invoice.getInvoiceitems().isEmpty()) {
            log.warn("Draft invoice has no line items");
            Response response = Response
                    .status(Response.Status.BAD_REQUEST)
                    .entity("No invoice items found for specified project and month")
                    .type(MediaType.TEXT_PLAIN)
                    .build();
            throw new WebApplicationException(response);
        }

        Invoice created = invoiceService.createDraftInvoice(invoice);
        log.info("Persisted draft invoice: " + created.getUuid());
        // Invoke pricing to add CALCULATED lines immediately
        Invoice priced = invoiceService.updateDraftInvoice(created);
        log.info("Priced draft invoice: " + priced.getUuid());
        return created;
    }

    /**
     * Validates that the client data contains the minimum required fields for invoice generation.
     * @param clientdata The client data to validate
     * @return null if valid, or an error message describing what's missing
     */
    private String validateClientdata(Clientdata clientdata) {
        List<String> missingFields = new ArrayList<>();

        if (clientdata.getClientname() == null || clientdata.getClientname().trim().isEmpty()) {
            missingFields.add("client name");
        }
        if (clientdata.getStreetnamenumber() == null || clientdata.getStreetnamenumber().trim().isEmpty()) {
            missingFields.add("street address");
        }
        if (clientdata.getPostalcode() == null) {
            missingFields.add("postal code");
        }
        if (clientdata.getCity() == null || clientdata.getCity().trim().isEmpty()) {
            missingFields.add("city");
        }

        if (!missingFields.isEmpty()) {
            return "missing " + String.join(", ", missingFields);
        }

        return null; // Valid
    }
}
