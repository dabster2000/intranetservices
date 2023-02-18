package dk.trustworks.intranet.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class ProjectUserDateDTO {
    @NonNull String uuid;
    @NonNull String projectuuid;
    @NonNull String useruuid;
    @NonNull String date;
    String contractuuid;
    double rate;
}
