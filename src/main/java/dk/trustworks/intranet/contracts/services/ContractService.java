package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.dto.ValidationReport;
import dk.trustworks.intranet.contracts.model.*;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.ProjectUserDateDTO;
import dk.trustworks.intranet.domain.user.entity.User;
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
    public void save(Contract contract) {
        log.info("ContractService.save");
        log.info("contract = " + contract);

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
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public Contract extendContract(String contractuuid) {
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
        return contract;
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void update(Contract contract) {
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
                        "clientdatauuid = ?4, " +
                        "refid = ?5, " +
                        "company = ?6, " +
                        "salesconsultant = ?7 " +
                        "WHERE uuid like ?8 ",
                contract.getAmount(), contract.getStatus(),
                contract.getNote(), contract.getClientdatauuid(),
                contract.getRefid(), contract.getCompany(), contract.getSalesconsultant(), contract.getUuid());
        if(contract.getSalesconsultant()!=null) ContractSalesConsultant.persist(contract.getSalesconsultant());
    }

    @Transactional
    public void delete(String contractuuid) {
        if(Invoice.find("contractuuid like ?1", contractuuid).count()>0) throw new RuntimeException("Cannot delete contract with invoices");
        Contract.deleteById(contractuuid);
    }

    @Transactional
    public void addProject(String contractuuid, String projectuuid) {
        ContractProject projectLink = new ContractProject(contractuuid, projectuuid);

        // Validate the project linkage
        ValidationReport report = validationService.validateContractProject(projectLink);
        validationService.enforceValidation(report);

        ContractProject.persist(projectLink);
    }

    @Transactional
    public void removeProject(String contractuuid, String projectuuid) {
        ContractProject contractProject = ContractProject.find("contractuuid like ?1 AND projectuuid like ?2", contractuuid, projectuuid).firstResult();
        ContractProject.deleteById(contractProject.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void addConsultant(String contractuuid, String consultantuuid, ContractConsultant contractConsultant) {
        // Validate the consultant before adding
        ValidationReport report = validationService.validateContractConsultant(contractConsultant);
        validationService.enforceValidation(report);

        ContractConsultant.persist(contractConsultant);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void updateConsultant(ContractConsultant contractConsultant) {
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
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void removeConsultant(String contractuuid, String consultantuuid) {
        ContractConsultant.deleteById(consultantuuid);
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
        contractTypeItem.setContractuuid(contractuuid);
        contractTypeItem.persist();
    }

    @Transactional
    public void updateContractTypeItem(ContractTypeItem contractTypeItem) {
        ContractTypeItem.update("key = ?1, value = ?2 where id = ?3", contractTypeItem.getKey(), contractTypeItem.getValue(), contractTypeItem.getId());
    }

}
