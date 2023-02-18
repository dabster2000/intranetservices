package dk.trustworks.intranet.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExpenseFile {

    private String uuid;
    @ToString.Exclude
    private String expensefile;

}
