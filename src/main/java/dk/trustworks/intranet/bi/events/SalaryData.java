package dk.trustworks.intranet.bi.events;

import dk.trustworks.intranet.userservice.model.Salary;
import dk.trustworks.intranet.userservice.model.UserStatus;
import java.util.List;

public record SalaryData(List<UserStatus> userStatusList, List<Salary> salaryList) {
}