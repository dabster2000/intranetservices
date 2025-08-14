package dk.trustworks.intranet.aggregates.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@JBossLog
@ApplicationScoped
public class SNSEventSender {

    // Legacy topic constants retained for backward compatibility/reference only
    public static final String UserStatusUpdatePerDayTopic = "legacy-sns:UserStatusUpdatePerDayTopic";
    public static final String UserSalaryUpdatePerDayTopic = "legacy-sns:UserSalaryUpdatePerDayTopic";
    public static final String ContractConsultantUpdatePerDayTopic = "legacy-sns:ContractConsultantUpdatePerDayTopic";
    public static final String WorkUpdatePerDayTopic = "legacy-sns:WorkUpdatePerDayTopic";

    public static final String WorkUpdateTopic = "legacy-sns:WorkUpdateTopic";
    public static final String UserStatusUpdateTopic = "legacy-sns:UserStatusUpdateTopic";
    public static final String UserSalaryUpdateTopic = "legacy-sns:UserSalaryUpdateTopic";
    public static final String BudgetUpdateTopic = "legacy-sns:UpdateBudget";
    public static final String ContractUpdateTopic = "legacy-sns:ContractConsultantUpdateTopic";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // No-op implementation: SNS has been retired. This method logs and returns.
    public void sendEvent(String topic, String aggregateUUID, LocalDate aggregateDate) {
        try {
            Map<String, String> messageMap = new HashMap<>();
            messageMap.put("aggregateRootUUID", aggregateUUID);
            messageMap.put("aggregateDate", DateUtils.stringIt(aggregateDate));
            String message = objectMapper.writeValueAsString(messageMap);
            log.debugf("[NO-OP SNS] topic=%s key=%s payload=%s", topic, aggregateUUID, message);
        } catch (Exception e) {
            log.debug("[NO-OP SNS] serialization error (ignored)", e);
        }
    }
}
