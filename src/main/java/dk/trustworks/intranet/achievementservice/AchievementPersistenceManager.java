package dk.trustworks.intranet.achievementservice;

import dk.trustworks.intranet.achievementservice.model.Achievement;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class AchievementPersistenceManager {

    @Transactional
    public void persistAchievement(Achievement achievement) {
        Achievement.persist(achievement);
    }

}
