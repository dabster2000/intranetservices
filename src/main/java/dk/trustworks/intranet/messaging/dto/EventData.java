package dk.trustworks.intranet.messaging.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EventData {
    private String aggregateRootUUID;
    private String aggregateDate;
}
