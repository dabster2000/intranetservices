package dk.trustworks.intranet.aggregateservices.v2;

import dk.trustworks.intranet.userservice.model.User;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;

import static dk.trustworks.intranet.utils.DateUtils.stringIt;

@ApplicationScoped
public class UserStatusService {
    @Inject
    EntityManager em;

    public List<User> findByDate(LocalDate date) {
        return em.createNativeQuery("SELECT u.uuid, u.active, u.created, u.email, u.firstname, u.lastname, " +
                "u.gender, u.type, u.password, u.username, u.slackusername, u.birthday, u.cpr, u.phone, u.pension, " +
                "u.healthcare, u.pensiondetails, u.defects, u.photoconsent, u.other, u.primaryskilltype, us.status, " +
                "us.allocation, us.type, s.salary " +
                "FROM user u " +
                "JOIN ( " +
                "    SELECT useruuid, " +
                "           MAX(statusdate) AS max_statusdate " +
                "    FROM userstatus " +
                "    WHERE statusdate <= '"+ stringIt(date) +"' " +
                "    GROUP BY useruuid " +
                ") max_status ON u.uuid = max_status.useruuid " +
                "JOIN userstatus us ON u.uuid = us.useruuid AND max_status.max_statusdate = us.statusdate " +
                "JOIN ( " +
                "    SELECT useruuid, " +
                "           MAX(activefrom) AS max_statusdate " +
                "    FROM salary " +
                "    WHERE activefrom <= '"+ stringIt(date) +"' " +
                "    GROUP BY useruuid " +
                ") max_salary ON u.uuid = max_salary.useruuid " +
                "JOIN salary s ON u.uuid = s.useruuid AND max_salary.max_statusdate = s.activefrom; ", User.class).getResultList();
    }
}
