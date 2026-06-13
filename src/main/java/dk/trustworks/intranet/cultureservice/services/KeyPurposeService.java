package dk.trustworks.intranet.cultureservice.services;

import dk.trustworks.intranet.cultureservice.model.KeyPurpose;
import dk.trustworks.intranet.domain.user.entity.User;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Created by hans on 23/06/2017.
 */

@ApplicationScoped
public class KeyPurposeService {

    public List<KeyPurpose> findAll() {
        return KeyPurpose.findAll().list();
    }

    @Transactional
    public List<KeyPurpose> findByUseruuid(String useruuid) {
        if (User.count("uuid = ?1", useruuid) == 0) {
            throw new BadRequestException("Unknown useruuid: " + useruuid);
        }
        List<KeyPurpose> keyPurposeList = KeyPurpose.find("useruuid = ?1", useruuid).list();
        // Lazily provision the three key purposes using a 1-based scheme (1, 2, 3).
        // Only missing slots are created, so a user with a partial set is completed
        // without ever producing duplicate num values.
        if (keyPurposeList.size() < 3) {
            Set<Integer> existingNums = keyPurposeList.stream()
                    .map(KeyPurpose::getNum)
                    .collect(Collectors.toSet());
            for (int num = 1; num <= 3; num++) {
                if (!existingNums.contains(num)) {
                    KeyPurpose keyPurpose = new KeyPurpose(useruuid, num, "");
                    create(keyPurpose);
                    keyPurposeList.add(keyPurpose);
                }
            }
        }
        return keyPurposeList;
    }

    public KeyPurpose findByUseruuidAndNum(String useruuid, int num) {
        return KeyPurpose.find("useruuid = ?1 and num = ?2", useruuid, num).firstResult();
    }

    @Transactional
    public void create(KeyPurpose keyPurpose) {
        KeyPurpose.persist(keyPurpose);
    }

    @Transactional
    public void update(KeyPurpose keyPurpose) {
        KeyPurpose.update("description = ?1, meetingNotes = ?2 where useruuid like ?3 and num = ?4",
            keyPurpose.getDescription(), keyPurpose.getMeetingNotes(), keyPurpose.getUseruuid(), keyPurpose.getNum());
    }

}
