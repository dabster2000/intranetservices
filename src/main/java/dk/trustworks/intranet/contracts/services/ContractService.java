package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.aggregates.invoice.model.Invoice;
import dk.trustworks.intranet.contracts.model.*;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.ProjectUserDateDTO;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.NonUniqueResultException;
import jakarta.persistence.Query;
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
    EntityManager em;

    public List<Contract> findAll() {
        List<Contract> contractList = Contract.findAll().list();
        //contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public List<Contract> findByPeriod(LocalDate fromdate, LocalDate todate) {
        Query query = em.createNativeQuery("SELECT " +
                "c.* " +
                "FROM " +
                "    twservices.contracts c " +
                "INNER JOIN " +
                "    ( " +
                "        SELECT " +
                "            contractuuid, " +
                "            MIN(activefrom) AS min_activefrom, " +
                "            MAX(activeto) AS max_activeto " +
                "        FROM " +
                "            twservices.contract_consultants " +
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
        return contractProjects.stream().map(contractProject -> projectService.findByUuid(contractProject.getProjectuuid())).collect(Collectors.toList());
    }

    public Set<ContractProject> getContractProjects(String contractuuid) {
        return ContractProject.find("contractuuid like ?1", contractuuid).list().stream().map(panacheEntityBase -> (ContractProject) panacheEntityBase).collect(Collectors.toSet());
    }

    /*
    public List<Contract> findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn(LocalDate fromdate, LocalDate todate, List<ContractStatus> contractStatuses) {
        List<Contract> contractList = Contract.find("(activeFrom <= ?1 AND activeTo >= ?1) OR (activeFrom <= ?2 AND activeTo >= ?2) AND status IN (?3)", fromdate, todate, contractStatuses).list();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }
    */
    /*
SELECT
    c.uuid,
    contract_period.min_activefrom,
    contract_period.max_activeto
FROM
    twservices.contracts c
INNER JOIN
    (
        SELECT
            contractuuid,
            MIN(activefrom) AS min_activefrom,
            MAX(activeto) AS max_activeto
        FROM
            twservices.contract_consultants
        GROUP BY
            contractuuid
    ) AS contract_period ON c.uuid = contract_period.contractuuid
WHERE
    (contract_period.min_activefrom <= ?1 AND contract_period.max_activeto >= ?1) OR (contract_period.min_activefrom <= ?2 AND contract_period.max_activeto >= ?2) AND status IN (?3);
     */

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


    public ProjectUserDateDTO findRateByProjectuuidAndUseruuidAndDate(String projectuuid, String useruuid, String date) {
        return addContractAndRate(new ProjectUserDateDTO(UUID.randomUUID().toString(), projectuuid, useruuid, date));
    }

    /*
    public Contract findContractByProjectuuidAndUseruuidAndDate(String projectuuid, String useruuid, String date) {
        String sql = "select c.* from contracts c " +
                "right join contract_project pc ON  pc.contractuuid = c.uuid " +
                "right join contract_consultants cc ON c.uuid = cc.contractuuid " +
                "where cc.activefrom <= :localdate and cc.activeto >= :localdate and cc.useruuid like :useruuid AND pc.projectuuid like :projectuuid ;";
        Query query = em.createNativeQuery(sql, Contract.class);
        query.setParameter("useruuid", useruuid);
        query.setParameter("projectuuid", projectuuid);
        query.setParameter("localdate", dateIt(date));
        try {
            return (Contract) query.getSingleResult();
        } catch (NonUniqueResultException | NoResultException e) {

        }
        return null;
    }
     */

    public List<Contract> findByClientuuid(String clientuuid) {
        return Contract.find("clientuuid = ?1", clientuuid).list();
    }

    public List<Contract> findByProjectuuid(String projectuuid) {
        List<ContractProject> contractProject = ContractProject.find("projectuuid LIKE ?1", projectuuid).list();
        List<Contract> result = new ArrayList<>();
        contractProject.forEach(contractProject1 -> result.add(Contract.findById(contractProject1.getContractuuid()))); //result.add(addConsultantsToContract(Contract.findById(contractProject1.getContractuuid()))));
        return result;
    }

    /*
    public List<ProjectUserDateDTO> findRateByProjectuuidAndUseruuidAndDateList(List<ProjectUserDateDTO> projectUserDateDTOList) {
        for (ProjectUserDateDTO projectUserDateDTO : projectUserDateDTOList) {
            addContractAndRate(projectUserDateDTO);
        }
        return projectUserDateDTOList;
    }

    public List<ContractConsultant> findContractConsultant(String constractuuid) {
        return getContractConsultants(constractuuid);
    }

     */

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void save(Contract contract) {
        log.info("ContractService.save");
        log.info("contract = " + contract);
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
        ContractProject.persist(new ContractProject(contractuuid, projectuuid));
    }

    @Transactional
    public void removeProject(String contractuuid, String projectuuid) {
        ContractProject contractProject = ContractProject.find("contractuuid like ?1 AND projectuuid like ?2", contractuuid, projectuuid).firstResult();
        ContractProject.deleteById(contractProject.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void addConsultant(String contractuuid, String consultantuuid, ContractConsultant contractConsultant) {
        ContractConsultant.persist(contractConsultant);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "employee-budgets")
    public void updateConsultant(ContractConsultant contractConsultant) {
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

    private void addConsultantsToContract(Contract contract) {
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
