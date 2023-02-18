package dk.trustworks.intranet.userservice.dto;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import dk.trustworks.intranet.userservice.utils.LocalDateDeserializer;
import dk.trustworks.intranet.userservice.utils.LocalDateSerializer;

import java.time.LocalDate;

public class Capacity {

    private String useruuid;

    @JsonDeserialize(using = LocalDateDeserializer.class)
    @JsonSerialize(using = LocalDateSerializer.class)
    private LocalDate month;

    private int totalAllocation;

    public Capacity() {
    }

    public Capacity(String useruuid, LocalDate month, int totalAllocation) {
        this.useruuid = useruuid;
        this.month = month;
        this.totalAllocation = totalAllocation;
    }

    public String getUseruuid() {
        return useruuid;
    }

    public void setUseruuid(String useruuid) {
        this.useruuid = useruuid;
    }

    public LocalDate getMonth() {
        return month;
    }

    public void setMonth(LocalDate month) {
        this.month = month;
    }

    public int getTotalAllocation() {
        return totalAllocation;
    }

    public void setTotalAllocation(int totalAllocation) {
        this.totalAllocation = totalAllocation;
    }

    @Override
    public String toString() {
        return "Capacity{" +
                "useruuid='" + useruuid + '\'' +
                ", month=" + month +
                ", totalAllocation=" + totalAllocation +
                '}';
    }
}