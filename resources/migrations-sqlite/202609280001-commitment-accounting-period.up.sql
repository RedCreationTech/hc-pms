-- H13c 承诺纳入会计期间封期: 为 pms_cost_commitment 增加可空 period 列(YYYY-MM), 并按创建时间回填历史承诺的会计期间.
ALTER TABLE pms_cost_commitment ADD COLUMN period VARCHAR(7);
--;;
UPDATE pms_cost_commitment SET period = substr(created_at, 1, 7) WHERE period IS NULL;
--;;
