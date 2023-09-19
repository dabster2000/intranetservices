package dk.trustworks.intranet.messaging.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

import java.time.LocalDate;

@Data
@RegisterForReflection
public class UserDateMap {
    public String useruuid;
    public LocalDate date;

    public UserDateMap() {
    }

    public UserDateMap(String useruuid, LocalDate date) {
        this.useruuid = useruuid;
        this.date = date;
    }
}
