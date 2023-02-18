package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.UserContactinfo;

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import java.util.UUID;

@ApplicationScoped
public class UserContactInfoService {

    @Transactional
    public UserContactinfo findOne(String useruuid) {
        UserContactinfo userContactinfo = UserContactinfo.findByUseruuid(useruuid);
        if(userContactinfo == null) {
            userContactinfo = new UserContactinfo(UUID.randomUUID().toString(), "", "" ,"" ,"", useruuid);
            UserContactinfo.persist(userContactinfo);
        }
        return userContactinfo;
    }

    @Transactional
    public void update(String useruuid, UserContactinfo userContactinfo) {
        UserContactinfo.update("city = ?1, " +
                        "phone = ?2, " +
                        "postalcode = ?3, " +
                        "street = ?4 " +
                        "where uuid like ?5 and useruuid like ?6 ",
                userContactinfo.getCity(),
                userContactinfo.getPhone(),
                userContactinfo.getPostalcode(),
                userContactinfo.getStreetname(),
                userContactinfo.getUuid(),
                useruuid);
    }
}