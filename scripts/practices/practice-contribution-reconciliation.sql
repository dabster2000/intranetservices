-- Read-only Practices contribution reconciliation. Bind :generation_id,
-- :current_start_month, :current_end_month, :prior_start_month and :prior_end_month.
SET STATEMENT max_statement_time = 120 FOR
WITH item_controls AS (
    SELECT
        i.generation_id,
        i.item_control_key,
        i.source_document_uuid,
        i.recognized_month,
        i.row_kind,
        i.control_source,
        i.valuation_status,
        i.item_control_dkk,
        i.document_control_dkk,
        i.document_gl_revenue_dkk,
        i.item_cent_adjustment_dkk,
        i.duplicate_risk_status
    FROM fact_practice_net_revenue_item_mat i
    WHERE i.generation_id = :generation_id
      AND i.recognized_month BETWEEN :prior_start_month AND :current_end_month
),
allocation_by_item AS (
    SELECT
        a.generation_id,
        a.item_control_key,
        SUM(a.allocation_dkk) AS allocation_dkk,
        SUM(a.segment_id = 'UNASSIGNED') AS unassigned_allocation_count
    FROM fact_practice_net_revenue_allocation_mat a
    WHERE a.generation_id = :generation_id
    GROUP BY a.generation_id, a.item_control_key
),
reconciled_items AS (
    SELECT
        i.*,
        COALESCE(a.allocation_dkk, 0) AS allocation_dkk,
        COALESCE(a.unassigned_allocation_count, 0) AS unassigned_allocation_count
    FROM item_controls i
    LEFT JOIN allocation_by_item a
      ON a.generation_id = i.generation_id
     AND a.item_control_key = i.item_control_key
),
periods AS (
    SELECT 'CURRENT' AS period_name,
           CAST(:current_start_month AS DATE) AS start_month,
           CAST(:current_end_month AS DATE) AS end_month
    UNION ALL
    SELECT 'PRIOR', CAST(:prior_start_month AS DATE), CAST(:prior_end_month AS DATE)
),
item_period_summary AS (
    SELECT
        p.period_name,
        COUNT(DISTINCT i.source_document_uuid) AS source_document_count,
        SUM(i.row_kind = 'SOURCE_ITEM') AS source_item_count,
        SUM(i.row_kind = 'SOURCE_ITEM'
            AND (i.item_control_dkk IS NOT NULL
                 OR i.valuation_status = 'CONTROLLED_BY_DOCUMENT_RESIDUAL')) AS valued_item_count,
        SUM(i.row_kind = 'DOCUMENT_RESIDUAL') AS residual_count,
        SUM(i.unassigned_allocation_count) AS unassigned_allocation_count,
        COUNT(DISTINCT CASE WHEN i.duplicate_risk_status <> 'NONE'
                            THEN i.source_document_uuid END) AS duplicate_risk_document_count
    FROM periods p
    LEFT JOIN reconciled_items i
      ON i.recognized_month BETWEEN p.start_month AND p.end_month
    GROUP BY p.period_name
),
controlled_documents AS (
    SELECT
        i.generation_id,
        i.source_document_uuid,
        MIN(i.recognized_month) AS recognized_month,
        MAX(i.document_control_dkk) AS document_gl_revenue_dkk,
        COUNT(DISTINCT i.recognized_month) AS recognized_month_value_count,
        COUNT(DISTINCT i.document_control_dkk) AS document_control_value_count,
        SUM(CASE
                WHEN i.document_gl_revenue_dkk IS NULL
                  OR i.item_cent_adjustment_dkk IS NULL
                  OR i.document_control_dkk IS NULL
                  OR i.document_gl_revenue_dkk + i.item_cent_adjustment_dkk
                     <> i.document_control_dkk
                THEN 1 ELSE 0
            END) AS invalid_cent_evidence_row_count
    FROM item_controls i
    WHERE i.control_source = 'ECONOMIC_GL'
      AND i.valuation_status = 'CONFIRMED_GL'
      AND i.item_control_dkk IS NOT NULL
      AND i.row_kind IN ('SOURCE_ITEM', 'DOCUMENT_RESIDUAL')
    GROUP BY i.generation_id, i.source_document_uuid
),
controlled_document_allocations AS (
    SELECT
        i.generation_id,
        i.source_document_uuid,
        SUM(COALESCE(i.allocation_dkk, 0)) AS allocated_revenue_dkk
    FROM reconciled_items i
    WHERE i.control_source = 'ECONOMIC_GL'
      AND i.valuation_status = 'CONFIRMED_GL'
      AND i.item_control_dkk IS NOT NULL
      AND i.row_kind IN ('SOURCE_ITEM', 'DOCUMENT_RESIDUAL')
    GROUP BY i.generation_id, i.source_document_uuid
),
gl_period_summary AS (
    SELECT
        p.period_name,
        SUM(d.document_gl_revenue_dkk) AS confirmed_gl_control_dkk,
        SUM(a.allocated_revenue_dkk) AS confirmed_gl_allocation_dkk,
        SUM(a.allocated_revenue_dkk) - SUM(d.document_gl_revenue_dkk) AS difference_dkk,
        COUNT(*) AS gl_document_count,
        SUM(d.recognized_month_value_count <> 1
            OR d.document_control_value_count <> 1
            OR d.invalid_cent_evidence_row_count <> 0) AS invalid_gl_document_count
    FROM periods p
    JOIN controlled_documents d
      ON d.recognized_month BETWEEN p.start_month AND p.end_month
    JOIN controlled_document_allocations a
      ON a.generation_id = d.generation_id
     AND a.source_document_uuid = d.source_document_uuid
    GROUP BY p.period_name
)
SELECT
    :generation_id AS generation_id,
    p.period_name,
    g.confirmed_gl_control_dkk,
    g.confirmed_gl_allocation_dkk,
    g.difference_dkk,
    COALESCE(g.gl_document_count, 0) AS gl_document_count,
    COALESCE(g.invalid_gl_document_count, 0) AS invalid_gl_document_count,
    COALESCE(i.source_document_count, 0) AS source_document_count,
    COALESCE(i.source_item_count, 0) AS source_item_count,
    COALESCE(i.valued_item_count, 0) AS valued_item_count,
    COALESCE(i.residual_count, 0) AS residual_count,
    COALESCE(i.unassigned_allocation_count, 0) AS unassigned_allocation_count,
    COALESCE(i.duplicate_risk_document_count, 0) AS duplicate_risk_document_count
FROM periods p
LEFT JOIN item_period_summary i ON i.period_name = p.period_name
LEFT JOIN gl_period_summary g ON g.period_name = p.period_name
ORDER BY FIELD(p.period_name, 'CURRENT', 'PRIOR');

SET STATEMENT max_statement_time = 120 FOR
SELECT
    p.publication_key,
    p.status,
    p.published_generation_id,
    p.paired_cost_generation_at,
    p.practice_basis_generation_id,
    p.item_control_total_dkk,
    p.allocation_total_dkk,
    p.reconciliation_gap_dkk,
    c.contribution_serving_enabled,
    c.legacy_cost_serving_enabled,
    o.refresh_state AS cost_publication_state,
    o.generation_at AS cost_generation_at,
    o.practice_basis_generation_id AS cost_basis_generation_id,
    o.latest_cost_basis_request_id,
    o.certified_cost_basis_request_id
FROM practice_revenue_publication p
CROSS JOIN practice_contribution_publication_control c
CROSS JOIN practice_operating_cost_publication o
WHERE p.publication_key = 'PRACTICE_CONTRIBUTION'
  AND c.control_id = 1
  AND o.publication_id = 1;
