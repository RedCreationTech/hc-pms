# 剩余工作清单

> 基于当前代码库与 `GAP_ANALYSIS.md` / `ROADMAP.md` 的差距核对生成。
> 优先级：🔴 高 > 🟡 中 > 🟢 低。

---

## Phase 1 — 快速补全（可立即交付）

### 1.1 文件管理接入路由与侧边栏菜单 ✅
- **现状**：`pages/file_manager.cljs` 页面和 `/api/system/file/*` 后端 API 已存在，但 `router.cljs` 未注册 `:file` 路由，侧边栏菜单也没有入口。
- **已完成**：
  - 在 `src/cljs/com/ruoyi/frontend/router.cljs` 增加 `/system/file` → `:file` 路由。
  - 在 `src/cljs/com/ruoyi/frontend/pages/layout.cljs` 注入文件管理菜单项（系统管理 → 文件管理）。
- **验收**：登录后点击菜单能打开文件管理页面，上传/下载/删除可用。

### 1.2 更新 `ROADMAP.md` 与 `GAP_ANALYSIS.md` ✅
- **现状**：两份文档与代码严重不同步，会误导后续开发。
- **已完成**：
  - 把已实现的功能（角色/菜单/部门/岗位/服务监控/数据源监控/缓存监控/多 Tab/代码生成 UI/Swagger/文件管理/Integrant 监控）标记为完成。
  - 补充 Integrant 依赖视图与调用追踪说明。
  - 修正总体完成度到 ~95%。
- **验收**：文档描述与 `src/cljs/com/ruoyi/frontend/pages/`、`src/clj/com/ruoyi/web/routes/system.clj` 一致。

---

## Phase 2 — 功能补齐（已有页面/API，但体验不完整）

### 2.1 代码生成器：ZIP 下载 + 语法高亮 ✅
- **现状**：表选择、预览、批量生成已可用；`GAP_ANALYSIS.md` 标 60%。
- **已完成**：
  - 验证后端 `/api/tool/gen/download` 返回 ZIP 正常。
  - 预览弹窗接入 `react-syntax-highlighter`，按 Clojure/SQL 语法高亮。
- **验收**：生成代码后可一键下载 ZIP；预览代码按 Clojure/ClojureScript/SQL 高亮。

### 2.2 表单构建器：后端持久化 ✅
- **现状**：前端拖拽设计器已存在，但无法保存/加载模板。
- **已完成**：
  - 新增 `sys_form_template` 表及迁移文件。
  - 后端 CRUD API `/api/system/form-template`。
  - 前端 form builder 增加“保存模板”弹窗和“加载模板”抽屉，支持保存/加载/删除。
- **验收**：设计好的表单可以命名保存，后续从列表加载继续编辑。

### 2.3 通知公告富文本编辑器 ✅
- **现状**：`GAP_ANALYSIS.md` 标 70%，通知公告内容字段目前可能是纯文本输入。
- **已完成**：
  - 新增 `notice_content` 字段及迁移。
  - 后端 `/api/system/notice` 支持内容保存/更新/查询。
  - 前端接入 `react-quill-new` 富文本编辑器。
- **验收**：公告内容支持图文混排，提交后详情页正确渲染 HTML。

---

## Phase 3 — 新功能

### 3.1 数据备份 🟢
- **现状**：无相关 API 与页面。
- **目标**：
  - 后端：`/api/system/backup` 列出备份、`POST /api/system/backup` 执行备份、`DELETE /api/system/backup/:name` 删除、`GET /api/system/backup/:name/download` 下载。
  - 前端：备份列表页 + 手动备份 + 下载/删除。
- **验收**：点击备份可生成 `.db` 备份文件并能下载。

### 3.2 移动端适配 🟢
- **现状**：侧边栏 + 表格在大屏下可用，小屏布局未处理。
- **目标**：
  - 小屏下侧边栏可折叠/抽屉化。
  - 表格支持横向滚动，搜索表单收缩。
- **验收**：在 375px 宽度下页面可正常浏览核心信息。

---

## Phase 4 — 通用体验增强（持续）

| 模块 | 内容 | 优先级 |
|------|------|--------|
| 表格 | 列排序、列筛选、列显隐控制、Excel 导出 | 🟡 |
| 表单 | 级联选择、日期范围选择、更完善的表单校验提示 | 🟡 |
| 交互 | 批量删除、批量修改状态、拖拽排序、快捷键、操作确认弹窗 | 🟢 |

---

## Phase 5 — Integrant 调用追踪增强

### 5.1 让非 Ring 函数组件也能真正抓到调用日志 ✅
- **现状**：`:handler/ring` 通过动态代理能抓到 HTTP 请求；但 `:db.sql/query-fn` 等组件的调用方在系统启动时就持有旧函数引用，切换追踪后日志不会增加。
- **已完成**：
  - 覆盖 `ig/init-key :db.sql/query-fn`，返回动态代理函数。
  - `trace/start!` / `stop!` 切换时替换代理 atom，使所有已持有 `:db.sql/query-fn` 的服务立即生效。
- **验收**：追踪 `:db.sql/query-fn` 后，列表查询能显示对应的调用日志。

---

## 建议执行顺序

1. **Phase 1.1** 文件管理路由/菜单（最快可见效果）
2. **Phase 1.2** 更新文档（避免继续误导）
3. **Phase 2.1** 代码生成 ZIP/高亮
4. **Phase 2.2** 表单构建器持久化
5. **Phase 2.3** 通知公告富文本
6. **Phase 5.1** Integrant 追踪增强
7. **Phase 3/4** 按资源逐步推进
