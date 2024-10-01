package dk.trustworks.intranet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KeyDateValueListDTO {
    private String key;
    private List<DateValueDTO> dateValueDTOList;
}
