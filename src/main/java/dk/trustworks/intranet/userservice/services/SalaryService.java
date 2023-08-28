package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.aggregateservices.messaging.MessageEmitter;
import dk.trustworks.intranet.userservice.model.Salary;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.validation.Valid;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class SalaryService {

    @Inject
    MessageEmitter messageEmitter;

    public List<Salary> listAll(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    public Salary getUserSalaryByMonth(String useruuid, LocalDate date) {
        return Salary.findByUseruuid(useruuid).stream().filter(salary -> salary.getActivefrom().isBefore(date)).max(Comparator.comparing(Salary::getActivefrom)).orElse(new Salary(date, 0, useruuid));
    }

    @Transactional
    public void create(String useruuid, @Valid Salary salary) {
        if(salary.getUuid()!=null && Salary.findByIdOptional(salary.getUuid()).isPresent()) Salary.deleteById(salary.getUuid());
        salary.setUuid(UUID.randomUUID().toString());
        salary.setUseruuid(useruuid);
        salary.setActivefrom(salary.getActivefrom().withDayOfMonth(1));
        Salary.persist(salary);

        messageEmitter.sendUserChange(useruuid);
    }

    @Transactional
    public void delete(String useruuid, String salaryuuid) {
        Salary.delete("uuid", salaryuuid);
    }
}