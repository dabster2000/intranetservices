package dk.trustworks.intranet.cultureservice.services;

import dk.trustworks.intranet.cultureservice.model.KeyPurpose;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;

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
        List<KeyPurpose> keyPurposeList = KeyPurpose.find("useruuid = ?1", useruuid).list();
        if(keyPurposeList.size()<3) {
            for (int i = 0; i < 3; i++) {
                KeyPurpose keyPurpose = new KeyPurpose(useruuid, i, "");
                create(keyPurpose);
                keyPurposeList.add(keyPurpose);
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
        KeyPurpose.update("description = ?1 where useruuid like ?2 and num = ?3", keyPurpose.getDescription(), keyPurpose.getUseruuid(), keyPurpose.getNum());
    }

}
