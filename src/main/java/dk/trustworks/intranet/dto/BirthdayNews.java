package dk.trustworks.intranet.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import dk.trustworks.intranet.newsservice.model.News;
import lombok.Data;

import java.time.LocalDate;

@Data
public class BirthdayNews extends News {

    private String birthdayuseruuid;

    @JsonSerialize(using = LocalDateTimeSerializer.class)
    private LocalDate birthdaydate;

}
