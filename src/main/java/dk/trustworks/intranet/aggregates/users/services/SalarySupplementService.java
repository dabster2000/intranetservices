package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.SalarySupplement;
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
public class SalarySupplementService {

    public List<SalarySupplement> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    public List<SalarySupplement> findByUseruuid(String useruuid) {
        return SalarySupplement.findByUseruuid(useruuid);
    }

    public List<SalarySupplement> findByUseruuidAndMonth(String useruuid, LocalDate month) {
        List<SalarySupplement> salarySupplements = SalarySupplement.<SalarySupplement>find("useruuid", useruuid).list();
        return salarySupplements.stream()
                .filter(salarySupplement -> salarySupplement.getFromMonth().isBefore(month) || salarySupplement.getFromMonth().isEqual(month))
                .filter(salarySupplement -> salarySupplement.getToMonth() == null || salarySupplement.getToMonth().isEqual(month) || salarySupplement.getToMonth().isAfter(month))
                .toList();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid SalarySupplement entity) {
        if(entity.getUuid().isEmpty()) return;
        entity.setFromMonth(entity.getFromMonth().withDayOfMonth(1));
        if(entity.getToMonth()!=null) entity.setToMonth(entity.getToMonth().withDayOfMonth(1));
        Optional<SalarySupplement> existingEntity = SalarySupplement.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            s.setValue(entity.getValue());
            s.setDescription(entity.getDescription());
            s.setFromMonth(entity.getFromMonth());
            s.setToMonth(entity.getToMonth());
            s.setType(entity.getType());
            s.setWithPension(entity.getWithPension());
            updateSalary(s);
        }, entity::persist);
    }

    private void updateSalary(SalarySupplement salarySupplement) {
        SalarySupplement.update("value = ?1, " +
                        "description = ?2, " +
                        "fromMonth = ?3, " +
                        "toMonth = ?4, " +
                        "type = ?5, " +
                        "withPension = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                salarySupplement.getValue(),
                salarySupplement.getDescription(),
                salarySupplement.getFromMonth(),
                salarySupplement.getToMonth(),
                salarySupplement.getType(),
                salarySupplement.getWithPension(),
                salarySupplement.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String salaryuuid) {
        SalarySupplement.deleteById(salaryuuid);
    }
}