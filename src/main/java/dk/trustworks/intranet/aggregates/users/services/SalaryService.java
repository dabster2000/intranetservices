package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.Salary;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.validation.Valid;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class SalaryService {

    public List<Salary> listAll(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    public Salary getUserSalaryByMonth(String useruuid, LocalDate date) {
        return Salary.findByUseruuid(useruuid).stream().filter(salary -> salary.getActivefrom().isBefore(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(date, 0, useruuid));
    }

    @Transactional
    public void create(@Valid Salary salary) {
        if(salary.getUuid().isEmpty()) return;
        Optional<Salary> existingSalary = Salary.findByIdOptional(salary.getUuid());
        existingSalary.ifPresentOrElse(s -> {
            s.setSalary(salary.getSalary());
            s.setActivefrom(salary.getActivefrom());
            updateSalary(s);
        }, salary::persist);
    }

    private void updateSalary(Salary salary) {
        Salary.update("salary = ?1, " +
                        "activefrom = ?2 " +
                        "WHERE uuid LIKE ?3 ",
                salary.getSalary(),
                salary.getActivefrom(),
                salary.getUuid());
    }

    @Transactional
    public void delete(String salaryuuid) {
        Salary.deleteById(salaryuuid);
    }

    public List<Salary> findByUseruuid(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }
}