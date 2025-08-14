package dk.trustworks.intranet.batch;

import dk.trustworks.intranet.batch.model.UserDay;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.batch.api.BatchProperty;
import jakarta.batch.api.chunk.ItemReader;
import jakarta.batch.runtime.context.StepContext;
import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.jbosslog.JBossLog;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@JBossLog
@Named("userDayItemReader")
@Dependent
public class UserDayItemReader implements ItemReader {

    @Inject
    StepContext stepContext;

    @Inject @BatchProperty(name = "users")
    String usersCsv;

    @Inject @BatchProperty(name = "startMonth")
    String startStr; // ISO yyyy-MM-01

    @Inject @BatchProperty(name = "endMonth")
    String endStr;   // ISO yyyy-MM-01

    @Inject @BatchProperty(name = "partitionId")
    String partitionId;


    private List<UserDay> items;
    private int index;

    @Override
    public void open(Serializable checkpoint) {
        index = (checkpoint instanceof Integer) ? (Integer) checkpoint : 0;

        LocalDate start = (startStr == null || startStr.isBlank())
                ? LocalDate.now().minusMonths(2).withDayOfMonth(1)
                : LocalDate.parse(startStr);
        LocalDate end = (endStr == null || endStr.isBlank())
                ? LocalDate.now().plusMonths(2).withDayOfMonth(1)
                : LocalDate.parse(endStr);

        List<String> users = (usersCsv == null || usersCsv.isBlank())
                ? List.of()
                : Arrays.asList(usersCsv.split(","));

        items = new ArrayList<>();
        for (String u : users) {
            LocalDate day = start;
            while (day.isBefore(end)) {
                if (!DateUtils.isWeekend(day)) {
                    items.add(new UserDay(u, day));
                }
                day = day.plusDays(1);
            }
        }
        String pid = (partitionId == null || partitionId.isBlank()) ? "?" : partitionId;
        log.infof("UserDayItemReader initialized: partition=%s items=%d users=%d window=%s..%s",
                pid, items.size(), users.size(), start, end);
    }


    @Override
    public Object readItem() {
        if (items == null || index >= items.size()) return null;
        return items.get(index++);
    }

    @Override
    public Serializable checkpointInfo() { return index; }

    @Override
    public void close() {}
}