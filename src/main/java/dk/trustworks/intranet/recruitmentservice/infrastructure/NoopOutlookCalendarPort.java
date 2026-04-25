package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class NoopOutlookCalendarPort implements OutlookCalendarPort {
}
