-- 办公一体化 · 报销审批（MySQL）

-- 报销单（业务记录，关联 Flowable 流程实例）
CREATE TABLE IF NOT EXISTS biz_oa_reimburse (
  reimburse_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id            BIGINT DEFAULT 0,
  user_name          VARCHAR(64) NOT NULL DEFAULT '',
  amount             DECIMAL(12,2) DEFAULT 0,
  reason             VARCHAR(500) NOT NULL DEFAULT '',
  process_instance_id VARCHAR(64) NOT NULL DEFAULT '',
  status             CHAR(1) NOT NULL DEFAULT '1',
  create_time        TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP,
  update_time        TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
--;;
CREATE INDEX idx_oa_reimburse_user ON biz_oa_reimburse(user_id);
--;;
CREATE INDEX idx_oa_reimburse_pid ON biz_oa_reimburse(process_instance_id);
--;;

-- 默认报销审批流程模型
INSERT IGNORE INTO biz_bpm_model (model_id, model_key, model_name, category_id, version, form_type, form_json, bpmn_xml, deployment_id, status, create_by, create_time, remark)
VALUES (502, 'reimburseApproval', '报销审批', 1, 1, '1', '{"fields":[{"key":"amount","label":"报销金额","type":"number"},{"key":"reason","label":"报销事由","type":"textarea"}]}',
'<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" id="reimburseApprovalDef" targetNamespace="http://bpmn.io/schema/bpmn"><process id="reimburseApproval" name="报销审批" isExecutable="true"><startEvent id="start"/><userTask id="approve" name="部门经理审批" flowable:candidateUsers="admin"/><exclusiveGateway id="gw"/><endEvent id="end"/><endEvent id="rejectEnd"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/><sequenceFlow id="f2" sourceRef="approve" targetRef="gw"/><sequenceFlow id="f3" sourceRef="gw" targetRef="end"><conditionExpression xsi:type="tFormalExpression">${approved == true}</conditionExpression></sequenceFlow><sequenceFlow id="f4" sourceRef="gw" targetRef="rejectEnd"><conditionExpression xsi:type="tFormalExpression">${approved == false}</conditionExpression></sequenceFlow></process></definitions>',
NULL, '1', 'system', CURRENT_TIMESTAMP, '内置报销审批流程');
