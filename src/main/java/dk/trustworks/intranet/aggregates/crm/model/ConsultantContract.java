package dk.trustworks.intranet.aggregates.crm.model;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.domain.user.entity.User;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

import java.time.LocalDate;

@Data
@NoArgsConstructor
@RequiredArgsConstructor
public class ConsultantContract {
    @NonNull String useruuid;
    @NonNull String contractuuid;
    @NonNull LocalDate fromdate;
    @NonNull LocalDate todate;
    Client client;
    User user;
    int budgetAmount;
    int actualAmount;

    public ConsultantContract(@NotNull String useruuid, @NotNull String contractuuid, @NotNull LocalDate fromdate, @NotNull LocalDate todate, Client client, User user) {
        this.useruuid = useruuid;
        this.contractuuid = contractuuid;
        this.fromdate = fromdate;
        this.todate = todate;
        this.client = client;
        this.user = user;
    }
}