package dk.trustworks.intranet.achievementservice.services;

import dk.trustworks.intranet.achievementservice.model.Achievement;
import lombok.extern.jbosslog.JBossLog;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.POST;
import java.util.List;
import java.util.UUID;

@JBossLog
@ApplicationScoped
public class AchievementService {

    public List<Achievement> findAll() {
        return Achievement.findAll().list();
    }

    public List<Achievement> findByUseruuid(String useruuid) {
        return Achievement.find("useruuid like ?1", useruuid).list();
    }

    @POST
    @Transactional
    public void saveAchievement(Achievement achievement) {
        if(achievement.getUuid() == null || achievement.getUuid().equalsIgnoreCase("")) achievement.setUuid(UUID.randomUUID().toString());

    }
}
