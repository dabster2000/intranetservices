package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.BorrowedDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
@ApplicationScoped
public class BorrowedDeviceService {

    public List<BorrowedDevice> findBorrowedDevices(String useruuid) {
        log.debugf("Fetching borrowed devices for user %s", useruuid);
        return BorrowedDevice.find("useruuid", useruuid).list();
    }

    @Transactional
    public void save(BorrowedDevice device) {
        if (device.getUuid() == null) {
            device.setUuid(UUID.randomUUID().toString());
            log.debugf("Persisting new borrowed device %s", device);
            BorrowedDevice.persist(device);
        } else {
            log.debugf("Device exists, updating %s", device);
            update(device);
        }
    }

    @Transactional
    public void update(BorrowedDevice device) {
        log.debugf("Updating borrowed device %s", device);
        BorrowedDevice.update("useruuid = ?1, type = ?2, description = ?3, serial = ?4, borrowedDate = ?5, returnedDate = ?6 where uuid = ?7",
                device.getUseruuid(), device.getType(), device.getDescription(), device.getSerial(),
                device.getBorrowedDate(), device.getReturnedDate(), device.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        log.debugf("Deleting borrowed device %s", uuid);
        BorrowedDevice.delete("uuid", uuid);
    }
}
