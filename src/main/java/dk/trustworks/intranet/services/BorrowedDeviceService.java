package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.BorrowedDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@JBossLog
public class BorrowedDeviceService {

    public List<BorrowedDevice> findBorrowedDevices(String useruuid) {
        log.debug("BorrowedDeviceService.findBorrowedDevices useruuid=" + useruuid);
        return BorrowedDevice.find("useruuid", useruuid).list();
    }

    @Transactional
    public void save(BorrowedDevice device) {
        log.debug("BorrowedDeviceService.save device=" + device);
        if (device.getUuid() == null) {
            device.setUuid(UUID.randomUUID().toString());
            BorrowedDevice.persist(device);
        } else {
            update(device);
        }
    }

    @Transactional
    public void update(BorrowedDevice device) {
        log.debug("BorrowedDeviceService.update device=" + device);
        BorrowedDevice.update("useruuid = ?1, clientuuid = ?2, type = ?3, description = ?4, serial = ?5, borrowedDate = ?6, returnedDate = ?7 where uuid = ?8",
                device.getUseruuid(), device.getClientuuid(), device.getType(), device.getDescription(), device.getSerial(),
                device.getBorrowedDate(), device.getReturnedDate(), device.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        log.debug("BorrowedDeviceService.delete uuid=" + uuid);
        BorrowedDevice.delete("uuid", uuid);
    }
}
