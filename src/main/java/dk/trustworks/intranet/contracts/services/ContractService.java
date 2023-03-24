package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.ContractProject;
import dk.trustworks.intranet.contracts.model.ContractSalesConsultant;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dto.ProjectUserDateDTO;
import dk.trustworks.intranet.userservice.model.User;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.NonUniqueResultException;
import javax.persistence.Query;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static dk.trustworks.intranet.utils.DateUtils.*;

@ApplicationScoped
public class ContractService {

    @Inject
    ProjectService projectService;

    @Inject
    EntityManager em;

    public List<Contract> findAll() {
        List<Contract> contractList = Contract.findAll().list();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public List<Contract> findByPeriod(LocalDate fromdate, LocalDate todate) {
        List<Contract> contractList = Contract.find("(activeFrom <= ?1 AND activeTo >= ?1) OR (activeFrom <= ?2 AND activeTo >= ?2)", fromdate, todate).list();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public Contract findByUuid(@PathParam("contractuuid") String contractuuid) {
        return addConsultantsToContract(Contract.findById(contractuuid));
    }

    public List<Contract> findTimeActiveConsultantContracts(String useruuid, LocalDate activeon) {
        String sql = "select c.* from contracts c " +
                "right join contract_consultants cc on c.uuid = cc.contractuuid " +
                "where c.activefrom <= :activeon and c.activeto >= :activeon and cc.useruuid like :useruuid " +
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


    public List<Contract> findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn(LocalDate fromdate, LocalDate todate, List<ContractStatus> contractStatuses) {
        List<Contract> contractList = Contract.find("(activeFrom <= ?1 AND activeTo >= ?1) OR (activeFrom <= ?2 AND activeTo >= ?2) AND status IN (?3)", fromdate, todate, contractStatuses).list();
        contractList.forEach(this::addConsultantsToContract);
        return contractList;
    }

    public List<ContractConsultant> getContractConsultants(String constractuuid) {
        return ContractConsultant.find("contractuuid like ?1", constractuuid).list();
    }

    public List<Contract> getContractsByDate(List<Contract> contracts, User user, LocalDate date) {
        return contracts.stream()
                .filter(contract -> isBetweenBothIncluded(date, contract.getActiveFrom(), contract.getActiveTo()) &&
                        (
                                contract.getStatus().equals(ContractStatus.CLOSED) ||
                                        contract.getStatus().equals(ContractStatus.TIME) ||
                                        contract.getStatus().equals(ContractStatus.SIGNED) ||
                                        contract.getStatus().equals(ContractStatus.BUDGET)
                        ) && contract.findByUser(user)!=null)
                .collect(Collectors.toList());
    }


    public ProjectUserDateDTO findRateByProjectuuidAndUseruuidAndDate(String projectuuid, String useruuid, String date) {
        return addContractAndRate(new ProjectUserDateDTO(UUID.randomUUID().toString(), projectuuid, useruuid, date));
    }

    public Contract findContractByProjectuuidAndUseruuidAndDate(String projectuuid, String useruuid, String date) {
        String sql = "select c.* from contracts c " +
                "right join contract_project pc ON  pc.contractuuid = c.uuid " +
                "right join contract_consultants cc ON c.uuid = cc.contractuuid " +
                "where c.activefrom <= :localdate and c.activeto >= :localdate and cc.useruuid like :useruuid AND pc.projectuuid like :projectuuid ;";
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

    public List<Contract> findByClientuuid(String clientuuid) {
        List<Contract> contracts = Contract.find("clientuuid like ?1", clientuuid).list();
        for (Contract contract : contracts) {
            addConsultantsToContract(contract);
        }
        return contracts;
    }

    public List<Contract> findByProjectuuid(String projectuuid) {
        List<ContractProject> contractProject = ContractProject.find("projectuuid LIKE ?1", projectuuid).list();
        List<Contract> result = new ArrayList<>();
        contractProject.forEach(contractProject1 -> result.add(addConsultantsToContract(Contract.findById(contractProject1.getContractuuid()))));
        return result;
    }

    public List<ProjectUserDateDTO> findRateByProjectuuidAndUseruuidAndDateList(List<ProjectUserDateDTO> projectUserDateDTOList) {
        for (ProjectUserDateDTO projectUserDateDTO : projectUserDateDTOList) {
            addContractAndRate(projectUserDateDTO);
        }
        return projectUserDateDTOList;
    }

    public List<ContractConsultant> findContractConsultant(String constractuuid) {
        return getContractConsultants(constractuuid);
    }

    @Transactional
    public void save(Contract contract) {
        if(contract.getUuid()==null || contract.getUuid().trim().equals("")) contract.setUuid(UUID.randomUUID().toString());
        Contract.persist(contract);
        if(contract.getSalesconsultant()!=null) ContractSalesConsultant.persist(contract.getSalesconsultant());
    }

    @Transactional
    public Contract extendContract(String contractuuid) {
        Contract c = Contract.findById(contractuuid);
        Contract contract = new Contract(c);
        contract.persist();
        for (ContractConsultant cc : getContractConsultants(contractuuid)) {
            ContractConsultant contractConsultant = ContractConsultant.createContractConsultant(cc, contract);
            contractConsultant.persist();
            contract.getContractConsultants().add(contractConsultant);
        }
        for (ContractProject cp : getContractProjects(contractuuid)) {
            ContractProject contractProject = new ContractProject(contract.getUuid(), cp.getProjectuuid());
            contractProject.persist();
            contract.getContractProjects().add(contractProject);
        }
        return contract;
    }

    @Transactional
    public void update(Contract contract) {
        Contract.update("activefrom = ?1, " +
                        "activeto = ?2, " +
                        "amount = ?3, " +
                        "status = ?4, " +
                        "note = ?5, " +
                        "clientdatauuid = ?6, " +
                        "refid = ?7 " +
                        "WHERE uuid like ?8 ",
                stringIt(contract.getActiveFrom()), stringIt(contract.getActiveTo()),
                contract.getAmount(), contract.getStatus(),
                contract.getNote(), contract.getClientdatauuid(),
                contract.getRefid(), contract.getUuid());
        if(contract.getSalesconsultant()!=null) ContractSalesConsultant.persist(contract.getSalesconsultant());
    }

    @Transactional
    public void delete(String contractuuid) {
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
    public void addConsultant(String contractuuid, String consultantuuid, ContractConsultant contractConsultant) {
        ContractConsultant.persist(contractConsultant);
    }

    @Transactional
    public void updateConsultant(String contractuuid, String consultantuuid, ContractConsultant contractConsultant) {
        ContractConsultant.update("budget = ?1, " +
                        "hours = ?2, " +
                        "rate = ?3 " +
                        "WHERE uuid like ?4 ",
                contractConsultant.getBudget(),
                contractConsultant.getHours(),
                contractConsultant.getRate(),
                contractConsultant.getUuid());
    }

    @Transactional
    public void removeConsultant(String contractuuid, String consultantuuid) {
        ContractConsultant.deleteById(consultantuuid);
    }


    private ProjectUserDateDTO addContractAndRate(ProjectUserDateDTO projectUserDateDTO) {
        String sql = "select cc.* from contracts c " +
                "right join contract_project pc ON  pc.contractuuid = c.uuid " +
                "right join contract_consultants cc ON c.uuid = cc.contractuuid " +
                "where c.activefrom <= :localdate and c.activeto >= :localdate and cc.useruuid like :useruuid AND pc.projectuuid like :projectuuid ;";
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

    private Contract addConsultantsToContract(Contract contract) {
        System.out.println("contract = " + contract);
        List<ContractConsultant> contractConsultants = ContractConsultant.find("contractuuid like ?1", contract.getUuid()).list();
        Optional<ContractSalesConsultant> salesConsultant = ContractSalesConsultant.find("contractuuid like ?1 and status like 'APPROVED' order by created DESC", contract.getUuid()).firstResultOptional();
        salesConsultant.ifPresent(contract::setSalesconsultant);
        contract.setContractConsultants(contractConsultants);
        return contract;
    }

    public List<Contract> findActiveContractsByDate(LocalDate activeDate, ContractStatus... statusList) {
        return findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn(activeDate, activeDate, Arrays.stream(statusList).toList());
    }

    private boolean isValidContract(Contract contract) {
        boolean isValid = true;
        for (Contract contractTest : findByClientuuid(contract.getClientuuid())) {
            boolean isOverlapped = false;
            if(contract.getUuid().equals(contractTest.getUuid())) continue;
            if((contract.getActiveFrom().isBefore(contractTest.getActiveTo()) || contract.getActiveFrom().isEqual(contractTest.getActiveTo())) &&
                    (contract.getActiveTo().isAfter(contractTest.getActiveFrom()) || contract.getActiveTo().isEqual(contractTest.getActiveFrom()))) {
                isOverlapped = true;
            }

            boolean hasProject = false;
            for (ContractProject contractProject : getContractProjects(contract.getUuid())) {
                for (ContractProject contractTestProject : contractTest.getContractProjects()) {
                    if(contractProject.getProjectuuid().equals(contractTestProject.getProjectuuid())) {
                        hasProject = true;
                        break;
                    }
                }
                if(hasProject) break;
            }

            boolean hasConsultant = false;
            for (ContractConsultant contractConsultant : findContractConsultant(contract.getUuid())) {
                for (ContractConsultant contractTestConsultant : contractTest.getContractConsultants()) {
                    if(contractConsultant.getUseruuid().equals(contractTestConsultant.getUseruuid())) {
                        hasConsultant = true;
                        break;
                    }
                }
                if(hasConsultant) break;
            }
            if(isOverlapped && hasProject && hasConsultant) isValid = false;
        }
        return isValid;
    }
}
