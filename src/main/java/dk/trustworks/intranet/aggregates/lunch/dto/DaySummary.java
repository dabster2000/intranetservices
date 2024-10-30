package dk.trustworks.intranet.aggregates.lunch.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DaySummary {

    public String weekday;
    public int totalMeat;
    public int totalVegetarian;
    public int totalAllergyBowl;
    public int reservedMeat;
    public int reservedVegetarian;
    public int reservedAllergyBowl;
    public int totalBreakfast;
    public int guestMeat;
    public int guestVegetarian;
    public int guestAllergyBowl;
    public int bufferMeat;
    public int bufferVegetarian;
    public int bufferAllergyBowl;

}
