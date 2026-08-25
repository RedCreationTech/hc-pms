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
'<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="reimburseApprovalDef" targetNamespace="http://bpmn.io/schema/bpmn"><process id="reimburseApproval" name="报销审批" isExecutable="true"><startEvent id="start"/><userTask id="approve" name="部门经理审批" flowable:candidateUsers="admin"/><exclusiveGateway id="gw0"/><sequenceFlow id="in_approve" sourceRef="start" targetRef="approve"/><sequenceFlow id="in_gw0" sourceRef="approve" targetRef="gw0"/><sequenceFlow id="fwd_gw0" sourceRef="gw0" targetRef="finance"><conditionExpression xsi:type="tFormalExpression">${approved == true}</conditionExpression></sequenceFlow><sequenceFlow id="rej_gw0" sourceRef="gw0" targetRef="rejectEnd"><conditionExpression xsi:type="tFormalExpression">${approved == false}</conditionExpression></sequenceFlow><userTask id="finance" name="财务审核" flowable:candidateUsers="admin"/><exclusiveGateway id="gw1"/><sequenceFlow id="in_finance" sourceRef="gw0" targetRef="finance"/><sequenceFlow id="in_gw1" sourceRef="finance" targetRef="gw1"/><sequenceFlow id="fwd_gw1" sourceRef="gw1" targetRef="end"><conditionExpression xsi:type="tFormalExpression">${approved == true}</conditionExpression></sequenceFlow><sequenceFlow id="rej_gw1" sourceRef="gw1" targetRef="rejectEnd"><conditionExpression xsi:type="tFormalExpression">${approved == false}</conditionExpression></sequenceFlow><endEvent id="end"/><endEvent id="rejectEnd"/><sequenceFlow id="in_end" sourceRef="gw1" targetRef="end"/></process><bpmndi:BPMNDiagram id="BPMNDiagram_1"><bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="reimburseApproval"><bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="160" y="102" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="approve_di" bpmnElement="approve"><dc:Bounds x="250" y="80" width="110" height="80"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="gwapprove_di" bpmnElement="gwapprove"><dc:Bounds x="390" y="95" width="50" height="50"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="finance_di" bpmnElement="finance"><dc:Bounds x="440" y="80" width="110" height="80"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="gwfinance_di" bpmnElement="gwfinance"><dc:Bounds x="580" y="95" width="50" height="50"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="660" y="102" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="rejectEnd_di" bpmnElement="rejectEnd"><dc:Bounds x="415" y="200" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNEdge id="e_in_approve" bpmnElement="in_approve"><di:waypoint x="196" y="120"/><di:waypoint x="250" y="120"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_in_gw0" bpmnElement="in_gw0"><di:waypoint x="360" y="120.0"/><di:waypoint x="390" y="120"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_fwd_gw0" bpmnElement="fwd_gw0"><di:waypoint x="440" y="120"/><di:waypoint x="440" y="120"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_rej_gw0" bpmnElement="rej_gw0"><di:waypoint x="415" y="145"/><di:waypoint x="415" y="200"/><di:waypoint x="415" y="200"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_in_gw1" bpmnElement="in_gw1"><di:waypoint x="550" y="120.0"/><di:waypoint x="580" y="120"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_fwd_gw1" bpmnElement="fwd_gw1"><di:waypoint x="630" y="120"/><di:waypoint x="660" y="120"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="e_rej_gw1" bpmnElement="rej_gw1"><di:waypoint x="605" y="145"/><di:waypoint x="605" y="200"/><di:waypoint x="415" y="200"/></bpmndi:BPMNEdge></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>',
NULL, '1', 'system', CURRENT_TIMESTAMP, '内置报销审批流程');
