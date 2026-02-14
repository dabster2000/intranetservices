package dk.trustworks.intranet.userservice.services;

import dk.trustworks.intranet.userservice.model.UserSetting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class UserSettingService {

    /**
     * Find all settings for a specific user.
     *
     * @param userUuid User UUID
     * @return List of user settings
     */
    public List<UserSetting> findAllByUser(String userUuid) {
        return UserSetting.find("userUuid", userUuid).list();
    }

    /**
     * Find a specific setting for a user by key.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @return Optional containing the setting if found
     */
    public Optional<UserSetting> findByUserAndKey(String userUuid, String key) {
        return UserSetting.find("userUuid = ?1 and settingKey = ?2", userUuid, key)
                .firstResultOptional();
    }

    /**
     * Create or update a user setting.
     * If the setting already exists, it will be updated.
     * If it doesn't exist, a new setting will be created.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @param value Setting value
     * @return The created or updated UserSetting
     */
    @Transactional
    public UserSetting upsert(String userUuid, String key, String value) {
        log.infof("Upserting setting: userUuid=%s, key=%s", userUuid, key);

        Optional<UserSetting> existingOpt = findByUserAndKey(userUuid, key);

        if (existingOpt.isPresent()) {
            // Update existing setting
            UserSetting existing = existingOpt.get();
            existing.setSettingValue(value);
            existing.persist();
            log.infof("Updated existing setting: id=%d", existing.getId());
            return existing;
        } else {
            // Create new setting
            UserSetting newSetting = new UserSetting();
            newSetting.setUserUuid(userUuid);
            newSetting.setSettingKey(key);
            newSetting.setSettingValue(value);
            newSetting.persist();
            log.infof("Created new setting: id=%d", newSetting.getId());
            return newSetting;
        }
    }

    /**
     * Delete a specific user setting.
     *
     * @param userUuid User UUID
     * @param key Setting key
     * @return true if setting was deleted, false if it didn't exist
     */
    @Transactional
    public boolean delete(String userUuid, String key) {
        log.infof("Deleting setting: userUuid=%s, key=%s", userUuid, key);

        long deletedCount = UserSetting.delete("userUuid = ?1 and settingKey = ?2", userUuid, key);

        if (deletedCount > 0) {
            log.infof("Deleted setting successfully");
            return true;
        } else {
            log.infof("Setting not found, nothing deleted");
            return false;
        }
    }
}
