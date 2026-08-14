package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.domain.user.entity.UserContactinfo;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

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
        if (User.findById(useruuid) == null) {
            // Without this check the lazy-create below hits fk_user_contactinfo_user
            // and surfaces as a 409 on a GET for a user that does not exist.
            throw new NotFoundException("User not found: " + useruuid);
        }
        UserContactinfo userContactinfo = UserContactinfo.findCurrentByUseruuid(useruuid);
        if (userContactinfo == null) {
            // PUT /users/{uuid}/contactinfo updates by useruuid and is a silent
            // no-op when no row exists, so the default row must be persisted here.
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
        // Preserve existing activeDate when not provided (BFF doesn't send it)
        LocalDate activeDate = userContactinfo.getActiveDate();
        if (activeDate == null) {
            UserContactinfo existing = UserContactinfo.findCurrentByUseruuid(useruuid);
            activeDate = existing != null ? existing.getActiveDate() : LocalDate.now();
        }

        UserContactinfo.update("city = ?1, " +
                        "phone = ?2, " +
                        "postalcode = ?3, " +
                        "streetname = ?4, " +
                        "slackusername = ?5, " +
                        "activeDate = ?6 " +
                        "where useruuid = ?7",
                userContactinfo.getCity(),
                userContactinfo.getPhone(),
                userContactinfo.getPostalcode(),
                userContactinfo.getStreetname(),
                userContactinfo.getSlackusername(),
                activeDate,
                useruuid);
    }
}
