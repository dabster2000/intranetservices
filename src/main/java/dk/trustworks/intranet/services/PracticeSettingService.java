package dk.trustworks.intranet.services;

import dk.trustworks.intranet.model.PracticeSetting;
import lombok.extern.jbosslog.JBossLog;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@JBossLog
@ApplicationScoped
public class PracticeSettingService {

    private static final int DEFAULT_IT_BUDGET = 25000;

    public List<PracticeSetting> findAll() {
        return PracticeSetting.listAll();
    }

    public Optional<PracticeSetting> findBySetting(String practice, String settingKey) {
        return PracticeSetting.find("practice = ?1 and settingKey = ?2", practice, settingKey)
                .firstResultOptional();
    }

    public int getItBudget(String practice) {
        return findBySetting(practice, "it_budget")
                .map(setting -> {
                    try {
                        return Integer.parseInt(setting.getSettingValue());
                    } catch (NumberFormatException e) {
                        log.warnf("Invalid it_budget value '%s' for practice '%s', using default %d",
                                setting.getSettingValue(), practice, DEFAULT_IT_BUDGET);
                        return DEFAULT_IT_BUDGET;
                    }
                })
                .orElse(DEFAULT_IT_BUDGET);
    }

    @Transactional
    public void saveSetting(String practice, String settingKey, String settingValue, String updatedBy) {
        Optional<PracticeSetting> existing = findBySetting(practice, settingKey);
        if (existing.isPresent()) {
            PracticeSetting setting = existing.get();
            setting.setSettingValue(settingValue);
            setting.setUpdatedBy(updatedBy);
            setting.setUpdatedAt(LocalDateTime.now());
        } else {
            PracticeSetting setting = new PracticeSetting(practice, settingKey, settingValue, updatedBy);
            PracticeSetting.persist(setting);
        }
    }
}
