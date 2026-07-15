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
import dk.trustworks.intranet.aggregates.invoice.model.PracticeInvoiceItemDeliverySource;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceStatus;
import dk.trustworks.intranet.aggregates.invoice.model.enums.InvoiceType;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceAttributionService;
import dk.trustworks.intranet.aggregates.invoice.services.InvoiceService;
import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
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

    @Inject
    EconomicsAgreementResolver agreements;

    @Inject
    RegisteredDeliveryEvidenceResolver registeredDeliveryEvidenceResolver;

    @Inject
    EntityManager em;

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
        Map<String, List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery>> contributionLineageByItem = new HashMap<>();

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
                Map<String, WorkFull> workByUuid = workFullList.stream().collect(Collectors.toMap(
                        WorkFull::getUuid, work -> work, (first, ignored) -> first, LinkedHashMap::new));
                Map<String, InvoiceItem> invoiceItemMap = new HashMap<>();

                Contract contract = contractService.findByUuid(contractuuid);

                List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> canonicalDelivery =
                        registeredDeliveryEvidenceResolver.resolve(
                                new RegisteredDeliveryEvidenceResolver.QueryInput(
                                        contractuuid, projectuuid, month.withDayOfMonth(1),
                                        month.withDayOfMonth(1).plusMonths(1)));

                for (RegisteredDeliveryEvidenceResolver.ResolvedDelivery delivery : canonicalDelivery) {
                    WorkFull workFull = workByUuid.get(delivery.workUuid());
                    if (workFull == null) {
                        throw new IllegalStateException("canonical delivery work is outside the invoice candidate set");
                    }
                    if (delivery.normalizedDuration() == null || delivery.normalizedDuration().signum() <= 0) continue;
                    Task task = taskService.findByUuid(delivery.taskUuid());
                    Project project = projectService.findByUuid(task.getProjectuuid());

                    //if (!contractService.findContractByWork(workFull, ContractStatus.TIME, ContractStatus.SIGNED, ContractStatus.CLOSED).getUuid().equals(contract.getUuid()))
                    if(workFull.getContractuuid()==null || workFull.getContractuuid().equals(""))
                        continue;

                    User user = userService.findById(delivery.registrantUuid(), true);

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
                        invoice = buildInitialInvoice(contract, project, billingClient, YearMonth.of(month.getYear(), month.getMonthValue()));
                        log.info("Created new invoice: " + invoice);
                    }

                    if (!delivery.usableForContribution() || delivery.normalizedRate() == null
                            || delivery.normalizedRate().signum() <= 0) {
                        log.error("Rate could not be found for user (link: " + user.getUuid() + ") and task (link: " + workFull.getTaskuuid() + ")");
                        Response response = Response
                                .status(Response.Status.BAD_REQUEST)
                                .entity("Rate could not be found for " + user.getFullname() + " on the project '"+project.getName()+"' and task '" + task.getName() + "'")
                                .type(MediaType.TEXT_PLAIN) // or MediaType.APPLICATION_JSON for JSON response
                                .build();
                        throw new WebApplicationException(response);
                    }
                    String itemKey = contract.getUuid() + project.getUuid()
                            + delivery.registrantUuid() + delivery.taskUuid()
                            + delivery.normalizedRate().toPlainString();
                    if (!invoiceItemMap.containsKey(itemKey)) {
                        String invoiceItemName = (workFull.getName()!=null && !workFull.getName().isEmpty())?workFull.getName():user.getFullname();
                        if(!Objects.equals(delivery.effectiveConsultantUuid(), delivery.registrantUuid())) {
                            User workAsUser = userService.findById(delivery.effectiveConsultantUuid(), true);
                            invoiceItemName = user.getFullname() + " (helped " + workAsUser.getFullname() + ")";
                        }
                        int nextPos = invoice.getInvoiceitems().size() + 1;
                        InvoiceItem invoiceItem = new InvoiceItem(user.getUuid(), invoiceItemName,
                                task.getName(),
                                delivery.normalizedRate().doubleValue(),
                                0.0, nextPos, invoice.uuid);
                        invoiceItem.uuid = UUID.randomUUID().toString();
                        invoiceItemMap.put(itemKey, invoiceItem);
                        invoice.invoiceitems.add(invoiceItem);
                        log.info("Created new invoice item: " + invoiceItem);
                    }
                    InvoiceItem groupedItem = invoiceItemMap.get(itemKey);
                    groupedItem.hours = BigDecimal.valueOf(groupedItem.hours)
                            .add(delivery.normalizedDuration()).doubleValue();
                    contributionLineageByItem.computeIfAbsent(groupedItem.getUuid(), ignored -> new ArrayList<>())
                            .add(delivery);
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
        persistContributionDeliveryLineage(contributionLineageByItem);
        em.createNativeQuery("CALL sp_mark_practice_revenue_source_changed('INVOICE_ATTRIBUTION', :month)")
                .setParameter("month", month.withDayOfMonth(1))
                .executeUpdate();
        // Attribution computed inside updateDraftInvoice after flush+refresh
        return created;
    }

    /**
     * Persists contribution-only Work As lineage for future generated items. This does not alter
     * shared invoice attribution; it gives the Practices materializer immutable, canonical work
     * UUID evidence and never accepts a recipient from an invoice client payload.
     */
    void persistContributionDeliveryLineage(
            Map<String, List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery>> lineageByItem) {
        for (Map.Entry<String, List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery>> entry : lineageByItem.entrySet()) {
            Map<String, LineageSeed> uniqueSeeds = new TreeMap<>();
            for (RegisteredDeliveryEvidenceResolver.ResolvedDelivery delivery : entry.getValue()) {
                LineageSeed seed = lineageSeed(entry.getKey(), delivery);
                LineageSeed prior = uniqueSeeds.putIfAbsent(seed.workUuid(), seed);
                if (prior != null && !prior.equals(seed)) {
                    throw new IllegalStateException("ambiguous generated delivery lineage for work " + seed.workUuid());
                }
            }
            List<LineageSeed> seeds = List.copyOf(uniqueSeeds.values());
            String distributionFingerprint = fingerprint(seeds.stream()
                    .map(LineageSeed::rowFingerprint)
                    .collect(Collectors.joining("|")));
            InvoiceItem persistedItem = InvoiceItem.findById(entry.getKey());
            if (persistedItem == null) {
                throw new IllegalStateException("generated invoice item disappeared before delivery-lineage persistence");
            }
            String itemFingerprint = fingerprint(String.join("|",
                    entry.getKey(),
                    normalizeDeliveryOperand(persistedItem.getHours()).toPlainString(),
                    normalizeDeliveryOperand(persistedItem.getRate()).toPlainString(),
                    persistedItem.getOrigin() == null ? "" : persistedItem.getOrigin().name(),
                    persistedItem.getRuleId() == null ? "" : persistedItem.getRuleId(),
                    distributionFingerprint));
            for (LineageSeed seed : seeds) {
                PracticeInvoiceItemDeliverySource row = new PracticeInvoiceItemDeliverySource();
                row.invoiceItemUuid = entry.getKey();
                row.workUuid = seed.workUuid();
                row.registrantUuid = seed.registrantUuid();
                row.effectiveConsultantUuid = seed.effectiveConsultantUuid();
                row.deliveryDate = seed.deliveryDate();
                row.taskUuid = seed.taskUuid();
                row.projectUuid = seed.projectUuid();
                row.contractUuid = seed.contractUuid();
                row.contractProjectUuid = seed.contractProjectUuid();
                row.contractConsultantUuid = seed.contractConsultantUuid();
                row.normalizedDuration = seed.duration();
                row.normalizedRate = seed.rate();
                row.deliveryValue = seed.value();
                row.rateResolutionStatus = seed.rateStatus();
                row.contributionAlgorithmVersion = "PRACTICE_DELIVERY_LINEAGE_V1";
                row.itemFingerprint = itemFingerprint;
                row.distributionFingerprint = distributionFingerprint;
                PracticeInvoiceItemDeliverySource.persist(row);
            }
        }
    }

    private static LineageSeed lineageSeed(String itemUuid,
                                           RegisteredDeliveryEvidenceResolver.ResolvedDelivery delivery) {
        BigDecimal duration = delivery.normalizedDuration();
        BigDecimal rate = delivery.normalizedRate();
        String status = delivery.rateResolutionStatus().name();
        BigDecimal value = delivery.deliveryValue();
        String canonical = String.join("|",
                itemUuid, delivery.workUuid(), delivery.registrantUuid(), delivery.effectiveConsultantUuid(),
                String.valueOf(delivery.deliveryDate()), String.valueOf(delivery.taskUuid()),
                String.valueOf(delivery.projectUuid()), String.valueOf(delivery.contractUuid()),
                String.valueOf(delivery.contractProjectUuid()), String.valueOf(delivery.contractConsultantUuid()),
                duration.toPlainString(), rate.toPlainString(), status);
        return new LineageSeed(delivery.workUuid(), delivery.registrantUuid(), delivery.effectiveConsultantUuid(),
                delivery.deliveryDate(), delivery.taskUuid(), delivery.projectUuid(), delivery.contractUuid(),
                delivery.contractProjectUuid(), delivery.contractConsultantUuid(), duration, rate,
                value, status, fingerprint(canonical));
    }

    private static BigDecimal normalizeDeliveryOperand(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("non-finite delivery evidence");
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }

    private static String fingerprint(String canonical) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    record LineageSeed(String workUuid, String registrantUuid, String effectiveConsultantUuid,
                       LocalDate deliveryDate, String taskUuid, String projectUuid, String contractUuid,
                       String contractProjectUuid, String contractConsultantUuid,
                       BigDecimal duration, BigDecimal rate, BigDecimal value, String rateStatus,
                       String rowFingerprint) {}

    /**
     * Constructs the initial {@link Invoice} shell for a new draft: populates all
     * billing-address fields, sets {@code currency} from the billing client, and
     * resolves the VAT rate from the configured VAT zone mapping.
     *
     * <p>Extracted as a package-private helper so unit tests can exercise the
     * currency + VAT logic without spinning up the full work-item loop.
     */
    Invoice buildInitialInvoice(Contract contract, Project project, Client billingClient, YearMonth month) {
        // Invoice date defaults to today. The due date is a placeholder —
        // e-conomics calculates the real due date from the customer's payment
        // term (termOfPaymentNumber) and returns it in the draft response,
        // which the sync code persists back onto the Invoice entity.
        LocalDate invoiceDate = LocalDate.now();

        Invoice invoice = new Invoice(InvoiceType.INVOICE,
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
                contract.getBillingAttention() != null
                        ? contract.getBillingAttention()
                        : billingClient.getDefaultBillingAttention(),
                invoiceDate,
                invoiceDate.plusMonths(1),
                project.getCustomerreference(),
                contract.getBillingRef(), contract.getContractType(), contract.getCompany(),
                billingClient.getCurrency(),
                "");

        EconomicsAgreementResolver.VatZoneDetails zone = agreements.vatZoneDetailsFor(
                billingClient.getCurrency(), contract.getCompany().getUuid());
        invoice.setVat(zone.vatRatePercent().doubleValue());

        return invoice;
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
