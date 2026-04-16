package dk.trustworks.intranet.aggregates.invoice;

import com.google.common.collect.Lists;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.services.ContractService;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.model.Task;
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
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
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

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus.CREATED;

@JBossLog
@ApplicationScoped
public class InvoiceGenerator {

    @Inject
    UserService userService;

    @Inject
    InvoiceService invoiceService;

    @Inject
    ContractService contractService;

    @Inject
    ClientService clientService;

    @Inject
    ProjectService projectService;

    @Inject
    TaskService taskService;

    @Inject
    InvoiceAttributionService invoiceAttributionService;

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
                    if(invoice.projectuuid.equals(work.getProjectuuid()) &&
                            invoice.getContractuuid().equals(work.getContractuuid()) && (
                            invoice.status.equals(CREATED))) {
                        numberOfInvoicesRelatedToProject++;
                        relatedInvoices.add(invoice);
                        for (InvoiceItem invoiceitem : invoice.invoiceitems) {
                            invoicedamount += (invoice.type.equals(InvoiceType.INVOICE)?
                                    (invoiceitem.hours*invoiceitem.rate):
                                    -(invoiceitem.hours*invoiceitem.rate));
                        }
                    }
                }

                List<Invoice> relatedDraftInvoices = invoices.stream().filter(invoice ->
                        invoice.projectuuid.equals(work.getProjectuuid()) &&
                                invoice.getContractuuid().equals(work.getContractuuid()) && (
                                invoice.status.equals(InvoiceStatus.DRAFT))
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

                    if (contract.getCompany() == null) {
                        log.error("Contract '" + contract.getUuid() + "' has no company assigned");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Contract has no company assigned. Please update the contract with a company before creating an invoice.")
                                .type(MediaType.TEXT_PLAIN)
                                .build();
                        throw new WebApplicationException(response);
                    }

                    String billingClientUuid = contract.getBillingClientUuid() != null
                            ? contract.getBillingClientUuid()
                            : contract.getClientuuid();
                    Client billingClient = clientService.findByUuid(billingClientUuid);
                    if (billingClient == null) {
                        log.error("Billing client '" + billingClientUuid + "' not found for contract '" + contract.getUuid() + "'");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Contract references invalid billing client. The billing client could not be found. Please update the contract with valid billing details.")
                                .type(MediaType.TEXT_PLAIN)
                                .build();
                        throw new WebApplicationException(response);
                    }

                    // Validate that essential billing client fields are present
                    String validationError = validateBillingClient(billingClient);
                    if (validationError != null) {
                        log.error("Billing client validation failed for contract '" + contract.getUuid() + "': " + validationError);
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Billing client data is incomplete: " + validationError + ". Please update the contract's billing information.")
                                .type(MediaType.TEXT_PLAIN)
                                .build();
                        throw new WebApplicationException(response);
                    }

                    if (invoice == null) {
                        invoice = new Invoice(InvoiceType.INVOICE,
                                contract.getUuid(),
                                project.getUuid(),
                                project.getName(),
                                0.0,
                                month.getYear(),
                                month.getMonthValue(),
                                billingClient.getName(),
                                billingClient.getBillingAddress(),
                                "",
                                (billingClient.getBillingZipcode() != null ? billingClient.getBillingZipcode() : "") + " " + (billingClient.getBillingCity() != null ? billingClient.getBillingCity() : ""),
                                billingClient.getEan(),
                                billingClient.getCvr(),
                                billingClient.getDefaultBillingAttention(),
                                LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).withDayOfMonth(LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).lengthOfMonth()),
                                LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).withDayOfMonth(LocalDate.now().withYear(month.getYear()).withMonth(month.getMonthValue()).lengthOfMonth()).plusMonths(1),
                                project.getCustomerreference(),
                                contract.getRefid(), contract.getContractType(), contract.getCompany(),
                                "DKK",
                                "");
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
                                0.0, nextPos, invoice.uuid);
                        invoiceItem.uuid = UUID.randomUUID().toString();
                        invoiceItemMap.put(contract.getUuid() + project.getUuid() + workFull.getUseruuid() + workFull.getTaskuuid(), invoiceItem);
                        invoice.invoiceitems.add(invoiceItem);
                        log.info("Created new invoice item: " + invoiceItem);
                    }
                    invoiceItemMap.get(contract.getUuid() + project.getUuid() + workFull.getUseruuid() + workFull.getTaskuuid()).hours += workFull.getWorkduration();
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
        // Attribution computed inside updateDraftInvoice after flush+refresh
        return created;
    }

    /**
     * Validates that the billing client contains the minimum required fields for invoice generation.
     * @param client The billing client to validate
     * @return null if valid, or an error message describing what's missing
     */
    private String validateBillingClient(Client client) {
        List<String> missingFields = new ArrayList<>();

        if (client.getName() == null || client.getName().trim().isEmpty()) {
            missingFields.add("client name");
        }
        if (client.getBillingAddress() == null || client.getBillingAddress().trim().isEmpty()) {
            missingFields.add("billing address");
        }
        if (client.getBillingZipcode() == null) {
            missingFields.add("postal code");
        }
        if (client.getBillingCity() == null || client.getBillingCity().trim().isEmpty()) {
            missingFields.add("city");
        }

        return missingFields.isEmpty() ? null : String.join(", ", missingFields);
    }
}
