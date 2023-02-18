package dk.trustworks.intranet.dto;

import dk.trustworks.intranet.newsservice.model.News;
import lombok.Data;

@Data
public class RegularNews extends News {

    private String description;
    private String text;
    private String image;
    private String createdby;

}
