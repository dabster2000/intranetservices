package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.BorrowedDevice;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class BorrowedDeviceService {

    public List<BorrowedDevice> findBorrowedDevices(String useruuid) {
        return BorrowedDevice.find("useruuid", useruuid).list();
    }

    @Transactional
    public void save(BorrowedDevice device) {
        if (device.getUuid() == null) {
            device.setUuid(UUID.randomUUID().toString());
            BorrowedDevice.persist(device);
        } else {
            update(device);
        }
    }

    @Transactional
    public void update(BorrowedDevice device) {
        BorrowedDevice.update("useruuid = ?1, type = ?2, description = ?3, serial = ?4, borrowedDate = ?5, returnedDate = ?6 where uuid = ?7",
                device.getUseruuid(), device.getType(), device.getDescription(), device.getSerial(),
                device.getBorrowedDate(), device.getReturnedDate(), device.getUuid());
    }

    @Transactional
    public void delete(String uuid) {
        BorrowedDevice.delete("uuid", uuid);
    }
}
