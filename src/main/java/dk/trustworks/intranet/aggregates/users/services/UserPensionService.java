package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.UserPension;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class UserPensionService {

    public List<UserPension> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid UserPension entity) {
        if(entity.getUuid().isEmpty()) return;
        Optional<UserPension> existingEntity = UserPension.findByIdOptional(entity.getUuid());
        existingEntity.ifPresentOrElse(s -> {
            s.setActiveDate(entity.getActiveDate());
            s.setOwnPensionPayment(entity.getOwnPensionPayment());
            s.setCompanyPensionPayment(entity.getCompanyPensionPayment());
            update(s);
        }, entity::persist);
    }

    private void update(UserPension entity) {
        UserPension.update(
                "activeDate = ?1, " +
                        "ownPensionPayment = ?2, " +
                        "companyPensionPayment = ?3 " +
                        "WHERE uuid LIKE ?4 ",
                entity.getActiveDate(),
                entity.getOwnPensionPayment(),
                entity.getCompanyPensionPayment(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        UserPension.deleteById(uuid);
    }

    public List<UserPension> findByUseruuid(String useruuid) {
        return UserPension.findByUser(useruuid);
    }
}