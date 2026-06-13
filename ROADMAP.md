# RuoYi Clojure 功能齐平路线图

> 目标：使本项目在功能上与 RuoYi 框架前后端分离版本 (vue.ruoyi.vip) 齐平
> 参考项目：https://gitee.com/y_project/RuoYi-Vue (v3.9.2)

## 一、当前状态总览

### ✅ 已完成功能 (后端 + 前端)

| 模块 | 后端 API | 前端页面 | 状态 |
|------|---------|---------|------|
| 用户管理 | ✅ | ✅ | 完成 |
| 角色管理 | ✅ | ✅ | 完成 |
| 菜单管理 | ✅ | ✅ | 完成 |
| 部门管理 | ✅ | ✅ | 完成 |
| 岗位管理 | ✅ | ✅ | 完成 |
| 字典管理 | ✅ | ✅ | 完成 |
| 参数配置 | ✅ | ✅ | 完成 |
| 操作日志 | ✅ | ✅ | 完成 |
| 登录日志 | ✅ | ✅ | 完成 |
| 在线用户 | ✅ | ✅ | 完成 |
| 定时任务 | ✅ | ✅ | 完成 |
| 个人中心 | ✅ | ✅ | 完成 |
| 通知公告 | ✅ | ✅ | 完成（富文本待完善） |
| 服务监控 | ✅ | ✅ | 完成 |
| 数据源监控 | ✅ | ✅ | 完成 |
| 缓存监控 | ✅ | ✅ | 完成 |
| 文件管理 | ✅ | ✅ | 完成 |
| 系统接口 / Swagger | ✅ | ✅ | 完成 |
| 多 Tab 页面支持 | ✅ | ✅ | 完成 |
| Integrant 依赖监控 | ✅ | ✅ | 完成 |

### ⚠️ 部分完成

| 模块 | 后端 API | 前端页面 | 缺失内容 |
|------|---------|---------|---------|
| 代码生成 | ✅ | ✅ | ZIP 下载、语法高亮 |
| 表单构建器 | ❌ | ✅ | 模板保存/加载后端 API |

### ❌ 完全缺失功能

| 功能 | 说明 | 优先级 |
|------|------|--------|
| **数据备份** | 数据库备份与恢复 | 🟡 中 |
| **移动端适配** | 小屏布局优化 | 🟢 低 |

---

## 二、UI 功能增强需求

### 2.1 多Tab页面支持 ✅

已完成：标签栏、右键菜单（关闭当前/其他/右侧/全部）、首页固定、Tab 滚动、刷新/全屏。

---

### 2.2 系统监控增强 ✅

- 服务监控、数据源监控、缓存监控、Integrant 依赖监控均已实现。

---

### 2.3 表单构建器 🟡

**当前状态：**
- 前端可视化拖拽设计器已实现，可生成 Hiccup。

**待完成：**
- [ ] 设计 `form_template` 表
- [ ] 后端 CRUD API `/api/system/form-template`
- [ ] 前端模板列表 + 保存/加载/删除

---

## 三、功能差距详细分析

### Phase 1: 体验补齐 (1 周)

#### 1.1 代码生成器增强 ⏰ 2天
- [ ] ZIP 打包下载
- [ ] 代码预览语法高亮

#### 1.2 表单构建器持久化 ⏰ 2天
- [ ] 模板表设计
- [ ] 后端 API
- [ ] 前端列表/保存/加载

#### 1.3 通知公告富文本 ⏰ 1天
- [ ] 集成富文本编辑器
- [ ] 详情页 HTML 渲染

---

### Phase 2: 新功能 (1-2 周)

#### 2.1 数据备份 ⏰ 2天
- [ ] 备份列表 API
- [ ] 手动备份 API
- [ ] 下载/删除备份
- [ ] 前端备份管理页

#### 2.2 移动端适配 ⏰ 3天
- [ ] 侧边栏抽屉化
- [ ] 表格横向滚动
- [ ] 搜索表单收缩

---

### Phase 3: 通用体验优化 (持续)

#### 3.1 表格功能增强
- [ ] 列排序
- [ ] 列筛选
- [ ] 列显隐控制
- [ ] Excel 导出

#### 3.2 表单功能增强
- [ ] 级联选择
- [ ] 日期范围选择
- [ ] 富文本编辑器

#### 3.3 交互优化
- [ ] 批量操作（批量删除、批量修改状态）
- [ ] 拖拽排序
- [ ] 快捷键支持
- [ ] 操作确认弹窗

---

## 四、技术实现要点

### 4.1 前端组件复用

```clojure
;; 部门树选择器组件（可复用）
(defn dept-tree-select []
  (let [depts @(rf/subscribe [:depts/tree])]
    [:> TreeSelect {:treeData depts :placeholder "选择部门"}]))

;; 菜单图标选择器组件
(defn icon-selector []
  [:> Popover {:content [icon-grid]}
   [:> Button "选择图标"]])

;; 权限分配组件
(defn permission-assign [{:keys [menus checked-keys on-change]}]
  [:> Tree {:checkable true
            :defaultCheckedKeys checked-keys
            :onCheck on-change}
   (map menu->tree-node menus)])
```

### 4.2 后端 API 规范

所有新 API 遵循以下规范：
- 列表查询：`GET /api/system/xxx` → 返回 `{:rows [...] :total N}`
- 详情查询：`GET /api/system/xxx/:id` → 返回单个对象
- 新增：`POST /api/system/xxx` → 返回 `{:xxx_id N}`
- 更新：`PUT /api/system/xxx/:id` → 返回成功消息
- 删除：`DELETE /api/system/xxx/:id` → 返回成功消息

### 4.3 数据库迁移规范

```sql
-- 202406120016-create-xxx.up.sql
CREATE TABLE IF NOT EXISTS xxx (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT NOT NULL DEFAULT '',
  status      TEXT NOT NULL DEFAULT '0',
  create_by   TEXT NOT NULL DEFAULT '',
  create_time TEXT,
  update_by   TEXT NOT NULL DEFAULT '',
  update_time TEXT,
  remark      TEXT NOT NULL DEFAULT ''
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_xxx_status ON xxx(status);
```

---

## 五、里程碑与时间线

| 阶段 | 内容 | 预计时间 | 交付物 | 完成度目标 |
|------|------|---------|--------|----------|
| **M1** | 代码生成增强 + 表单构建器持久化 + 富文本 | 1 周 | ZIP 下载/高亮 + 表单模板 + 富文本公告 | 95% |
| **M2** | 数据备份 + 移动端适配 | 1.5 周 | 备份管理 + 小屏可用 | 98% |
| **M3** | 通用体验优化 | 持续 | 表格/表单增强 | 100% |

---

## 六、优先级排序

### 🔴 高优先级 (必须完成)
1. **代码生成器增强** — ZIP 下载、语法高亮
2. **表单构建器持久化** — 模板保存/加载

### 🟡 中优先级 (建议完成)
1. 数据备份
2. 通知公告富文本
3. 表格/表单通用增强

### 🟢 低优先级 (可选)
1. 移动端适配
2. 高级导出功能

---

## 七、开发规范

### 代码组织
- 每个功能模块包含：controller、domain service、SQL、前端 page
- 前端页面不超过 300 行，拆分为子组件
- 后端 controller 不超过 50 行，业务逻辑放 domain service

### 命名约定
- 后端：`kebab-case` (如 `list-users`)
- 前端事件：`:module/action` (如 `:users/fetch`)
- 前端订阅：`:module/property` (如 `:users/items`)
- 数据库：`snake_case` (如 `user_id`)

### 测试要求
- 每个 API 编写集成测试
- 前端组件编写快照测试
- 关键业务逻辑编写单元测试

---

## 八、风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Redis 依赖 | 缓存管理需要 Redis | ✅ 已决定：使用内存缓存，无需 Redis |
| 文件存储 | 需要配置存储服务 | 先支持本地存储 |
| Excel 导出 | 需要额外依赖 | 使用 clojure.data.csv |
| 图标库 | Ant Design 图标有限 | 支持自定义图标上传 |
| 内存缓存限制 | 缓存数据重启丢失 | 可选：持久化到文件或数据库 |

---

*最后更新: 2026-06-13*
*文档版本: 3.0*
