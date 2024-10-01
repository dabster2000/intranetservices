package dk.trustworks.intranet.aggregates.sender;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;
import software.amazon.awssdk.services.sns.model.PublishResponse;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class SNSEventSender {

    public final static String UserStatusUpdatePerDayTopic = "arn:aws:sns:eu-west-1:932149427356:UserStatusUpdatePerDayTopic";
    public final static String UserSalaryUpdatePerDayTopic = "arn:aws:sns:eu-west-1:932149427356:UserSalaryUpdatePerDayTopic";
    public final static String ContractConsultantUpdatePerDayTopic = "arn:aws:sns:eu-west-1:932149427356:ContractConsultantUpdatePerDayTopic";
    public final static String WorkUpdatePerDayTopic = "arn:aws:sns:eu-west-1:932149427356:WorkUpdatePerDayTopic";

    public final static String WorkUpdateTopic = "arn:aws:sns:eu-west-1:932149427356:WorkUpdatePerDayTopic";
    public final static String UserStatusUpdateTopic = "arn:aws:sns:eu-west-1:932149427356:UserStatusUpdateTopic";
    public final static String UserSalaryUpdateTopic = "arn:aws:sns:eu-west-1:932149427356:UserSalaryUpdateTopic";
    public final static String BudgetUpdateTopic = "arn:aws:sns:eu-west-1:932149427356:UpdateBudget";
    public final static String ContractUpdateTopic = "arn:aws:sns:eu-west-1:932149427356:ContractConsultantUpdateTopic";

    @Inject
    SnsClient snsClient;

    public void sendEvent(String topic, String aggregateUUID, LocalDate aggregateDate) {
        Map<String, String> messageMap = new HashMap<>();
        messageMap.put("aggregateRootUUID", aggregateUUID);
        messageMap.put("aggregateDate", DateUtils.stringIt(aggregateDate));

        ObjectMapper objectMapper = new ObjectMapper();
        String message = null;
        try {
            message = objectMapper.writeValueAsString(messageMap);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        System.out.println("message = " + message);

        PublishRequest request = PublishRequest.builder()
                .message(message)
                .topicArn(topic)
                .build();

        PublishResponse result = snsClient.publish(request);

        System.out.println("Message sent with ID: " + result.messageId());
    }
}
