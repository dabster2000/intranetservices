package dk.trustworks.intranet.dto;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GraphKeyValue {

    private String uuid;
    private String description;
    private double value;

    public void addValue(double value) {
        this.value += value;
    }

}
