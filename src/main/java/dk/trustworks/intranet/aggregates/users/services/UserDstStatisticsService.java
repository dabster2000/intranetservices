package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.UserDstStatistic;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class UserDstStatisticsService {

    public List<UserDstStatistic> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(UserDstStatistic entity) {
        if(entity.getUuid().isEmpty()) return;
        Optional<UserDstStatistic> existingEntity = UserDstStatistic.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            /*
            s.setActiveDate(entity.getActiveDate());
            s.setEmployementTerms(entity.getEmployementTerms());
            s.setEmployementFunction(entity.getEmployementFunction());
            s.setEmployementType(entity.getEmployementType());
            s.setJobStatus(entity.getJobStatus());
            s.setSalaryType(entity.getSalaryType());

             */
            update(entity);
        }, entity::persist);
    }

    private void update(UserDstStatistic entity) {
        UserDstStatistic.update(
                "activeDate = ?1, " +
                        "employementTerms = ?2, " +
                        "employementFunction = ?3, " +
                        "employementType = ?4, " +
                        "jobStatus = ?5, " +
                        "salaryType = ?6 " +
                        "WHERE uuid LIKE ?7 ",
                entity.getActiveDate(),
                entity.getEmployementTerms(),
                entity.getEmployementFunction(),
                entity.getEmployementType(),
                entity.getJobStatus(),
                entity.getSalaryType(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        UserDstStatistic.deleteById(uuid);
    }

    public List<UserDstStatistic> findByUseruuid(String useruuid) {
        return UserDstStatistic.findByUser(useruuid);
    }
}