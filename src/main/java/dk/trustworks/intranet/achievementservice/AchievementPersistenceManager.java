package dk.trustworks.intranet.achievementservice;

import dk.trustworks.intranet.achievementservice.model.Achievement;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;

@ApplicationScoped
public class AchievementPersistenceManager {

    @Transactional
    public void persistAchievement(Achievement achievement) {
        Achievement.persist(achievement);
    }

}
