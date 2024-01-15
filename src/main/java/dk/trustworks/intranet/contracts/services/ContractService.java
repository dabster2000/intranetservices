package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.model.*;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.crm.services.ProjectService;
import dk.trustworks.intranet.dao.workservice.model.WorkFull;
import dk.trustworks.intranet.dao.workservice.services.WorkService;
import dk.trustworks.intranet.dto.ProjectUserDateDTO;
import dk.trustworks.intranet.userservice.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import lombok.extern.jbosslog.JBossLog;

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

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

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
        List<Contract> contracts = Contract.find("clientuuid like ?1", clientuuid).list();
        /*
        for (Contract contract : contracts) {
            addConsultantsToContract(contract);
        }
         */
        return contracts;
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
    public void save(Contract contract) {
        log.info("ContractService.save");
        log.info("contract = " + contract);
        contract.setContractConsultants(new HashSet<>());
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
        Contract.update(
                        "amount = ?1, " +
                        "status = ?2, " +
                        "note = ?3, " +
                        "clientdatauuid = ?4, " +
                        "refid = ?5, " +
                        "company = ?6 " +
                        "WHERE uuid like ?7 ",
                contract.getAmount(), contract.getStatus(),
                contract.getNote(), contract.getClientdatauuid(),
                contract.getRefid(), contract.getCompany(), contract.getUuid());
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
        ContractConsultant.update(
                        "hours = ?1, " +
                        "rate = ?2, " +
                        "activefrom = ?3, " +
                        "activeto = ?4 " +
                        "WHERE uuid like ?5 ",
                contractConsultant.getHours(),
                contractConsultant.getRate(),
                stringIt(contractConsultant.getActiveFrom()),
                stringIt(contractConsultant.getActiveTo()),
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

    private Contract addConsultantsToContract(Contract contract) {
        Set<ContractConsultant> contractConsultants = ContractConsultant.<ContractConsultant>stream("contractuuid like ?1", contract.getUuid()).collect(Collectors.toSet());
        Optional<ContractSalesConsultant> salesConsultant = ContractSalesConsultant.find("contractuuid like ?1 and status like 'APPROVED' order by created DESC", contract.getUuid()).firstResultOptional();
        salesConsultant.ifPresent(contract::setSalesconsultant);
        contract.setContractConsultants(contractConsultants);
        return contract;
    }

    /*
    public List<Contract> findActiveContractsByDate(LocalDate activeDate, ContractStatus... statusList) {
        return findByActiveFromLessThanEqualAndActiveToGreaterThanEqualAndStatusIn(activeDate, activeDate, Arrays.stream(statusList).toList());
    }

     */

    /*
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
     */

    @Inject
    WorkService workService;

    //@Scheduled(every = "1h")
    public void updateContractStatus() {
        VertxOptions options = new VertxOptions().setBlockedThreadCheckInterval(300000); // 120 seconds
        Vertx vertx = Vertx.vertx(options);


        System.out.println("ContractService.updateContractStatus START");
        List<ContractConsultant> contractConsultants = ContractConsultant.listAll();
        List<ContractConsultant> updatedContractConsultants = new ArrayList<>();
        List<Budget> budgets = Budget.<Budget>stream("budget > 0").toList();
        System.out.println("Load workfull");
        List<WorkFull> workFullList = WorkFull.<WorkFull>find("contractuuid is not null and useruuid is not null and workduration > 0").list();
        System.out.println("workFullList.size() = " + workFullList.size());

        Map<String, List<Budget>> budgetMap = budgets.stream().collect(Collectors.groupingBy(Budget::getConsultantuuid));
        int i = 0;
        System.out.println("budgetMap.keySet().size() = " + budgetMap.keySet().size());
        for (String key : budgetMap.keySet().stream().sorted().toList()) {
            Optional<ContractConsultant> ccOptional = contractConsultants.stream().filter(contractConsultant -> contractConsultant.getUuid().equals(key)).findAny();
            if(ccOptional.isEmpty()) continue;
            ContractConsultant cc = ccOptional.get();
            //System.out.println("ContractConsultant = " + cc);
            //System.out.println("min = " + budgetMap.get(key).stream().map(b -> LocalDate.of(b.getYear(), b.getMonth()+1, 1)).min(LocalDate::compareTo).orElse(null));
            //System.out.println("max = " + budgetMap.get(key).stream().map(b -> LocalDate.of(b.getYear(), b.getMonth()+1, 1)).max(LocalDate::compareTo).orElse(null));
            double max = Math.round(budgetMap.get(key).stream().mapToDouble(b -> (((b.getBudget()*12)/52.0/cc.getRate()/7.4)*7.4)).max().orElse(0.0));
            if(max>=30) max = 37;
            if(max>25 && max<30) max = 30;
            if(max>20 && max<=25) max = 25;
            if(max>10 && max<=15) max = 15;
            cc.setHours(max);
            cc.setActiveFrom(budgetMap.get(key).stream().map(b -> LocalDate.of(b.getYear(), b.getMonth()+1, 1)).min(LocalDate::compareTo).orElse(LocalDate.of(1900,1,1)));
            if(!cc.getActiveFrom().isAfter(LocalDate.now())) {
                //Optional singleResult = em.createNativeQuery("select * from work_full where contractuuid = '" + cc.getContractuuid() + "' and useruuid = '" + cc.getUseruuid() + "' order by registered asc limit 1; ", WorkFull.class).getResultStream().findAny();
                Optional<WorkFull> singleResult = workFullList.stream().filter(wf -> wf.getContractuuid().equals(cc.getContractuuid()) && wf.getUseruuid().equals(cc.getUseruuid())).min(Comparator.comparing(WorkFull::getRegistered));
                if(singleResult.isPresent()) {
                    LocalDate workMin = singleResult.get().getRegistered();
                    if (workMin.isBefore(cc.getActiveFrom())) cc.setActiveFrom(workMin);
                }
            }
            cc.setActiveTo(budgetMap.get(key).stream().map(b -> LocalDate.of(b.getYear(), b.getMonth()+1, 1)).max(LocalDate::compareTo).orElse(LocalDate.of(2100,1,1)));
            if(!cc.getActiveTo().isAfter(LocalDate.now())) {
                //Optional singleResult = em.createNativeQuery("select * from work_full where contractuuid = '" + cc.getContractuuid() + "' and useruuid = '" + cc.getUseruuid() + "' order by registered desc limit 1; ", WorkFull.class).getResultStream().findAny();
                Optional<WorkFull> singleResult = workFullList.stream().filter(wf -> wf.getContractuuid().equals(cc.getContractuuid()) && wf.getUseruuid().equals(cc.getUseruuid())).max(Comparator.comparing(WorkFull::getRegistered));
                if(singleResult.isPresent()) {
                    LocalDate workMax = singleResult.get().getRegistered();
                    if (workMax.isAfter(cc.getActiveTo())) cc.setActiveTo(workMax);
                }
            }
            updatedContractConsultants.add(cc);
            System.out.println(cc);
            System.out.println("i = " + (++i));
        }

        QuarkusTransaction.begin();
        updatedContractConsultants.forEach(cc -> updateConsultant("", "", cc));
        QuarkusTransaction.commit();

        System.out.println("ContractService.updateContractStatus END");
    }
}
