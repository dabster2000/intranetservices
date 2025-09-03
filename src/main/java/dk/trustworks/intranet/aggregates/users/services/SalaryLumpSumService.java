package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.SalaryLumpSum;
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
public class SalaryLumpSumService {

    public List<SalaryLumpSum> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    public List<SalaryLumpSum> findByUseruuid(String useruuid) {
        return SalaryLumpSum.findByUser(useruuid);
    }

    public List<SalaryLumpSum> findByUseruuidAndMonth(String useruuid, LocalDate month) {
        List<SalaryLumpSum> lumpSumList = SalaryLumpSum.<SalaryLumpSum>find("useruuid", useruuid).list();
        return lumpSumList.stream()
                .filter(lumpSum -> lumpSum.getMonth().isEqual(month))
                .toList();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid SalaryLumpSum entity) {
        if(entity.getUuid().isEmpty()) return;
        entity.setMonth(entity.getMonth().withDayOfMonth(1));
        Optional<SalaryLumpSum> existingEntity = SalaryLumpSum.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            s.setMonth(entity.getMonth());
            s.setDescription(entity.getDescription());
            s.setLumpSum(entity.getLumpSum());
            s.setPension(entity.getPension());
            updateSalary(s);
        }, entity::persist);
    }

    private void updateSalary(SalaryLumpSum entity) {
        SalaryLumpSum.update(
                "month = ?1, " +
                        "description = ?2, " +
                        "lumpSum = ?3, " +
                        "pension = ?4 " +
                        "WHERE uuid LIKE ?5 ",
                entity.getMonth(),
                entity.getDescription(),
                entity.getLumpSum(),
                entity.getPension(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String salaryuuid) {
        SalaryLumpSum.deleteById(salaryuuid);
    }
}