package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.AgreementContractDTO;
import dk.trustworks.intranet.contracts.dto.AgreementContractRevenueDTO;
import dk.trustworks.intranet.contracts.dto.ContractTypeContractsResponse;
import dk.trustworks.intranet.contracts.dto.ContractTypeItemDTO;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Read-optimized projections for contracts covered by one framework agreement. */
@ApplicationScoped
public class ContractTypeContractQueryService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9_]{3,50}$");

    @Inject
    EntityManager entityManager;

    public ContractTypeContractsResponse findContracts(String rawCode) {
        String code = validateCode(rawCode);
        if (ContractTypeDefinition.findByCode(code) == null) {
            throw new NotFoundException("Contract type with code '" + code + "' not found");
        }

        Map<String, ContractAccumulator> contracts = loadContractsAndParameters(code);
        List<AgreementContractDTO> allContracts = contracts.values().stream()
                .map(ContractAccumulator::toDto)
                .toList();
        List<AgreementContractRevenueDTO> topContracts = loadTopRevenue(code, contracts);

        return new ContractTypeContractsResponse(code, allContracts.size(), allContracts, topContracts);
    }

    @SuppressWarnings("unchecked")
    private Map<String, ContractAccumulator> loadContractsAndParameters(String code) {
        String sql = """
                SELECT c.uuid, c.name, c.clientuuid, cl.name, c.status,
                       cti.id, cti.name, cti.value
                FROM contracts c
                LEFT JOIN client cl ON cl.uuid = c.clientuuid
                LEFT JOIN contract_type_items cti ON cti.contractuuid = c.uuid
                WHERE c.contracttype = :code
                ORDER BY LOWER(COALESCE(c.name, '')), c.uuid,
                         LOWER(COALESCE(cti.name, '')), cti.id
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("code", code)
                .getResultList();

        Map<String, ContractAccumulator> contracts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String uuid = asString(row[0]);
            ContractAccumulator accumulator = contracts.computeIfAbsent(uuid, ignored ->
                    new ContractAccumulator(
                            uuid,
                            asString(row[1]),
                            asString(row[2]),
                            asString(row[3]),
                            contractStatus(row[4])));
            if (row[5] != null) {
                accumulator.parameters.add(new ContractTypeItemDTO(
                        ((Number) row[5]).intValue(), asString(row[6]), asString(row[7])));
            }
        }
        return contracts;
    }

    @SuppressWarnings("unchecked")
    private List<AgreementContractRevenueDTO> loadTopRevenue(
            String code, Map<String, ContractAccumulator> contracts) {
        LocalDate today = LifecycleStatus.today();
        String sql = """
                SELECT c.uuid, COALESCE(SUM(ii.hours * ii.rate), 0) AS revenue
                FROM contracts c
                LEFT JOIN invoices i
                       ON i.contractuuid = c.uuid
                      AND i.status = 'CREATED'
                      AND i.type = 'INVOICE'
                      AND i.invoicedate >= :fromDate
                      AND i.invoicedate <= :toDate
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE c.contracttype = :code
                GROUP BY c.uuid, c.name
                ORDER BY revenue DESC, LOWER(COALESCE(c.name, '')), c.uuid
                LIMIT 10
                """;
        List<Object[]> rows = entityManager.createNativeQuery(sql)
                .setParameter("code", code)
                .setParameter("fromDate", today.minusMonths(12))
                .setParameter("toDate", today)
                .getResultList();

        List<AgreementContractRevenueDTO> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            ContractAccumulator contract = contracts.get(asString(row[0]));
            if (contract == null) {
                continue;
            }
            result.add(contract.toRevenueDto(asBigDecimal(row[1])));
        }
        return List.copyOf(result);
    }

    private static String validateCode(String rawCode) {
        String code = rawCode != null ? rawCode.trim() : "";
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException(
                    "Contract type code must be 3-50 uppercase letters, numbers, or underscores");
        }
        return code;
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static BigDecimal asBigDecimal(Object value) {
        return value != null ? new BigDecimal(value.toString()) : BigDecimal.ZERO;
    }

    private static ContractStatus contractStatus(Object value) {
        return value != null ? ContractStatus.valueOf(value.toString()) : null;
    }

    private static final class ContractAccumulator {
        private final String uuid;
        private final String name;
        private final String clientUuid;
        private final String clientName;
        private final ContractStatus status;
        private final List<ContractTypeItemDTO> parameters = new ArrayList<>();

        private ContractAccumulator(String uuid, String name, String clientUuid,
                                    String clientName, ContractStatus status) {
            this.uuid = uuid;
            this.name = name;
            this.clientUuid = clientUuid;
            this.clientName = clientName;
            this.status = status;
        }

        private AgreementContractDTO toDto() {
            return new AgreementContractDTO(
                    uuid, name, clientUuid, clientName, status, List.copyOf(parameters));
        }

        private AgreementContractRevenueDTO toRevenueDto(BigDecimal revenue) {
            return new AgreementContractRevenueDTO(
                    uuid, name, clientUuid, clientName, status,
                    List.copyOf(parameters), revenue.setScale(2, java.math.RoundingMode.HALF_UP));
        }
    }
}
