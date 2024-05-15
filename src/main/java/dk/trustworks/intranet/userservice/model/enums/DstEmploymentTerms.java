package dk.trustworks.intranet.userservice.model.enums;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import lombok.Getter;

@Getter
public enum DstEmploymentTerms {
    @JsonEnumDefaultValue FUNKTIONAER("Funktionær"), WORKER("Arbejder, funktionærlignende"), WORKER_OTHER("Arbejder, i øvrigt");

    private final String name;

    DstEmploymentTerms(String name) {
        this.name = name;
    }

}
