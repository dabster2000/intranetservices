package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.invoice.services.RegisteredDeliveryEvidenceResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Complete, non-inferential DELIVERY_EVIDENCE retention-gap recheck. */
@ApplicationScoped
public class PracticeDeliveryEvidenceRecoveryService {
    static final String SOURCE_VECTOR_SQL="""
            SELECT source_name,source_version,source_state,attempt_token,
                   recovery_target_fact_change_log_id,last_pruned_fact_change_log_id
            FROM practice_revenue_source_watermark ORDER BY source_name
            """;
    static final String BASIS_VECTOR_SQL="""
            SELECT o.generation_at,o.practice_basis_generation_id,b.full_refresh_version,
                   b.incremental_refresh_version
            FROM practice_operating_cost_publication o
            JOIN bi_refresh_watermark b ON b.pipeline_name='FACT_USER_DAY'
            WHERE o.publication_id=1 AND o.refresh_state='READY'
            """;
    static final String CONTROL_SQL="""
            SELECT refresh_enabled,revenue_recovery_owner_token
            FROM practice_contribution_publication_control WHERE control_id=1
            """;
    static final String SCOPE_SQL="""
            WITH one_hop_source AS (
                SELECT DISTINCT source_document_uuid
                FROM practice_basis_dependency_manifest_mat
                WHERE generation_id=:basis AND recognized_document_type='CREDIT_NOTE'
                  AND recognized_month>=:fromDate AND recognized_month<=:toDate
                  AND source_document_uuid IS NOT NULL
            )
            SELECT DISTINCT i.uuid,i.contractuuid,i.projectuuid,i.year,i.month
            FROM invoices i
            WHERE i.type='INVOICE' AND i.status='CREATED'
              AND ((i.invoicedate>=:fromDate AND i.invoicedate<=:toDate)
                   OR i.uuid IN (SELECT source_document_uuid FROM one_hop_source))
            ORDER BY i.uuid
            """;
    static final String LINEAGE_SQL="""
            WITH one_hop_source AS (
                SELECT DISTINCT source_document_uuid
                FROM practice_basis_dependency_manifest_mat
                WHERE generation_id=:basis AND recognized_document_type='CREDIT_NOTE'
                  AND recognized_month>=:fromDate AND recognized_month<=:toDate
                  AND source_document_uuid IS NOT NULL
            )
            SELECT i.uuid,d.invoice_item_uuid,d.work_uuid,d.registrant_uuid,
                   d.effective_consultant_uuid,d.delivery_date,d.task_uuid,d.project_uuid,
                   d.contract_uuid,d.contract_project_uuid,d.contract_consultant_uuid,
                   d.normalized_duration,d.normalized_rate,d.delivery_value,
                   d.rate_resolution_status,d.contribution_algorithm_version,
                   d.item_fingerprint,d.distribution_fingerprint,ii.hours,ii.rate,ii.origin,ii.rule_id
            FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid=i.uuid
            JOIN practice_invoice_item_delivery_source d ON d.invoice_item_uuid=ii.uuid
            WHERE i.type='INVOICE' AND i.status='CREATED'
              AND ((i.invoicedate>=:fromDate AND i.invoicedate<=:toDate)
                   OR i.uuid IN (SELECT source_document_uuid FROM one_hop_source))
            ORDER BY i.uuid,d.invoice_item_uuid,d.work_uuid
            """;
    static final String ITEM_INVENTORY_SQL="""
            WITH one_hop_source AS (
                SELECT DISTINCT source_document_uuid
                FROM practice_basis_dependency_manifest_mat
                WHERE generation_id=:basis AND recognized_document_type='CREDIT_NOTE'
                  AND recognized_month>=:fromDate AND recognized_month<=:toDate
                  AND source_document_uuid IS NOT NULL
            )
            SELECT i.uuid,ii.uuid,ii.hours,ii.rate,ii.origin,ii.rule_id
            FROM invoices i JOIN invoiceitems ii ON ii.invoiceuuid=i.uuid
            WHERE i.type='INVOICE' AND i.status='CREATED'
              AND ((i.invoicedate>=:fromDate AND i.invoicedate<=:toDate)
                   OR i.uuid IN (SELECT source_document_uuid FROM one_hop_source))
            ORDER BY i.uuid,ii.uuid
            """;
    static final String FACT_EVENTS_SQL="""
            SELECT id,change_type,source_table,source_id
            FROM fact_change_log WHERE id>:target ORDER BY id
            """;

    @Inject EntityManager em;
    @Inject RegisteredDeliveryEvidenceResolver resolver;
    @ConfigProperty(name="practices.contribution.query-timeout",defaultValue="PT2M")
    Duration queryTimeout=Duration.ofMinutes(2);

    public RecheckResult rebuild(PracticeRevenueSourceRebuildHandler.RebuildRequest request){
        if(request.recoveryTargetFactChangeLogId()==null){
            throw new IllegalArgumentException("delivery recovery target is required");
        }
        CapturedVector start=captureVector(request);
        List<DeliveryScope> scopes=loadScopes(request.fromInclusive(),request.toInclusive(),
                start.basisGenerationId());
        Map<String,List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery>> byDocument=new LinkedHashMap<>();
        int explicitAbsence=0;
        Set<String> workUuids=new HashSet<>();
        List<String> snapshotRows=new ArrayList<>();
        for(DeliveryScope scope:scopes){
            if(!scope.valid()){
                explicitAbsence++;
                snapshotRows.add("ABSENT|"+scope.canonical());
                continue;
            }
            List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery> resolved=resolver.resolve(
                    new RegisteredDeliveryEvidenceResolver.QueryInput(scope.contractUuid(),scope.projectUuid(),
                            scope.startDate(),scope.endDateExclusive()));
            byDocument.put(scope.documentUuid(),resolved);
            if(resolved.isEmpty()){
                explicitAbsence++;
                snapshotRows.add("ABSENT|"+scope.canonical());
            }
            for(var row:resolved){
                if(!Objects.equals(scope.contractUuid(),row.contractUuid())
                        ||!Objects.equals(scope.projectUuid(),row.projectUuid())
                        ||row.deliveryDate()==null||row.deliveryDate().isBefore(scope.startDate())
                        ||!row.deliveryDate().isBefore(scope.endDateExclusive())
                        ||row.workUuid()==null||!workUuids.add(row.workUuid()+"|"+scope.documentUuid())){
                    throw new IllegalStateException("DELIVERY_CANONICAL_SCOPE_INVALID");
                }
                snapshotRows.add(scope.documentUuid()+"|"+canonical(row));
            }
        }
        int missingPractice=validateEffectivePracticeCoverage(start.basisGenerationId(),
                byDocument.values().stream().flatMap(List::stream).map(
                        RegisteredDeliveryEvidenceResolver.ResolvedDelivery::workUuid).distinct().toList());
        Map<String,ItemInventory> items=loadItemInventory(request.fromInclusive(),request.toInclusive(),
                start.basisGenerationId());
        List<LineageRow> lineage=loadLineage(request.fromInclusive(),request.toInclusive(),
                start.basisGenerationId());
        LineageValidation lineageValidation=validateImmutableLineage(lineage,items,byDocument);
        explicitAbsence+=lineageValidation.explicitUnlineagedItemCount();
        Set<String> lineagedItems=lineage.stream().map(LineageRow::invoiceItemUuid).collect(
                java.util.stream.Collectors.toUnmodifiableSet());
        items.values().stream().filter(item->!lineagedItems.contains(item.invoiceItemUuid()))
                .map(item->"ABSENT_ITEM|"+item.documentUuid()+"|"+item.invoiceItemUuid())
                .forEach(snapshotRows::add);
        CapturedVector end=captureVector(request);
        if(!start.sameInputs(end))throw new IllegalStateException("DELIVERY_SOURCE_VECTOR_ADVANCED");
        BigInteger cursor=scanPostTarget(request.recoveryTargetFactChangeLogId());
        snapshotRows.sort(String::compareTo);
        return new RecheckResult(cursor,scopes.size(),items.size(),lineage.size(),explicitAbsence,missingPractice,
                sha256(String.join("\n",snapshotRows)));
    }

    CapturedVector captureVector(PracticeRevenueSourceRebuildHandler.RebuildRequest request){
        @SuppressWarnings("unchecked") List<Object[]> rows=timed(SOURCE_VECTOR_SQL).getResultList();
        if(rows.size()!=PracticeRevenueDirtyMarker.Source.values().length)
            throw new IllegalStateException("DELIVERY_SOURCE_VECTOR_INCOMPLETE");
        Map<String,String> versions=new LinkedHashMap<>();
        BigInteger pruned=null;
        for(Object[] row:rows){
            String source=text(row[0]);
            String state=text(row[2]);
            if("DELIVERY_EVIDENCE".equals(source)){
                if(!"RUNNING".equals(state)||!request.recoveryToken().equals(text(row[3]))
                        ||!request.recoveryTargetFactChangeLogId().equals(integer(row[4])))
                    throw new IllegalStateException("DELIVERY_RECOVERY_OWNER_LOST");
                pruned=integer(row[5]);
            }else if(!"READY".equals(state)){
                throw new IllegalStateException("DELIVERY_OTHER_SOURCE_NOT_READY");
            }
            versions.put(source,integer(row[1]).toString()+"|"+state);
        }
        if(pruned!=null&&pruned.compareTo(request.recoveryTargetFactChangeLogId())>0)
            throw new IllegalStateException("DELIVERY_RETENTION_ADVANCED");
        Object[] basis=(Object[])timed(BASIS_VECTOR_SQL).getSingleResult();
        if(basis[0]==null||basis[1]==null)throw new IllegalStateException("DELIVERY_BASIS_UNAVAILABLE");
        Object[] control=(Object[])timed(CONTROL_SQL).getSingleResult();
        if(control.length<2||!isFalse(control[0])||control[1]!=null)
            throw new IllegalStateException("DELIVERY_BUILD_CONTROL_CHANGED");
        return new CapturedVector(Map.copyOf(versions),text(basis[0]),text(basis[1]),integer(basis[2]),
                integer(basis[3]),pruned);
    }

    @SuppressWarnings("unchecked")
    List<DeliveryScope> loadScopes(LocalDate from,LocalDate to,String basis){
        List<Object[]> rows=timed(SCOPE_SQL).setParameter("fromDate",from)
                .setParameter("toDate",to).setParameter("basis",basis).getResultList();
        List<DeliveryScope> result=new ArrayList<>();
        for(Object[] row:rows){
            int year=row[3] instanceof Number n?n.intValue():0;
            int month=row[4] instanceof Number n?n.intValue():0;
            LocalDate start=null;
            if(year>0&&month>=1&&month<=12)start=LocalDate.of(year,month,1);
            result.add(new DeliveryScope(text(row[0]),text(row[1]),text(row[2]),start,
                    start==null?null:start.plusMonths(1)));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    List<LineageRow> loadLineage(LocalDate from,LocalDate to,String basis){
        List<Object[]> rows=timed(LINEAGE_SQL).setParameter("fromDate",from)
                .setParameter("toDate",to).setParameter("basis",basis).getResultList();
        List<LineageRow> result=new ArrayList<>();
        for(Object[] r:rows)result.add(new LineageRow(text(r[0]),text(r[1]),text(r[2]),text(r[3]),
                text(r[4]),date(r[5]),text(r[6]),text(r[7]),text(r[8]),text(r[9]),text(r[10]),
                decimal(r[11]),decimal(r[12]),decimal(r[13]),text(r[14]),text(r[15]),text(r[16]),
                text(r[17]),decimal(r[18]),decimal(r[19]),text(r[20]),text(r[21])));
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    Map<String,ItemInventory> loadItemInventory(LocalDate from,LocalDate to,String basis){
        List<Object[]> rows=timed(ITEM_INVENTORY_SQL).setParameter("fromDate",from)
                .setParameter("toDate",to).setParameter("basis",basis).getResultList();
        Map<String,ItemInventory> result=new LinkedHashMap<>();
        for(Object[] row:rows){
            ItemInventory item=new ItemInventory(text(row[0]),text(row[1]),deliveryOperand(row[2]),
                    deliveryOperand(row[3]),text(row[4]),text(row[5]));
            if(item.invoiceItemUuid()==null||item.documentUuid()==null
                    ||result.putIfAbsent(item.invoiceItemUuid(),item)!=null){
                throw new IllegalStateException("DELIVERY_ITEM_INVENTORY_INVALID");
            }
        }
        return Map.copyOf(result);
    }

    int validateEffectivePracticeCoverage(String basisGeneration,List<String> workUuids){
        if(workUuids.isEmpty())return 0;
        int missing=0;
        for(int offset=0;offset<workUuids.size();offset+=500){
            List<String> chunk=workUuids.subList(offset,Math.min(workUuids.size(),offset+500));
            @SuppressWarnings("unchecked") List<Object[]> rows=timed("""
                    SELECT w.uuid,COUNT(b.user_uuid)
                    FROM work w LEFT JOIN practice_user_effective_basis_mat b
                      ON b.generation_id=:basis
                     AND b.user_uuid=COALESCE(NULLIF(TRIM(w.workas),''),w.useruuid)
                     AND w.registered>=b.effective_from_date
                     AND w.registered<b.effective_to_date_exclusive
                    WHERE w.uuid IN (:workUuids) GROUP BY w.uuid
                    """).setParameter("basis",basisGeneration).setParameter("workUuids",chunk).getResultList();
            if(rows.size()!=chunk.size())throw new IllegalStateException("DELIVERY_WORK_DISAPPEARED");
            for(Object[] row:rows){
                int count=((Number)row[1]).intValue();
                if(count>1)throw new IllegalStateException("DELIVERY_EFFECTIVE_PRACTICE_AMBIGUOUS");
                if(count==0)missing++;
            }
        }
        return missing;
    }

    static LineageValidation validateImmutableLineage(
            List<LineageRow> lineage,
            Map<String,ItemInventory> itemInventory,
            Map<String,List<RegisteredDeliveryEvidenceResolver.ResolvedDelivery>> byDocument){
        Map<String,List<LineageRow>> byItem=new LinkedHashMap<>();
        for(LineageRow row:lineage){
            ItemInventory item=itemInventory.get(row.invoiceItemUuid());
            if(item==null||!Objects.equals(item.documentUuid(),row.documentUuid()))
                throw new IllegalStateException("DELIVERY_LINEAGE_OUTSIDE_ITEM_SCOPE");
            byItem.computeIfAbsent(row.invoiceItemUuid(),ignored->new ArrayList<>()).add(row);
        }
        for(List<LineageRow> rows:byItem.values()){
            rows.sort(Comparator.comparing(LineageRow::workUuid));
            LineageRow first=rows.getFirst();
            ItemInventory itemInventoryRow=itemInventory.get(first.invoiceItemUuid());
            if(rows.stream().anyMatch(row->!"PRACTICE_DELIVERY_LINEAGE_V1".equals(row.algorithmVersion())))
                throw new IllegalStateException("DELIVERY_LINEAGE_ALGORITHM_INVALID");
            Set<String> work=new HashSet<>();
            BigDecimal duration=BigDecimal.ZERO.setScale(6);
            BigDecimal value=BigDecimal.ZERO.setScale(12);
            List<PracticeRevenueMaterializationService.StoredDelivery> stored=new ArrayList<>();
            Map<String,RegisteredDeliveryEvidenceResolver.ResolvedDelivery> current=new HashMap<>();
            for(var resolved:byDocument.getOrDefault(first.documentUuid(),List.of()))
                if(current.putIfAbsent(resolved.workUuid(),resolved)!=null)
                    throw new IllegalStateException("DELIVERY_CURRENT_EVIDENCE_DUPLICATE");
            for(LineageRow row:rows){
                if(!work.add(row.workUuid())||!same(row,current.get(row.workUuid())))
                    throw new IllegalStateException("DELIVERY_LINEAGE_CURRENT_MISMATCH");
                if(row.normalizedDuration()==null||row.normalizedRate()==null||row.deliveryValue()==null
                        ||!"RESOLVED".equals(row.rateStatus()))
                    throw new IllegalStateException("DELIVERY_LINEAGE_ROW_INVALID");
                duration=duration.add(row.normalizedDuration());
                value=value.add(row.deliveryValue());
                stored.add(stored(row,itemInventoryRow));
            }
            String distribution=PracticeRevenueMaterializationService.deliveryDistributionFingerprint(stored);
            if(rows.stream().anyMatch(row->!distribution.equals(row.distributionFingerprint())))
                throw new IllegalStateException("DELIVERY_DISTRIBUTION_FINGERPRINT_MISMATCH");
            String item=PracticeRevenueMaterializationService.deliveryItemFingerprint(stored.getFirst(),distribution);
            if(rows.stream().anyMatch(row->!item.equals(row.itemFingerprint()))
                    ||duration.compareTo(itemInventoryRow.itemHours())!=0
                    ||value.compareTo(itemInventoryRow.itemHours().multiply(itemInventoryRow.itemRate())
                    .setScale(12,RoundingMode.HALF_UP))!=0)
                throw new IllegalStateException("DELIVERY_LINEAGE_ITEM_CONSERVATION_FAILED");
        }
        int explicitUnlineaged=itemInventory.size()-byItem.size();
        if(explicitUnlineaged<0||itemInventory.size()!=byItem.size()+explicitUnlineaged)
            throw new IllegalStateException("DELIVERY_ITEM_COVERAGE_MISMATCH");
        return new LineageValidation(itemInventory.size(),byItem.size(),explicitUnlineaged);
    }

    BigInteger scanPostTarget(BigInteger target){
        @SuppressWarnings("unchecked") List<Object[]> events=timed(FACT_EVENTS_SQL)
                .setParameter("target",target).getResultList();
        BigInteger cursor=target;
        for(Object[] event:events){
            cursor=cursor.max(integer(event[0]));
            String type=text(event[1]);
            if("WORK".equals(type)||"CONTRACT".equals(type))
                throw new IllegalStateException("DELIVERY_RELEVANT_EVENT_AFTER_TARGET");
        }
        return cursor;
    }

    private Query timed(String sql){
        if(queryTimeout==null||queryTimeout.isZero()||queryTimeout.isNegative()
                ||queryTimeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE))>0)
            throw new IllegalStateException("invalid delivery recovery query timeout");
        return em.createNativeQuery(sql).setHint("jakarta.persistence.query.timeout",
                Math.toIntExact(queryTimeout.toMillis()));
    }

    private static boolean same(LineageRow row,RegisteredDeliveryEvidenceResolver.ResolvedDelivery current){
        return current!=null&&Objects.equals(row.registrantUuid(),current.registrantUuid())
                &&Objects.equals(row.effectiveConsultantUuid(),current.effectiveConsultantUuid())
                &&Objects.equals(row.deliveryDate(),current.deliveryDate())
                &&Objects.equals(row.taskUuid(),current.taskUuid())&&Objects.equals(row.projectUuid(),current.projectUuid())
                &&Objects.equals(row.contractUuid(),current.contractUuid())
                &&Objects.equals(row.contractProjectUuid(),current.contractProjectUuid())
                &&Objects.equals(row.contractConsultantUuid(),current.contractConsultantUuid())
                &&Objects.equals(row.normalizedDuration(),current.normalizedDuration())
                &&Objects.equals(row.normalizedRate(),current.normalizedRate())
                &&Objects.equals(row.deliveryValue(),current.deliveryValue())
                &&Objects.equals(row.rateStatus(),current.rateResolutionStatus().name());
    }
    private static PracticeRevenueMaterializationService.StoredDelivery stored(
            LineageRow row,ItemInventory item){
        return new PracticeRevenueMaterializationService.StoredDelivery(row.invoiceItemUuid(),row.workUuid(),
                row.registrantUuid(),row.effectiveConsultantUuid(),row.deliveryDate(),row.taskUuid(),
                row.projectUuid(),row.contractUuid(),row.contractProjectUuid(),row.contractConsultantUuid(),
                row.normalizedDuration(),row.normalizedRate(),row.deliveryValue(),row.rateStatus(),
                row.algorithmVersion(),row.itemFingerprint(),row.distributionFingerprint(),item.itemHours(),
                item.itemRate(),item.itemOrigin(),item.itemRuleId(),null,null,null);
    }
    private static String canonical(RegisteredDeliveryEvidenceResolver.ResolvedDelivery row){
        return String.join("|",canonicalText(row.workUuid()),canonicalText(row.registrantUuid()),
                canonicalText(row.effectiveConsultantUuid()),canonicalText(row.deliveryDate()),
                canonicalText(row.taskUuid()),canonicalText(row.projectUuid()),canonicalText(row.contractUuid()),
                canonicalText(row.contractProjectUuid()),canonicalText(row.contractConsultantUuid()),
                canonicalText(row.normalizedDuration()),canonicalText(row.normalizedRate()),
                row.rateResolutionStatus().name());
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private static String text(Object value){return value==null?null:value.toString();}
    private static String canonicalText(Object value){return String.valueOf(value);}
    private static BigInteger integer(Object value){return value instanceof BigInteger i?i:new BigInteger(value.toString());}
    private static BigDecimal decimal(Object value){return value==null?null:value instanceof BigDecimal d?d:new BigDecimal(value.toString());}
    private static BigDecimal deliveryOperand(Object value){
        if(value==null)return null;
        double parsed=value instanceof Number number?number.doubleValue():Double.parseDouble(value.toString());
        if(!Double.isFinite(parsed))throw new IllegalStateException("DELIVERY_ITEM_OPERAND_INVALID");
        return BigDecimal.valueOf(parsed).setScale(6,RoundingMode.HALF_UP);
    }
    private static boolean isFalse(Object value){
        return value instanceof Boolean bool?!bool:value instanceof Number number&&number.intValue()==0;
    }
    private static LocalDate date(Object value){return value instanceof LocalDate d?d:value instanceof java.sql.Date d?d.toLocalDate():LocalDate.parse(value.toString());}

    record DeliveryScope(String documentUuid,String contractUuid,String projectUuid,
                         LocalDate startDate,LocalDate endDateExclusive){
        boolean valid(){return contractUuid!=null&&!contractUuid.isBlank()&&projectUuid!=null&&!projectUuid.isBlank()
                &&startDate!=null&&endDateExclusive!=null&&endDateExclusive.isAfter(startDate);}
        String canonical(){return String.join("|",canonicalText(documentUuid),canonicalText(contractUuid),
                canonicalText(projectUuid),canonicalText(startDate),canonicalText(endDateExclusive));}
    }
    record LineageRow(String documentUuid,String invoiceItemUuid,String workUuid,String registrantUuid,
                      String effectiveConsultantUuid,LocalDate deliveryDate,String taskUuid,String projectUuid,
                      String contractUuid,String contractProjectUuid,String contractConsultantUuid,
                      BigDecimal normalizedDuration,BigDecimal normalizedRate,BigDecimal deliveryValue,
                      String rateStatus,String algorithmVersion,String itemFingerprint,
                      String distributionFingerprint,BigDecimal itemHours,BigDecimal itemRate,
                      String itemOrigin,String itemRuleId){}
    record ItemInventory(String documentUuid,String invoiceItemUuid,BigDecimal itemHours,BigDecimal itemRate,
                         String itemOrigin,String itemRuleId){
        ItemInventory{
            if(itemHours==null||itemRate==null)throw new IllegalStateException("DELIVERY_ITEM_OPERAND_MISSING");
        }
    }
    record LineageValidation(int scopedItemCount,int lineagedItemCount,int explicitUnlineagedItemCount){}
    record CapturedVector(Map<String,String> versions,String costGeneration,String basisGenerationId,
                          BigInteger fullVersion,BigInteger incrementalVersion,BigInteger pruned){
        boolean sameInputs(CapturedVector other){return versions.equals(other.versions)
                &&Objects.equals(costGeneration,other.costGeneration)&&Objects.equals(basisGenerationId,other.basisGenerationId)
                &&fullVersion.equals(other.fullVersion)&&incrementalVersion.equals(other.incrementalVersion)
                &&Objects.equals(pruned,other.pruned);}
    }
    public record RecheckResult(BigInteger observedFactChangeLogId,int scopeCount,int scopedItemCount,
                                int lineageRowCount,
                                int explicitAbsenceCount,int missingEffectivePracticeCount,String snapshotFingerprint){}
}
