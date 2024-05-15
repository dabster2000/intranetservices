package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.userservice.model.UserBankInfo;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class UserBankInfoService {

    public List<UserBankInfo> listAll(String useruuid) {
        return findByUseruuid(useruuid);
    }

    public List<UserBankInfo> findUnassignedBankInfos() {
        return UserBankInfo.find("useruuid IS NULL").list();
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid UserBankInfo entity) {
        if(entity.getUuid().isEmpty()) return;
        entity.setActiveDate(entity.getActiveDate().withDayOfMonth(1));
        Optional<UserBankInfo> existingEntity = UserBankInfo.findByIdOptional(entity.getUuid());
        if(existingEntity.isEmpty()) existingEntity = UserBankInfo.find("useruuid = ?1 AND activeDate = ?2", entity.getUseruuid(), entity.getActiveDate()).firstResultOptional();
        existingEntity.ifPresentOrElse(s -> {
            s.setUseruuid(entity.getUseruuid());
            s.setActiveDate(entity.getActiveDate());
            s.setRegnr(entity.getRegnr());
            s.setAccountNr(entity.getAccountNr());
            s.setBicSwift(entity.getBicSwift());
            s.setIban(entity.getIban());
            update(s);
        }, entity::persist);
    }

    private void update(UserBankInfo entity) {
        UserBankInfo.update(
                "activeDate = ?1, " +
                        "regnr = ?2, " +
                        "accountNr = ?3, " +
                        "bicSwift = ?4, " +
                        "iban = ?5 " +
                        "WHERE uuid LIKE ?6 ",
                entity.getActiveDate(),
                entity.getRegnr(),
                entity.getAccountNr(),
                entity.getBicSwift(),
                entity.getIban(),
                entity.getUuid());
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        UserBankInfo.deleteById(uuid);
    }

    public List<UserBankInfo> findByUseruuid(String useruuid) {
        return UserBankInfo.findByUseruuid(useruuid);
    }
}