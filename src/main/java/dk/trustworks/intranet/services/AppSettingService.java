package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.AppSetting;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class AppSettingService {

    public List<AppSetting> findByCategory(String category) {
        return AppSetting.list("category", category);
    }

    public Optional<AppSetting> findByKey(String settingKey) {
        return AppSetting.find("settingKey", settingKey).firstResultOptional();
    }

    @Transactional
    public void saveSetting(String settingKey, String settingValue, String category, String updatedBy) {
        Optional<AppSetting> existing = findByKey(settingKey);
        if (existing.isPresent()) {
            AppSetting setting = existing.get();
            setting.setSettingValue(settingValue);
            setting.setCategory(category);
            setting.setUpdatedBy(updatedBy);
            setting.setUpdatedAt(LocalDateTime.now());
        } else {
            AppSetting setting = new AppSetting(settingKey, settingValue, category, updatedBy);
            AppSetting.persist(setting);
        }
    }
}
