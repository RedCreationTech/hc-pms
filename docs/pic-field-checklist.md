# 截图字段清单

> 本清单对应 `PROJECT_REQUIREMENTS_FROM_PICS.md` 的实现门禁。页面开发必须以本清单为字段基准；截图字段不得缺失。
> 约定：数据库字段采用 snake_case；前端 key 与接口 request 字段保持一致；页面 label 按截图中文展示。

## 通用字段

所有业务主表/明细表包含：`create_by`、`create_time`、`update_by`、`update_time`、`remark`、`del_flag`。

## 首页/方案流程

### 工程选择

| 截图 | 页面 label | 数据库字段 | 前端 key | request spec 字段 | 控件 | 说明 |
|---|---|---|---|---|---|---|
| `1-1首页-新增方案-选工程.png` | 工程名称 | `engineering_name` | `engineering_name` | `engineering_name` | Input | 查询/展示 |
| 同上 | 工程编号 | `engineering_code` | `engineering_code` | `engineering_code` | Input | 查询/展示 |
| 同上 | 工程说明 | `description` | `description` | `description` | TextArea | 展示 |
| 同上 | 状态 | `status` | `status` | `status` | Select | 启用/停用 |

### 项目表单

| 截图 | 页面 label | 数据库字段 | 前端 key | request spec 字段 | 控件 | 说明 |
|---|---|---|---|---|---|---|
| `1-3首页-新增方案-新增项目.png` / `2-2项目信息管理-新建项目.png` | 项目名称 | `project_name` | `project_name` | `project_name` | Input | 必填 |
| 同上 | 项目编号 | `project_code` | `project_code` | `project_code` | Input | 编码 |
| 同上 | 所属工程 | `engineering_id` | `engineering_id` | `engineering_id` | Select | 关联工程 |
| 同上 | 工程名称 | `engineering_name` | `engineering_name` | `engineering_name` | Input | 快照/展示 |
| 同上 | 项目地址 | `project_address` | `project_address` | `project_address` | Input | 地址 |
| 同上 | 建设单位 | `construction_unit` | `construction_unit` | `construction_unit` | Input | 单位 |
| 同上 | 施工单位 | `contractor_unit` | `contractor_unit` | `contractor_unit` | Input | 单位 |
| 同上 | 监理单位 | `supervision_unit` | `supervision_unit` | `supervision_unit` | Input | 单位 |
| 同上 | 设计单位 | `design_unit` | `design_unit` | `design_unit` | Input | 单位 |
| 同上 | 勘察单位 | `survey_unit` | `survey_unit` | `survey_unit` | Input | 单位 |
| 同上 | 项目经理 | `project_manager` | `project_manager` | `project_manager` | Input | 人员 |
| 同上 | 项目负责人 | `project_leader` | `project_leader` | `project_leader` | Input | 人员 |
| 同上 | 联系电话 | `contact_phone` | `contact_phone` | `contact_phone` | Input | 电话 |
| 同上 | 合同工期 | `contract_period` | `contract_period` | `contract_period` | Input | 工期 |
| 同上 | 开工日期 | `start_date` | `start_date` | `start_date` | DatePicker | 日期 |
| 同上 | 竣工日期 | `end_date` | `end_date` | `end_date` | DatePicker | 日期 |
| 同上 | 建筑面积 | `building_area` | `building_area` | `building_area` | Input | 面积 |
| 同上 | 工程造价 | `project_cost` | `project_cost` | `project_cost` | Input | 金额 |
| 同上 | 结构类型 | `structure_type` | `structure_type` | `structure_type` | Input | 类型 |
| 同上 | 建筑层数 | `building_floors` | `building_floors` | `building_floors` | Input | 层数 |
| 同上 | 项目概况 | `project_overview` | `project_overview` | `project_overview` | TextArea | 长文本 |
| 同上 | 附件 | `biz_attachment` | `attachments` | `attachments` | Upload | 附件元数据 |
| 同上 | 备注 | `remark` | `remark` | `remark` | TextArea | 备注 |

### 分包队伍

| 截图 | 页面 label | 数据库字段 | 前端 key | request spec 字段 | 控件 | 说明 |
|---|---|---|---|---|---|---|
| `1-4首页-新增方案-新增项目-新增分包队伍.png` | 分包队伍名称 | `team_name` | `team_name` | `team_name` | Input | 必填 |
| 同上 | 负责人 | `leader_name` | `leader_name` | `leader_name` | Input | 人员 |
| 同上 | 联系电话 | `contact_phone` | `contact_phone` | `contact_phone` | Input | 电话 |
| 同上 | 分包内容 | `work_scope` | `work_scope` | `work_scope` | TextArea | 范围 |
| 同上 | 备注 | `remark` | `remark` | `remark` | TextArea | 备注 |

### 方案与章节

| 截图 | 页面 label | 数据库字段 | 前端 key | request spec 字段 | 控件 | 说明 |
|---|---|---|---|---|---|---|
| `1-首页.png` | 方案名称 | `solution_name` | `solution_name` | `solution_name` | Input | 方案标题 |
| 同上 | 所属工程 | `engineering_id` | `engineering_id` | `engineering_id` | Select | 关联工程 |
| 同上 | 所属项目 | `project_id` | `project_id` | `project_id` | Select | 关联项目 |
| 同上 | 状态 | `status` | `status` | `status` | Select | 草稿/完成 |
| 同上 | 进度 | `progress` | `progress` | `progress` | Number | 百分比 |
| `1-6首页-新增方案-选择项目-编辑项目信息.png` | 项目信息 | `content_json` | `project_info` | `content` | Form | 章节 JSON，字段同项目表单 |
| `1-7首页-新增方案-选择项目-编辑-人员和组织.png` | 组织名称 | `content_json` | `org_name` | `content.org_name` | Input | 章节 JSON |
| 同上 | 岗位/职务 | `content_json` | `position` | `content.position` | Input | 章节 JSON |
| 同上 | 姓名 | `content_json` | `person_name` | `content.person_name` | Input | 章节 JSON |
| 同上 | 联系方式 | `content_json` | `phone` | `content.phone` | Input | 章节 JSON |
| 同上 | 职责 | `content_json` | `responsibility` | `content.responsibility` | TextArea | 章节 JSON |
| `1-8首页-新增方案-选择项目-编辑封面.png` | 方案名称 | `content_json` | `title` | `content.title` | Input | 封面 |
| 同上 | 编制单位 | `content_json` | `compile_unit` | `content.compile_unit` | Input | 封面 |
| 同上 | 编制人 | `content_json` | `compiler` | `content.compiler` | Input | 封面 |
| 同上 | 审核人 | `content_json` | `reviewer` | `content.reviewer` | Input | 封面 |
| 同上 | 审批人 | `content_json` | `approver` | `content.approver` | Input | 封面 |
| 同上 | 编制日期 | `content_json` | `compile_date` | `content.compile_date` | DatePicker | 封面 |
| 同上 | 封面图片 | `biz_attachment` | `cover_image` | `attachments` | Upload | 附件 |
| `1-9首页-新建方案-选择项目-编辑设计概况.png` | 工程概况 | `content_json` | `engineering_overview` | `content.engineering_overview` | TextArea | 章节 JSON |
| 同上 | 设计依据 | `content_json` | `design_basis` | `content.design_basis` | TextArea | 章节 JSON |
| 同上 | 设计范围 | `content_json` | `design_scope` | `content.design_scope` | TextArea | 章节 JSON |
| 同上 | 主要技术参数 | `content_json` | `technical_params` | `content.technical_params` | TextArea | 章节 JSON |
| 同上 | 设计说明 | `content_json` | `design_description` | `content.design_description` | TextArea | 章节 JSON |
| `1-10首页-新建方案-选择项目-编辑平面布置图.png` | 图纸名称 | `content_json` | `drawing_name` | `content.drawing_name` | Input | 章节 JSON |
| 同上 | 图纸说明 | `content_json` | `drawing_desc` | `content.drawing_desc` | TextArea | 章节 JSON |
| 同上 | 排序 | `content_json` | `sort_order` | `content.sort_order` | Input | 章节 JSON |
| 同上 | 图纸文件 | `biz_attachment` | `drawing_files` | `attachments` | Upload | 附件/图库引用 |

## 项目信息管理

字段复用“项目表单”，列表字段包括：项目名称、项目编号、工程名称、建设单位、施工单位、项目地址、项目经理、联系电话、创建时间、操作。

## 资源管理

### 标准规范

| 截图 | 页面 label | 数据库字段 | 前端 key | request spec 字段 | 控件 | 说明 |
|---|---|---|---|---|---|---|
| `3-1-1资源管理-标准规范-列表.png` / `3-1-2资源管理-标准规范-编辑.png` | 标准名称 | `name` | `name` | `name` | Input | 必填 |
| 同上 | 标准编号 | `code` | `code` | `code` | Input | 编号 |
| 同上 | 标准类型 | `category` | `category` | `category` | Select | 分类 |
| 同上 | 发布单位 | `publish_unit` | `publish_unit` | `publish_unit` | Input | 单位 |
| 同上 | 发布日期 | `publish_date` | `publish_date` | `publish_date` | DatePicker | 日期 |
| 同上 | 实施日期 | `effective_date` | `effective_date` | `effective_date` | DatePicker | 日期 |
| 同上 | 状态 | `status` | `status` | `status` | Select | 启用/停用 |
| 同上 | 附件 | `biz_attachment` | `attachments` | `attachments` | Upload | 文档 |
| 同上 | 备注 | `remark` | `remark` | `remark` | TextArea | 备注 |

### 向量知识库

字段：知识名称 `name`、知识分类 `category`、文件名称 `file_name`、文件类型 `file_type`、向量化状态 `vector_status`、标签 `tags`、描述 `summary`、附件 `biz_attachment`、备注 `remark`。

### 结构化知识库

字段：知识名称 `name`、知识分类 `category`、属性字段 `structured_fields`、内容 `content`、状态 `status`、备注 `remark`。

### 优秀案例库

字段：案例名称 `name`、案例分类 `category`、项目名称 `related_project_name`、案例简介 `summary`、案例内容 `content`、附件/图片 `biz_attachment`、状态 `status`、备注 `remark`。

### 通用图集库

字段：图集名称 `name`、图集分类 `category`、图集编号 `code`、图集说明 `summary`、图片/图纸文件 `biz_attachment`、图库明细 `biz_gallery_item`、状态 `status`、排序 `sort_order`、备注 `remark`。
