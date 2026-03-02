package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.domain.user.entity.UserPersonalDetails;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.NotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class UserPersonalDetailsService {

    public List<UserPersonalDetails> findAll(String useruuid) {
        return UserPersonalDetails.findByUseruuid(useruuid);
    }

    public UserPersonalDetails findCurrent(String useruuid) {
        UserPersonalDetails details = UserPersonalDetails.findActiveByUseruuid(useruuid, LocalDate.now());
        if (details == null) {
            throw new NotFoundException("No personal details found for user: " + useruuid);
        }
        return details;
    }

    @Transactional
    public UserPersonalDetails create(String useruuid, UserPersonalDetails details) {
        details.setUuid(UUID.randomUUID().toString());
        details.setUseruuid(useruuid);
        if (details.getActiveDate() == null) {
            details.setActiveDate(LocalDate.now());
        }
        UserPersonalDetails.persist(details);
        return details;
    }

    @Transactional
    public UserPersonalDetails update(String uuid, UserPersonalDetails incoming) {
        UserPersonalDetails existing = UserPersonalDetails.findById(uuid);
        if (existing == null) {
            throw new NotFoundException("UserPersonalDetails not found: " + uuid);
        }
        existing.setActiveDate(incoming.getActiveDate());
        existing.setPension(incoming.isPension());
        existing.setHealthcare(incoming.isHealthcare());
        existing.setPensiondetails(incoming.getPensiondetails());
        existing.setPhotoconsent(incoming.isPhotoconsent());
        existing.setDefects(incoming.getDefects());
        existing.setOther(incoming.getOther());
        return existing;
    }

    @Transactional
    public void delete(String uuid) {
        UserPersonalDetails existing = UserPersonalDetails.findById(uuid);
        if (existing == null) {
            throw new NotFoundException("UserPersonalDetails not found: " + uuid);
        }
        UserPersonalDetails.deleteById(uuid);
    }
}
