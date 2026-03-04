package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.UserContactinfo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserContactInfoService {

    @Transactional
    public List<UserContactinfo> findAll(String useruuid) {
        return UserContactinfo.findAllByUseruuid(useruuid);
    }

    @Transactional
    public UserContactinfo findOne(String useruuid) {
        UserContactinfo userContactinfo = UserContactinfo.findCurrentByUseruuid(useruuid);
        if (userContactinfo == null) {
            userContactinfo = new UserContactinfo();
            userContactinfo.setUuid(UUID.randomUUID().toString());
            userContactinfo.setStreetname("");
            userContactinfo.setPostalcode("");
            userContactinfo.setCity("");
            userContactinfo.setPhone("");
            userContactinfo.setUseruuid(useruuid);
            userContactinfo.setActiveDate(LocalDate.now());
            userContactinfo.setSlackusername("");
            UserContactinfo.persist(userContactinfo);
        }
        return userContactinfo;
    }

    @Transactional
    public UserContactinfo create(String useruuid, UserContactinfo userContactinfo) {
        userContactinfo.setUuid(UUID.randomUUID().toString());
        userContactinfo.setUseruuid(useruuid);
        if (userContactinfo.getActiveDate() == null) {
            userContactinfo.setActiveDate(LocalDate.now());
        }
        UserContactinfo.persist(userContactinfo);
        return userContactinfo;
    }

    @Transactional
    public void update(String useruuid, UserContactinfo userContactinfo) {
        UserContactinfo.update("city = ?1, " +
                        "phone = ?2, " +
                        "postalcode = ?3, " +
                        "streetname = ?4, " +
                        "slackusername = ?5, " +
                        "activeDate = ?6 " +
                        "where useruuid like ?7",
                userContactinfo.getCity(),
                userContactinfo.getPhone(),
                userContactinfo.getPostalcode(),
                userContactinfo.getStreetname(),
                userContactinfo.getSlackusername(),
                userContactinfo.getActiveDate(),
                useruuid);
    }
}
