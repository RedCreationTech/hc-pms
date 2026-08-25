-- 办公一体化 · 报销审批（SQLite）

-- 报销单（业务记录，关联 Flowable 流程实例）
CREATE TABLE IF NOT EXISTS biz_oa_reimburse (
  reimburse_id       INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id            INTEGER DEFAULT 0,
  user_name          TEXT NOT NULL DEFAULT '',
  amount             INTEGER DEFAULT 0,
  reason             TEXT NOT NULL DEFAULT '',
  process_instance_id TEXT NOT NULL DEFAULT '',
  status             TEXT NOT NULL DEFAULT '1',
  create_time        TEXT,
  update_time        TEXT
);
--;;
CREATE INDEX IF NOT EXISTS idx_oa_reimburse_user ON biz_oa_reimburse(user_id);
--;;
CREATE INDEX IF NOT EXISTS idx_oa_reimburse_pid ON biz_oa_reimburse(process_instance_id);
--;;

-- 默认报销审批流程模型
INSERT OR IGNORE INTO biz_bpm_model (model_id, model_key, model_name, category_id, version, form_type, form_json, bpmn_xml, deployment_id, status, create_by, create_time, remark)
VALUES (502, 'reimburseApproval', '报销审批', 1, 1, '1', '{"fields":[{"key":"amount","label":"报销金额","type":"number"},{"key":"reason","label":"报销事由","type":"textarea"}]}',
'<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:flowable="http://flowable.org/bpmn" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" id="reimburseApprovalDef" targetNamespace="http://bpmn.io/schema/bpmn"><process id="reimburseApproval" name="报销审批" isExecutable="true"><startEvent id="start"/><userTask id="approve" name="部门经理审批" flowable:candidateUsers="admin"/><exclusiveGateway id="gw"/><endEvent id="end"/><endEvent id="rejectEnd"/><sequenceFlow id="f1" sourceRef="start" targetRef="approve"/><sequenceFlow id="f2" sourceRef="approve" targetRef="gw"/><sequenceFlow id="f3" sourceRef="gw" targetRef="end"><conditionExpression xsi:type="tFormalExpression">${approved == true}</conditionExpression></sequenceFlow><sequenceFlow id="f4" sourceRef="gw" targetRef="rejectEnd"><conditionExpression xsi:type="tFormalExpression">${approved == false}</conditionExpression></sequenceFlow></process><bpmndi:BPMNDiagram id="BPMNDiagram_1"><bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="reimburseApproval"><bpmndi:BPMNShape id="start_di" bpmnElement="start"><dc:Bounds x="180" y="90" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="approve_di" bpmnElement="approve"><dc:Bounds x="270" y="70" width="100" height="80"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="gw_di" bpmnElement="gw"><dc:Bounds x="430" y="85" width="50" height="50"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="end_di" bpmnElement="end"><dc:Bounds x="540" y="90" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNShape id="rejectEnd_di" bpmnElement="rejectEnd"><dc:Bounds x="430" y="180" width="36" height="36"/></bpmndi:BPMNShape><bpmndi:BPMNEdge id="f1_di" bpmnElement="f1"><di:waypoint x="216" y="108"/><di:waypoint x="270" y="108"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="f2_di" bpmnElement="f2"><di:waypoint x="370" y="110"/><di:waypoint x="430" y="110"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="f3_di" bpmnElement="f3"><di:waypoint x="480" y="110"/><di:waypoint x="540" y="108"/></bpmndi:BPMNEdge><bpmndi:BPMNEdge id="f4_di" bpmnElement="f4"><di:waypoint x="455" y="135"/><di:waypoint x="455" y="180"/></bpmndi:BPMNEdge></bpmndi:BPMNPlane></bpmndi:BPMNDiagram></definitions>',
NULL, '1', 'system', CURRENT_TIMESTAMP, '内置报销审批流程');
