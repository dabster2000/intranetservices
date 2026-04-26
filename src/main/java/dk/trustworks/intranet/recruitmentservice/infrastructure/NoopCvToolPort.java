package dk.trustworks.intranet.recruitmentservice.infrastructure;

import dk.trustworks.intranet.recruitmentservice.ports.CvToolPort;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import java.util.List;

/**
 * Fallback {@link CvToolPort} that throws on every call. Production runtime
 * uses {@link CvToolPortImpl} (higher {@code @Priority}). Tests should either
 * register a higher-priority alternative or use {@code @InjectMock CvToolPort}
 * to control behaviour deterministically.
 */
@ApplicationScoped
@Alternative
@Priority(1)  // overridden by CvToolPortImpl@Priority(10)
public class NoopCvToolPort implements CvToolPort {

    @Override
    public List<EmployeeCvSummary> findByPractice(String practiceCode, int limit) {
        throw new IllegalStateException(
            "NoopCvToolPort hit — wire CvToolPortImpl or a test fake. practiceCode=" + practiceCode);
    }

    @Override
    public List<EmployeeCvSummary> findByCareerLevelUuid(String careerLevelUuid, int limit) {
        throw new IllegalStateException(
            "NoopCvToolPort hit — wire CvToolPortImpl or a test fake. careerLevelUuid=" + careerLevelUuid);
    }
}
