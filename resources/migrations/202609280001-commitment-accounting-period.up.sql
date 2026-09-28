-- H13c 承诺纳入会计期间封期: 为 pms_cost_commitment 增加可空 period 列(YYYY-MM), 并按创建时间回填历史承诺的会计期间.
ALTER TABLE pms_cost_commitment ADD COLUMN period VARCHAR(7) NULL AFTER code;
--;;
UPDATE pms_cost_commitment SET period = DATE_FORMAT(created_at, '%Y-%m') WHERE period IS NULL;
--;;
