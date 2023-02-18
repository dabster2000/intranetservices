package dk.trustworks.intranet.dto;

import dk.trustworks.intranet.newsservice.model.News;
import lombok.Data;

@Data
public class BannerNews extends News {

    private String text;

}
