package dk.trustworks.intranet.aggregates.users.services;

import dk.trustworks.intranet.domain.user.entity.UserCareerLevel;
import io.quarkus.cache.CacheInvalidateAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class CareerLevelService {

    public List<UserCareerLevel> listAll(String useruuid) {
        return UserCareerLevel.findByUseruuid(useruuid);
    }

    public Optional<UserCareerLevel> getCurrent(String useruuid) {
        return UserCareerLevel.findByUseruuid(useruuid).stream()
                .filter(cl -> !cl.getActiveFrom().isAfter(LocalDate.now()))
                .max(Comparator.comparing(UserCareerLevel::getActiveFrom));
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void create(@Valid UserCareerLevel careerLevel) {
        if (careerLevel.getUuid() == null || careerLevel.getUuid().isEmpty()) return;

        // Validate that career level matches its track
        if (careerLevel.getCareerLevel().getTrack() != null &&
                !careerLevel.getCareerLevel().getTrack().equals(careerLevel.getCareerTrack())) {
            throw new IllegalArgumentException(
                    "Career level " + careerLevel.getCareerLevel() +
                    " does not belong to track " + careerLevel.getCareerTrack() +
                    " (expected " + careerLevel.getCareerLevel().getTrack() + ")");
        }

        Optional<UserCareerLevel> existing = UserCareerLevel.findByIdOptional(careerLevel.getUuid());
        existing.ifPresentOrElse(cl -> {
            log.info("CareerLevelService.create -> updating career level");
            cl.setActiveFrom(careerLevel.getActiveFrom());
            cl.setCareerTrack(careerLevel.getCareerTrack());
            cl.setCareerLevel(careerLevel.getCareerLevel());
            UserCareerLevel.update("activeFrom = ?1, careerTrack = ?2, careerLevel = ?3 WHERE uuid LIKE ?4",
                    cl.getActiveFrom(), cl.getCareerTrack(), cl.getCareerLevel(), cl.getUuid());
        }, () -> {
            log.info("CareerLevelService.create -> creating career level");
            careerLevel.persist();
        });
    }

    @Transactional
    @CacheInvalidateAll(cacheName = "user-cache")
    public void delete(String uuid) {
        log.info("CareerLevelService.delete: " + uuid);
        UserCareerLevel.deleteById(uuid);
    }
}
