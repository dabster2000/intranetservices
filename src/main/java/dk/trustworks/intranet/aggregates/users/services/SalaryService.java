package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.Salary;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class SalaryService {

    public List<Salary> listAll(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }

    public Salary getUserSalaryByMonth(String useruuid, LocalDate month) {
        return Salary.<Salary>find("useruuid = ?1 and activefrom <= ?2 order by activefrom desc",
                useruuid, month).firstResultOptional().orElse(new Salary(month, 0, useruuid));
    }

    public boolean isSalaryChanged(String useruuid, LocalDate month) {
        return Salary.<Salary>find("activefrom = ?1 and useruuid = ?2", month, useruuid).firstResultOptional().isPresent();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid Salary salary) {
        if(salary.getUuid().isEmpty()) return;
        Optional<Salary> existingSalary = Salary.findByIdOptional(salary.getUuid());
        existingSalary.ifPresentOrElse(s -> {
            s.setSalary(salary.getSalary());
            s.setLunch(salary.isLunch());
            s.setPhone(salary.isPhone());
            s.setPrayerDay(salary.isPrayerDay());
            s.setType(salary.getType());
            s.setActivefrom(salary.getActivefrom());
            updateSalary(s);
        }, salary::persist);
    }

    private void updateSalary(Salary salary) {
        log.info("Updating salary: " + salary);
        Salary.update("salary = ?1, " +
                        "activefrom = ?2, " +
                        "type = ?3, " +
                        "lunch = ?4, " +
                        "phone = ?5, " +
                        "prayerDay = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                salary.getSalary(),
                salary.getActivefrom(),
                salary.getType(),
                salary.isLunch(),
                salary.isPhone(),
                salary.isPrayerDay(),
                salary.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String salaryuuid) {
        Salary.deleteById(salaryuuid);
    }

    public List<Salary> findByUseruuid(String useruuid) {
        return Salary.findByUseruuid(useruuid);
    }
}