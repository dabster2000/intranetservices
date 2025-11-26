CREATE ALGORITHM=UNDEFINED
    SQL SECURITY DEFINER
    VIEW `fact_user_utilization` AS
WITH user_months AS (
    SELECT
        b.`useruuid` AS user_id,
        b.`companyuuid` AS companyuuid,
        b.`year` AS year_val,
        b.`month` AS month_val,
        SUM(COALESCE(b.`gross_available_hours`, 0))    AS gross_available_hours,
        SUM(COALESCE(b.`unavailable_hours`, 0))        AS unavailable_hours,
        SUM(COALESCE(b.`vacation_hours`, 0))           AS vacation_hours,
        SUM(COALESCE(b.`sick_hours`, 0))               AS sick_hours,
        SUM(COALESCE(b.`maternity_leave_hours`, 0))    AS maternity_leave_hours,
        SUM(COALESCE(b.`non_payd_leave_hours`, 0))     AS non_payd_leave_hours,
        SUM(COALESCE(b.`paid_leave_hours`, 0))         AS paid_leave_hours
    FROM `bi_data_per_day` b
    WHERE b.`consultant_type` = 'CONSULTANT'
      AND b.`status_type` != 'TERMINATED'
    GROUP BY
        b.`useruuid`,
        b.`companyuuid`,
        b.`year`,
        b.`month`
),
     billable_hours_by_user_month AS (
         SELECT
             wf.`useruuid` AS user_id,
             YEAR(wf.`registered`) AS year_val,
             MONTH(wf.`registered`) AS month_val,
             SUM(
                     CASE
                         WHEN wf.`rate` > 0
                             AND wf.`workduration` > 0
                             THEN wf.`workduration`
                         ELSE 0
                         END
             ) AS billable_hours
         FROM `work_full` wf
         WHERE wf.`registered` IS NOT NULL
           AND wf.`workduration` > 0
           AND wf.`rate` > 0
           AND wf.`type` = 'CONSULTANT'
         GROUP BY
             wf.`useruuid`,
             YEAR(wf.`registered`),
             MONTH(wf.`registered`)
     )
SELECT
    CONCAT(
            um.user_id,
            '-',
            CONCAT(LPAD(um.year_val, 4, '0'), LPAD(um.month_val, 2, '0'))
    ) AS utilization_id,
    um.user_id          AS user_id,
    um.companyuuid      AS companyuuid,
    COALESCE(u.`primaryskilltype`, 'UD') AS practice_id,
    NULL                AS client_id,
    'OTHER'             AS sector_id,
    'PERIOD'            AS contract_type_id,
    CONCAT(
            LPAD(um.year_val, 4, '0'),
            LPAD(um.month_val, 2, '0')
    ) AS month_key,
    um.year_val         AS year,
    um.month_val        AS month_number,

    -- availability + leave breakdown (monthly)
    um.gross_available_hours         AS gross_available_hours,
    um.unavailable_hours             AS unavailable_hours,
    um.vacation_hours                AS vacation_hours,
    um.sick_hours                    AS sick_hours,
    um.maternity_leave_hours         AS maternity_leave_hours,
    um.non_payd_leave_hours          AS non_payd_leave_hours,
    um.paid_leave_hours              AS paid_leave_hours,

    -- net available & billable
    (um.gross_available_hours - um.unavailable_hours) AS net_available_hours,
    COALESCE(bh.billable_hours, 0)                   AS billable_hours,

    -- utilization: 0 if net_available_hours <= 0
    CASE
        WHEN (um.gross_available_hours - um.unavailable_hours) > 0
            THEN COALESCE(bh.billable_hours, 0)
            / (um.gross_available_hours - um.unavailable_hours)
        ELSE 0
        END AS utilization_ratio
FROM user_months um
         LEFT JOIN billable_hours_by_user_month bh
                   ON bh.user_id  = um.user_id
                       AND bh.year_val = um.year_val
                       AND bh.month_val = um.month_val
         LEFT JOIN `user` u
                   ON u.`uuid` = um.user_id
ORDER BY
    um.year_val DESC,
    um.month_val DESC,
    um.user_id;