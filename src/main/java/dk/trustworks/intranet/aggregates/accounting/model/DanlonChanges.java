package dk.trustworks.intranet.aggregates.accounting.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DanlonChanges {
    private String danlonNumber;
    private String name;
    private String statusType;
    private String salaryNotes;
}
