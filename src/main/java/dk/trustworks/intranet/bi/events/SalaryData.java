package dk.trustworks.intranet.bi.events;

import dk.trustworks.intranet.domain.user.entity.Salary;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import java.util.List;

public record SalaryData(List<UserStatus> userStatusList, List<Salary> salaryList) {
}