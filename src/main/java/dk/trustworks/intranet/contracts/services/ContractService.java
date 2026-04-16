package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.dto.ValidationReport;
import dk.trustworks.intranet.contracts.model.*;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.DateValueDTO;
import dk.trustworks.intranet.dto.ProjectUserDateDTO;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.hibernate.orm.panache.Panache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.PathParam;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@JBossLog
@ApplicationScoped
public class ContractService {

    @Inject
    ProjectService projectService;

    @Inject
    ContractValidationService validationService;

    @Inject
    ContractTypeValidationService contractTypeValidationService;

    @Inject
    ClientActivityLogService activityLogService;

    @Inject
    RequestHeaderHolder requestHeaderHolder;

    @Inject
    EntityManager em;

    public List<Contract> findAll() {
        return Contract.listAll();
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public List<Contract> findByDayReadOnly(LocalDate day) {
        final EntityManager em = Panache.getEntityManager();

        // Avoid autoflush on queries from this EM
        em.setFlushMode(FlushModeType.COMMIT);

        final String sql =
                "SELECT DISTINCT c.* " +
                        "FROM contracts c " +
                        "JOIN contract_consultants cc ON cc.contractuuid = c.uuid " +
                        "WHERE cc.activefrom <= :day " +
                        "  AND cc.activeto   >= :day " +
                        "  AND c.status IN ('TIME','SIGNED','CLOSED','BUDGET')";

        Query q = em.createNativeQuery(sql, Contract.class);
        q.setParameter("day", day);

        // Tell Hibernate this is read-only; skip dirty checking
        q.setHint(org.hibernate.jpa.HibernateHints.HINT_READ_ONLY, Boolean.TRUE);

        // Optional (Hibernate-specific): further enforce read-only + no flushing
        try {
            org.hibernate.query.NativeQuery<?> hq = q.unwrap(org.hibernate.query.NativeQuery.class);
            hq.setReadOnly(true);
            hq.setHibernateFlushMode(org.hibernate.FlushMode.MANUAL);
        } catch (IllegalStateException ignored) {
            // Unwrap not available – fine to proceed with the hint alone
        }

        @SuppressWarnings("unchecked")
        List<Contract> results = (List<Contract>) q.getResultList();

        // Detach to avoid accidental writes later
        results.forEach(em::detach);
        return results;
    }

    public List<Contract> findByPeriod(LocalDate fromdate, LocalDate todate) {
        Query query = em.createNativeQuery("SELECT " +
                "c.* " +
                "FROM " +
                "    contracts c " +
                "INNER JOIN " +
                "    ( " +
                "        SELECT " +
                "            contractuuid, " +
                "            MIN(activefrom) AS min_activefrom, " +
                "            MAX(activeto) AS max_activeto " +
                "        FROM " +
                "            contract_consultants " +
                "        GROUP BY " +
                "            contractuuid " +
                "    ) AS contract_period ON c.uuid = contract_period.contractuuid " +
                "WHERE " +
                "    (contract_period.min_activefrom <= :fromdate AND contract_period.max_activeto >= :fromdate) " +
                "OR " +
                "    (contract_period.min_activefrom <= :todate AND contract_period.max_activeto >= :todate)", Contract.class);
        query.setParameter("fromdate", fromdate);
        query.setParameter("todate", todate);
        List<Contract> contractList = query.getResultList();
        //List<Contract> contractList = Contract.find("(activeFrom <= ?1 AND activeTo >= ?1) OR (activeFrom <= ?2 AND activeTo >= ?2)", fromdate, todate).list();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public Contract findByUuid(@PathParam("contractuuid") String contractuuid) {
        return Contract.findById(contractuuid);
    }

    public Optional<Contract> findActiveContractByClientAndUserAndDate(String clientUuid, String userUuid, LocalDate date) {
        String sql = "SELECT c.* FROM contracts c " +
                "JOIN contract_consultants cc ON c.uuid = cc.contractuuid " +
                "WHERE c.clientuuid = :clientUuid " +
                "AND cc.useruuid = :userUuid " +
                "AND cc.activefrom <= :date " +
                "AND (cc.activeto IS NULL OR cc.activeto >= :date) " +
                "AND c.status IN ('TIME','SIGNED','CLOSED') " +
                "AND cc.hours > 0";
        Query query = em.createNativeQuery(sql, Contract.class);
        query.setParameter("clientUuid", clientUuid);
        query.setParameter("userUuid", userUuid);
        query.setParameter("date", date);
        try {
            @SuppressWarnings("unchecked")
            List<Contract> results = query.getResultList();
            return results.stream().findFirst();
        } catch (Exception e) {
            log.warnf("Failed to find contract for client=%s user=%s date=%s: %s", clientUuid, userUuid, date, e.getMessage());
            return Optional.empty();
        }
    }

    public List<Contract> findTimeActiveConsultantContracts(String useruuid, LocalDate activeon) {
        String sql = "select c.* from contracts c " +
                "right join contract_consultants cc on c.uuid = cc.contractuuid " +
                "where cc.activefrom <= :activeon and cc.activeto >= :activeon and cc.useruuid like :useruuid " +
                "and c.status in ('TIME','SIGNED','CLOSED')";
        Query query = em.createNativeQuery(sql, Contract.class);
        query.setParameter("activeon", activeon);
        query.setParameter("useruuid", useruuid);
        return query.getResultList();
    }

    public List<Project> findProjectsByContract(String contractuuid) {
        Set<ContractProject> contractProjects = getContractProjects(contractuuid);
        return contractProjects.stream()
                .filter(cp -> {
                    if (cp.getProjectuuid() == null) {
                        log.warnf("Found ContractProject with null projectuuid: contract=%s, uuid=%s",
                                contractuuid, cp.getUuid());
                        return false;
                    }
                    return true;
                })
                .map(cp -> projectService.findByUuid(cp.getProjectuuid()))
                .filter(project -> project != null)
                .collect(Collectors.toList());
    }

    public Set<ContractProject> getContractProjects(String contractuuid) {
        return ContractProject.find("contractuuid like ?1", contractuuid).list().stream().map(panacheEntityBase -> (ContractProject) panacheEntityBase).collect(Collectors.toSet());
    }

    public List<ContractConsultant> getContractConsultants(String constractuuid) {
        return ContractConsultant.find("contractuuid like ?1", constractuuid).list();
    }

    public List<Contract> getContractsByDate(List<Contract> contracts, User user, LocalDate date) {
        return contracts.stream()
                .filter(contract ->
                        (
                                contract.getStatus().equals(ContractStatus.CLOSED) ||
                                        contract.getStatus().equals(ContractStatus.TIME) ||
                                        contract.getStatus().equals(ContractStatus.SIGNED) ||
                                        contract.getStatus().equals(ContractStatus.BUDGET)
                        ) && contract.findByUserAndDate(user, date)!=null)
                .collect(Collectors.toList());
    }

    public List<Contract> findByDay(LocalDate testDay) {
        Query query = em.createNativeQuery("SELECT " +
                "c.* " +
                "FROM " +
                "    contracts c " +
                "INNER JOIN " +
                "    ( " +
                "        SELECT " +
                "            contractuuid, " +
                "            MIN(activefrom) AS min_activefrom, " +
                "            MAX(activeto) AS max_activeto " +
                "        FROM " +
                "            contract_consultants " +
                "        GROUP BY " +
                "            contractuuid " +
                "    ) AS contract_period ON c.uuid = contract_period.contractuuid " +
                "WHERE (contract_period.min_activefrom <= :testDay AND contract_period.max_activeto >= :testDay)", Contract.class);
        query.setParameter("testDay", testDay);
        List<Contract> contractList = query.getResultList();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public ProjectUserDateDTO findRateByProjectuuidAndUseruuidAndDate(String projectuuid, String useruuid, String date) {
        return addContractAndRate(new ProjectUserDateDTO(UUID.randomUUID().toString(), projectuuid, useruuid, date));
    }


    public List<Contract> findByClientuuid(String clientuuid) {
        return Contract.find("clientuuid = ?1", clientuuid).list();
    }

    public List<Contract> findByProjectuuid(String projectuuid) {
        List<ContractProject> contractProject = ContractProject.find("projectuuid LIKE ?1", projectuuid).list();
        return contractProject.stream()
                .filter(cp -> {
                    if (cp.getContractuuid() == null) {
                        log.warnf("Found ContractProject with null contractuuid: project=%s, uuid=%s",
                                projectuuid, cp.getUuid());
                        return false;
                    }
                    return true;
                })
                .map(cp -> (Contract) Contract.findById(cp.getContractuuid()))
                .filter(contract -> contract != null)
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public Contract save(Contract contract) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.debugf("Saving contract uuid=%s, client=%s, status=%s, user=%s",
                contract.getUuid(), contract.getClientuuid(), contract.getStatus(), userUuid);

        if (contract.getCompany() == null) {
            throw new jakarta.ws.rs.BadRequestException("Company is required when creating a contract");
        }

        // Validate contract type against creation date
        if (contract.getContractType() != null) {
            LocalDate createdDate = contract.getCreated() != null
                    ? contract.getCreated().toLocalDate()
                    : LocalDate.now();
            if (!contractTypeValidationService.isValidContractType(contract.getContractType(), createdDate)) {
                String errorMessage = contractTypeValidationService.getValidationErrorMessage(contract.getContractType());
                log.warnf("Invalid contract type for contract uuid=%s: %s, user=%s", contract.getUuid(), errorMessage, userUuid);
                throw new jakarta.ws.rs.BadRequestException(errorMessage);
            }
        }

        // Validate the contract if it's being activated
        if (contract.getStatus() == ContractStatus.BUDGET ||
            contract.getStatus() == ContractStatus.SIGNED ||
            contract.getStatus() == ContractStatus.TIME) {
            ValidationReport report = validationService.validateContractActivation(contract);
            validationService.enforceValidation(report);
        }

        contract.setContractConsultants(new HashSet<>());
        if(contract.getUuid()==null || contract.getUuid().trim().isEmpty()) contract.setUuid(UUID.randomUUID().toString());
        Contract.persist(contract);
        if(contract.getSalesconsultant()!=null) ContractSalesConsultant.persist(contract.getSalesconsultant());

        // Log activity
        activityLogService.logCreated(contract.getClientuuid(),
                ClientActivityLog.TYPE_CONTRACT, contract.getUuid(), contract.getName());

        log.infof("Saved contract uuid=%s, client=%s, status=%s, user=%s",
                contract.getUuid(), contract.getClientuuid(), contract.getStatus(), userUuid);
        return contract;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public Contract extendContract(String contractuuid) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.debugf("Extending contract uuid=%s, user=%s", contractuuid, userUuid);

        Contract c = Contract.findById(contractuuid);
        Contract contract = new Contract(c);
        contract.persist();
        for (ContractConsultant cc : getContractConsultants(contractuuid)) {
            ContractConsultant contractConsultant = ContractConsultant.createContractConsultant(cc, contract);
            addConsultant(contract.getUuid(), cc.getUseruuid(), contractConsultant);
            contract.getContractConsultants().add(contractConsultant);
        }
        for (ContractProject cp : getContractProjects(contractuuid)) {
            ContractProject contractProject = new ContractProject(contract.getUuid(), cp.getProjectuuid());
            addProject(contract.getUuid(), cp.getProjectuuid());
            contract.getContractProjects().add(contractProject);
        }

        log.infof("Extended contract uuid=%s to new contract uuid=%s, user=%s", contractuuid, contract.getUuid(), userUuid);
        return contract;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void update(Contract contract) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.debugf("Updating contract uuid=%s, status=%s, user=%s", contract.getUuid(), contract.getStatus(), userUuid);

        if (contract.getCompany() == null) {
            throw new jakarta.ws.rs.BadRequestException("Company is required when updating a contract");
        }

        // Load old state for change logging
        Contract oldContract = Contract.findById(contract.getUuid());

        // Validate contract type against the ORIGINAL contract creation date (grandfathered)
        if (contract.getContractType() != null) {
            LocalDate validationDate = oldContract != null && oldContract.getCreated() != null
                    ? oldContract.getCreated().toLocalDate()
                    : LocalDate.now();

            if (!contractTypeValidationService.isValidContractType(contract.getContractType(), validationDate)) {
                String errorMessage = contractTypeValidationService.getValidationErrorMessage(contract.getContractType());
                log.warnf("Invalid contract type for contract uuid=%s: %s, user=%s", contract.getUuid(), errorMessage, userUuid);
                throw new jakarta.ws.rs.BadRequestException(errorMessage);
            }
        }

        // Validate the contract if it's being activated or is already active
        if (contract.getStatus() == ContractStatus.BUDGET ||
            contract.getStatus() == ContractStatus.SIGNED ||
            contract.getStatus() == ContractStatus.TIME) {
            // Get the full contract with consultants and projects for validation
            Contract fullContract = Contract.findById(contract.getUuid());
            if (fullContract != null) {
                ValidationReport report = validationService.validateContractActivation(fullContract);
                validationService.enforceValidation(report);
            }
        }

        Contract.update(
                        "amount = ?1, " +
                        "status = ?2, " +
                        "note = ?3, " +
                        "refid = ?4, " +
                        "company = ?5, " +
                        "salesconsultant = ?6, " +
                        "billingClientUuid = ?7, " +
                        "billingAttention = ?8, " +
                        "billingEmail = ?9, " +
                        "billingRef = ?10, " +
                        "paymentTermsUuid = ?11 " +
                        "WHERE uuid like ?12 ",
                contract.getAmount(), contract.getStatus(),
                contract.getNote(),
                contract.getRefid(), contract.getCompany(), contract.getSalesconsultant(),
                contract.getBillingClientUuid(), contract.getBillingAttention(),
                contract.getBillingEmail(), contract.getBillingRef(),
                contract.getPaymentTermsUuid(),
                contract.getUuid());
        if(contract.getSalesconsultant()!=null) ContractSalesConsultant.persist(contract.getSalesconsultant());

        // Log field-level changes
        if (oldContract != null) {
            String clientUuid = oldContract.getClientuuid();
            String entityUuid = contract.getUuid();
            String entityName = oldContract.getName();

            if (oldContract.getAmount() != contract.getAmount()) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT, entityUuid, entityName,
                        "amount", String.valueOf(oldContract.getAmount()), String.valueOf(contract.getAmount()));
            }
            if (oldContract.getStatus() != contract.getStatus()) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT, entityUuid, entityName,
                        "status", String.valueOf(oldContract.getStatus()), String.valueOf(contract.getStatus()));
            }
            if (!Objects.equals(oldContract.getNote(), contract.getNote())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT, entityUuid, entityName,
                        "note", oldContract.getNote(), contract.getNote());
            }
            if (!Objects.equals(oldContract.getRefid(), contract.getRefid())) {
                activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT, entityUuid, entityName,
                        "refid", oldContract.getRefid(), contract.getRefid());
            }
        }

        log.infof("Updated contract uuid=%s, status=%s, user=%s", contract.getUuid(), contract.getStatus(), userUuid);
    }

    @Transactional
    public void delete(String contractuuid) {
        String userUuid = requestHeaderHolder.getUserUuid();
        log.debugf("Deleting contract uuid=%s, user=%s", contractuuid, userUuid);

        if(Invoice.find("contractuuid like ?1", contractuuid).count()>0) {
            log.warnf("Cannot delete contract uuid=%s: has associated invoices, user=%s", contractuuid, userUuid);
            throw new RuntimeException("Cannot delete contract with invoices");
        }
        Contract contract = Contract.findById(contractuuid);
        String clientUuid = contract != null ? contract.getClientuuid() : null;
        String contractName = contract != null ? contract.getName() : null;
        Contract.deleteById(contractuuid);

        // Log activity
        if (clientUuid != null) {
            activityLogService.logDeleted(clientUuid,
                    ClientActivityLog.TYPE_CONTRACT, contractuuid, contractName);
        }

        log.infof("Deleted contract uuid=%s, client=%s, user=%s", contractuuid, clientUuid, userUuid);
    }

    @Transactional
    public ContractProject addProject(String contractuuid, String projectuuid) {
        log.debugf("Adding project=%s to contract=%s, user=%s", projectuuid, contractuuid, requestHeaderHolder.getUserUuid());

        ContractProject projectLink = new ContractProject(contractuuid, projectuuid);

        // Validate the project linkage
        ValidationReport report = validationService.validateContractProject(projectLink);
        validationService.enforceValidation(report);

        ContractProject.persist(projectLink);

        // Log activity
        Contract contract = Contract.findById(contractuuid);
        if (contract != null) {
            Project project = projectService.findByUuid(projectuuid);
            String projectName = project != null ? project.getName() : projectuuid;
            activityLogService.logCreated(contract.getClientuuid(),
                    ClientActivityLog.TYPE_CONTRACT_PROJECT, projectuuid, projectName);
        }

        log.infof("Added project=%s to contract=%s, user=%s", projectuuid, contractuuid, requestHeaderHolder.getUserUuid());
        return projectLink;
    }

    @Transactional
    public void removeProject(String contractuuid, String projectuuid) {
        log.debugf("Removing project=%s from contract=%s, user=%s", projectuuid, contractuuid, requestHeaderHolder.getUserUuid());

        ContractProject contractProject = ContractProject.find("contractuuid like ?1 AND projectuuid like ?2", contractuuid, projectuuid).firstResult();

        if (contractProject != null) {
            // Load contract and remove project from its collection
            // to prevent stale state at flush time
            Contract contract = Contract.findById(contractuuid);
            if (contract != null) {
                contract.getContractProjects().remove(contractProject);

                Project project = projectService.findByUuid(projectuuid);
                String projectName = project != null ? project.getName() : projectuuid;
                activityLogService.logDeleted(contract.getClientuuid(),
                        ClientActivityLog.TYPE_CONTRACT_PROJECT, projectuuid, projectName);
            }

            // Use Panache instance delete (em.remove()) instead of
            // static deleteById (JPQL bulk delete that bypasses the PC)
            contractProject.delete();
        }
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public ContractConsultant addConsultant(String contractuuid, String consultantuuid, ContractConsultant contractConsultant) {
        log.debugf("Adding consultant=%s to contract=%s, user=%s", consultantuuid, contractuuid, requestHeaderHolder.getUserUuid());

        // Validate the consultant before adding
        ValidationReport report = validationService.validateContractConsultant(contractConsultant);
        validationService.enforceValidation(report);

        ContractConsultant.persist(contractConsultant);

        // Log activity
        Contract contract = Contract.findById(contractuuid);
        if (contract != null) {
            activityLogService.logCreated(contract.getClientuuid(),
                    ClientActivityLog.TYPE_CONTRACT_CONSULTANT, contractConsultant.getUuid(),
                    contractConsultant.getName() != null ? contractConsultant.getName() : consultantuuid);
        }

        log.infof("Added consultant=%s to contract=%s, ccUuid=%s, user=%s",
                consultantuuid, contractuuid, contractConsultant.getUuid(), requestHeaderHolder.getUserUuid());
        return contractConsultant;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void updateConsultant(ContractConsultant contractConsultant) {
        log.debugf("Updating contract consultant uuid=%s, contract=%s, user=%s",
                contractConsultant.getUuid(), contractConsultant.getContractuuid(), requestHeaderHolder.getUserUuid());

        // Load old state for change logging
        ContractConsultant oldCc = ContractConsultant.findById(contractConsultant.getUuid());

        // Validate the consultant before updating
        ValidationReport report = validationService.validateContractConsultant(contractConsultant);
        validationService.enforceValidation(report);

        ContractConsultant.update(
                "hours = ?1, " +
                        "rate = ?2, " +
                        "activeFrom = ?3, " +
                        "activeTo = ?4, " +
                        "name = ?5 " +
                        "WHERE uuid like ?6 ",
                contractConsultant.getHours(),
                contractConsultant.getRate(),
                contractConsultant.getActiveFrom(),
                contractConsultant.getActiveTo(),
                contractConsultant.getName(),
                contractConsultant.getUuid());

        // Log field-level changes
        if (oldCc != null) {
            Contract contract = Contract.findById(oldCc.getContractuuid());
            if (contract != null) {
                String clientUuid = contract.getClientuuid();
                String entityUuid = contractConsultant.getUuid();
                String entityName = oldCc.getName() != null ? oldCc.getName() : oldCc.getUseruuid();

                if (oldCc.getRate() != contractConsultant.getRate()) {
                    activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT_CONSULTANT, entityUuid, entityName,
                            "rate", String.valueOf(oldCc.getRate()), String.valueOf(contractConsultant.getRate()));
                }
                if (oldCc.getHours() != contractConsultant.getHours()) {
                    activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT_CONSULTANT, entityUuid, entityName,
                            "hours", String.valueOf(oldCc.getHours()), String.valueOf(contractConsultant.getHours()));
                }
                if (!Objects.equals(oldCc.getActiveFrom(), contractConsultant.getActiveFrom())) {
                    activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT_CONSULTANT, entityUuid, entityName,
                            "activeFrom", String.valueOf(oldCc.getActiveFrom()), String.valueOf(contractConsultant.getActiveFrom()));
                }
                if (!Objects.equals(oldCc.getActiveTo(), contractConsultant.getActiveTo())) {
                    activityLogService.logFieldChange(clientUuid, ClientActivityLog.TYPE_CONTRACT_CONSULTANT, entityUuid, entityName,
                            "activeTo", String.valueOf(oldCc.getActiveTo()), String.valueOf(contractConsultant.getActiveTo()));
                }
            }
        }

        log.infof("Updated contract consultant uuid=%s, contract=%s, user=%s",
                contractConsultant.getUuid(), contractConsultant.getContractuuid(), requestHeaderHolder.getUserUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void removeConsultant(String contractuuid, String consultantuuid) {
        log.debugf("Removing consultant=%s from contract=%s, user=%s", consultantuuid, contractuuid, requestHeaderHolder.getUserUuid());

        ContractConsultant cc = ContractConsultant.findById(consultantuuid);

        if (cc != null) {
            // Load the contract and remove consultant from its collection
            // to prevent CascadeType.ALL from re-persisting the entity
            Contract contract = Contract.findById(contractuuid);
            if (contract != null) {
                contract.getContractConsultants().remove(cc);

                // Log activity
                activityLogService.logDeleted(contract.getClientuuid(),
                        ClientActivityLog.TYPE_CONTRACT_CONSULTANT, consultantuuid,
                        cc.getName() != null ? cc.getName() : cc.getUseruuid());
            }

            // Use Panache instance delete (em.remove()) instead of
            // static deleteById (JPQL bulk delete that bypasses the PC)
            cc.delete();

            log.infof("Removed consultant=%s from contract=%s, user=%s", consultantuuid, contractuuid, requestHeaderHolder.getUserUuid());
        } else {
            log.warnf("Consultant=%s not found for removal from contract=%s, user=%s", consultantuuid, contractuuid, requestHeaderHolder.getUserUuid());
        }
    }


    private ProjectUserDateDTO addContractAndRate(ProjectUserDateDTO projectUserDateDTO) {
        String sql = "select cc.* from contracts c " +
                "right join contract_project pc ON  pc.contractuuid = c.uuid " +
                "right join contract_consultants cc ON c.uuid = cc.contractuuid " +
                "where cc.activefrom <= :localdate and cc.activeto >= :localdate and cc.useruuid like :useruuid AND pc.projectuuid like :projectuuid ;";
        Query query = em.createNativeQuery(sql, ContractConsultant.class);
        query.setParameter("useruuid", projectUserDateDTO.getUseruuid());
        query.setParameter("projectuuid", projectUserDateDTO.getProjectuuid());
        query.setParameter("localdate", projectUserDateDTO.getDate());
        try {
            ContractConsultant contractConsultant = (ContractConsultant) query.getSingleResult();
            projectUserDateDTO.setRate(contractConsultant.getRate());
            projectUserDateDTO.setContractuuid(contractConsultant.getContractuuid());
        } catch (NonUniqueResultException | NoResultException e) {
            projectUserDateDTO.setContractuuid("");
            projectUserDateDTO.setRate(0.0);
        }
        return projectUserDateDTO;
    }

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public void addConsultantsToContract(Contract contract) {
        Set<ContractConsultant> contractConsultants = ContractConsultant.<ContractConsultant>stream("contractuuid like ?1", contract.getUuid()).collect(Collectors.toSet());
        Optional<ContractSalesConsultant> salesConsultant = ContractSalesConsultant.find("contractuuid like ?1 and status like 'APPROVED' order by created DESC", contract.getUuid()).firstResultOptional();
        salesConsultant.ifPresent(contract::setSalesconsultant);
        contract.setContractConsultants(contractConsultants);
    }

    @Transactional
    public void addContractTypeItem(String contractuuid, ContractTypeItem contractTypeItem) {
        validateContractTypeItemValue(contractTypeItem);
        contractTypeItem.setContractuuid(contractuuid);
        contractTypeItem.persist();
    }

    @Transactional
    public void updateContractTypeItem(ContractTypeItem contractTypeItem) {
        validateContractTypeItemValue(contractTypeItem);
        ContractTypeItem.update("key = ?1, value = ?2 where id = ?3", contractTypeItem.getKey(), contractTypeItem.getValue(), contractTypeItem.getId());
    }

    private void validateContractTypeItemValue(ContractTypeItem item) {
        // Normalize empty strings to null
        if (item.getValue() != null && item.getValue().isBlank()) {
            item.setValue(null);
        }
        // Validate that value is numeric (used in CAST operations by BI stored procedures and views)
        if (item.getValue() != null) {
            try {
                Double.parseDouble(item.getValue());
            } catch (NumberFormatException e) {
                throw new jakarta.ws.rs.BadRequestException(
                        "ContractTypeItem value must be a valid number, got: '" + item.getValue() + "'");
            }
        }
    }

    /**
     * Calculates monthly revenue for contracts using a specific contract type.
     * Uses SQL aggregation for optimal performance - calculates revenue from invoice items
     * in the database rather than loading full objects into memory.
     *
     * Only includes invoices with status='CREATED' and type='INVOICE'.
     * Revenue calculation: SUM(hours * rate * (1 - discount/100))
     *
     * @param contractType the contract type code to filter by
     * @return list of DateValueDTO with date (first of month) and revenue value
     */
    public List<DateValueDTO> getMonthlyRevenueByContractType(String contractType) {
        log.debugf("ContractService.getMonthlyRevenueByContractType: %s", contractType);

        String sql = "SELECT i.year, i.month, " +
                     "       SUM(ii.hours * ii.rate * (1 - i.discount/100)) AS totalRevenue " +
                     "FROM invoices i " +
                     "INNER JOIN invoiceitems ii ON i.uuid = ii.invoiceuuid " +
                     "WHERE i.status = 'CREATED' " +
                     "  AND i.type = 'INVOICE' " +
                     "  AND i.contract_type = :contractType " +
                     "GROUP BY i.year, i.month " +
                     "ORDER BY i.year, i.month";

        return ((List<Tuple>) em.createNativeQuery(sql, Tuple.class)
                .setParameter("contractType", contractType)
                .getResultList()).stream()
                .map(tuple -> {
                    int year = ((Number) tuple.get(0)).intValue();
                    int rawMonth = ((Number) tuple.get(1)).intValue();
                    int monthOneBased = (rawMonth >= 1 && rawMonth <= 12) ? rawMonth : rawMonth + 1; // handle 0-based months
                    Double revenue = tuple.get(2) != null ? ((Number) tuple.get(2)).doubleValue() : 0.0;

                    // Create date as first day of the month (month must be 1-12)
                    LocalDate date = LocalDate.of(year, monthOneBased, 1);
                    return new DateValueDTO(date, revenue);
                })
                .toList();
    }

}
