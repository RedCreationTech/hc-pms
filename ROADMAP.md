# RuoYi Clojure 功能齐平路线图

> 目标：使本项目在功能上与 RuoYi 框架前后端分离版本 (vue.ruoyi.vip) 齐平
> 参考项目：https://gitee.com/y_project/RuoYi-Vue (v3.9.2)
> 详细对比：见 [RUOYI_VUE_COMPARISON.md](./RUOYI_VUE_COMPARISON.md)

## 一、当前状态总览

### ✅ 已完成功能 (后端 + 前端)

| 模块 | 后端 API | 前端页面 | 状态 |
|------|---------|---------|------|
| 用户管理 | ✅ | ✅ | 完成 |
| 字典管理 | ✅ | ✅ | 完成 |
| 参数配置 | ✅ | ✅ | 完成 |
| 操作日志 | ✅ | ✅ | 完成 |
| 登录日志 | ✅ | ✅ | 完成 |
| 在线用户 | ✅ | ✅ | 完成 |
| 定时任务 | ✅ | ✅ | 完成 |
| 个人中心 | ✅ | ✅ | 完成 |
| 通知公告 | ✅ | ✅ | 完成 |
| 服务监控 | ✅ | ❌ | 后端完成 |
| 数据源监控 | ✅ | ❌ | 后端完成 |

### ⚠️ 后端完成但前端页面缺失

| 模块 | 后端 API | 前端页面 | 缺失内容 |
|------|---------|---------|---------|
| 角色管理 | ✅ | ❌ | 角色列表、权限分配弹窗 |
| 菜单管理 | ✅ | ❌ | 菜单树、新增/编辑表单 |
| 部门管理 | ✅ | ❌ | 部门树、新增/编辑表单 |
| 岗位管理 | ✅ | ❌ | 岗位列表、新增/编辑表单 |
| 服务监控 | ✅ | ❌ | 监控面板页面 |
| 代码生成 | 部分 | ❌ | 完整代码生成 UI |

### ❌ 完全缺失功能

| 功能 | 说明 | 优先级 |
|------|------|--------|
| **多Tab页面支持** | 类似浏览器标签页，可同时打开多个页面 | 🔴 高 |
| **缓存管理** | 内存缓存监控（不使用Redis） | 🟡 中 |
| **文件管理** | 文件上传、存储、预览 | 🟡 中 |
| **表单构建器** | 可视化拖拽表单设计器 | 🟡 中 |
| **系统接口** | Swagger/OpenAPI 文档 | 🟡 中 |
| **连接池监视** | 数据库连接池状态监控 | 🟡 中 |
| **数据备份** | 数据库备份与恢复 | 🟢 低 |

---

## 二、UI 功能增强需求

### 2.1 多Tab页面支持 🔴 高优先级

**需求描述：**
类似浏览器标签页的交互方式，用户可以同时打开多个页面，在不同页面间快速切换，而不会丢失页面状态。

**功能要点：**
- [ ] Tab栏显示在内容区顶部
- [ ] 点击菜单自动添加新Tab（如已打开则切换到该Tab）
- [ ] 支持关闭单个Tab（右键/关闭按钮）
- [ ] 支持关闭其他Tab、关闭所有Tab
- [ ] 首页Tab固定不可关闭
- [ ] Tab状态保持（表单输入、滚动位置等）
- [ ] Tab超出宽度时可滚动
- [ ] 支持右键菜单操作

**技术实现：**
```clojure
;; DB 结构
{:tabs {:items [{:key :user :label "用户管理" :closable true}
                {:key :dashboard :label "首页" :closable false}]
        :active :user}}

;; 事件
:tabs/add       ;; 添加Tab
:tabs/remove     ;; 关闭Tab
:tabs/remove-others  ;; 关闭其他
:tabs/remove-all    ;; 关闭所有
:tabs/activate   ;; 切换Tab

;; 页面缓存（避免重复渲染）
(defn cached-page [page-key]
  (let [cache (rf/subscribe [:tabs/cache])]
    (fn []
      (or (get @cache page-key)
          (let [component (page-component page-key)]
            (rf/dispatch [:tabs/cache-page page-key component])
            component)))))
```

**参考：** Ruyi Vue 版的 TagsView 组件

---

### 2.2 系统监控增强 🟡 中优先级

**当前状态：**
- 后端仅有基础的服务器信息（OS、JVM、内存）和数据源信息
- 前端完全没有监控页面

**需要新增的功能：**

#### 服务器监控页面
- [ ] CPU 信息（核心数、使用率、型号）
- [ ] 内存信息（已用/可用/使用率，进度条展示）
- [ ] JVM 信息（版本、最大/已用/空闲内存）
- [ ] 磁盘信息（分区、总空间、已用、可用）
- [ ] 系统信息（IP、架构、运行时长）

#### 后端 API 增强
```clojure
;; 需要增强的服务器信息
{:cpu {:cpuNum 8
        :used 23.5
        :sys 5.2
        :free 71.3}
  :mem {:total 16384
        :used 8192
        :free 8192
        :usage 50.0}
  :jvm {:version "17.0.1"
        :maxMemory 4096
        :totalMemory 2048
        :freeMemory 1024
        :usedMemory 1024}
  :sys {:osName "Mac OS X"
        :osArch "aarch64"
        :computerName "Kevin-MBP"
        :computerIp "192.168.1.100"
        :userDir "/Users/kevinli"}
  :disk [{:dirName "/"
          :total 500000
          :used 300000
          :free 200000
          :usage 60.0}]}
```

#### 缓存监控页面（内存缓存方案）
- [ ] 缓存信息（名称、类型、键数量、内存使用）
- [ ] 缓存键列表浏览
- [ ] 缓存值查看
- [ ] 缓存清空操作
- [ ] 内存使用趋势图

#### 后端缓存 API（基于 Clojure 内置缓存）
```clojure
(ns com.ruoyi.infra.cache
  "内存缓存管理，不依赖 Redis。"
  (:require [clojure.core.cache :as cache]))

;; 全局缓存存储
(defonce cache-store (atom {}))

(defn cache-info "获取缓存信息" [_ _])
(defn cache-keys "获取缓存键列表" [_ _])
(defn cache-value "获取缓存值" [_ _])
(defn cache-clear "清空缓存" [_ _])
```

**技术选型：**
- 使用 Clojure 内置 `core.cache` 库
- 支持 LRU/LFU/TTL 等缓存策略
- 无需外部依赖，简化部署和维护

---

### 2.3 表单构建器 🟡 中优先级

**需求描述：**
可视化拖拽式表单设计器，可以快速创建表单页面。

**功能要点：**
- [ ] 左侧组件面板（输入框、选择器、日期、上传等）
- [ ] 中间设计区域（拖拽排序）
- [ ] 右侧属性配置（标签、校验、样式）
- [ ] 表单预览功能
- [ ] 生成 JSON Schema
- [ ] 生成 ClojureScript 代码
- [ ] 保存/加载表单模板

**组件类型：**
| 组件 | 说明 |
|------|------|
| Input | 单行文本 |
| TextArea | 多行文本 |
| Number | 数字输入 |
| Select | 下拉选择 |
| Radio | 单选框 |
| Checkbox | 多选框 |
| DatePicker | 日期选择 |
| TimePicker | 时间选择 |
| Switch | 开关 |
| Slider | 滑块 |
| Upload | 文件上传 |
| RichText | 富文本编辑器 |

**技术实现：**
- 使用 React DnD 或 react-beautiful-dnd 实现拖拽
- 表单配置存储为 JSON
- 可以动态渲染表单

---

## 三、功能差距详细分析

### Phase 1: 核心UI体验 (1-2 周)

#### 1.0 多Tab页面支持 ⏰ 3天
- [ ] Tab栏组件开发
- [ ] Tab状态管理（打开/关闭/切换）
- [ ] 页面缓存机制
- [ ] 右键菜单
- [ ] Tab与菜单联动

#### 1.1 角色管理前端页面 ⏰ 2天
- [ ] 角色列表页（搜索、分页、CRUD）
- [ ] 角色-菜单权限分配弹窗（树形选择）
- [ ] 角色-数据权限分配（部门树选择）
- [ ] 前端事件/订阅/API

#### 1.2 菜单管理前端页面 ⏰ 2天
- [ ] 菜单树形列表
- [ ] 新增/编辑菜单表单
- [ ] 菜单图标选择器组件
- [ ] 菜单类型切换（目录/菜单/按钮）

#### 1.3 部门管理前端页面 ⏰ 1.5天
- [ ] 部门树形列表
- [ ] 新增/编辑部门表单
- [ ] 部门树选择器组件（用于用户管理）

#### 1.4 岗位管理前端页面 ⏰ 1天
- [ ] 岗位列表页
- [ ] 新增/编辑岗位弹窗

#### 1.5 服务监控前端页面 ⏰ 1.5天
- [ ] 服务器信息展示（CPU、内存、JVM、磁盘）
- [ ] 数据源信息展示
- [ ] 使用进度条、图表等可视化组件

---

### Phase 2: 完善代码生成器 (1 周)

#### 2.1 代码生成前端页面 ⏰ 3天
- [ ] 数据库表列表（可选择表）
- [ ] 生成配置（包名、模块名、作者等）
- [ ] 代码预览（语法高亮）
- [ ] 一键生成下载

#### 2.2 代码生成后端完善 ⏰ 2天
- [ ] 生成 Clojure 控制器模板
- [ ] 生成 ClojureScript 页面模板
- [ ] 生成 SQL 查询模板
- [ ] 自定义模板支持

---

### Phase 3: 高级功能 (1-2 周)

#### 3.1 缓存管理 ⏰ 2天
- [ ] 缓存列表（Redis 键值浏览）
- [ ] 缓存详情查看
- [ ] 缓存删除/清空
- [ ] 命令行工具（info/dbsize/flushdb）

#### 3.2 文件管理 ⏰ 2天
- [ ] 文件上传 API
- [ ] 文件列表（分页、搜索）
- [ ] 文件预览/下载
- [ ] 文件删除

#### 3.3 系统接口文档 ⏰ 1天
- [ ] 集成 Swagger UI
- [ ] 自动生成 API 文档
- [ ] 在线测试接口

---

### Phase 4: 用户体验优化 (持续)

#### 4.1 表格功能增强
- [ ] 列排序
- [ ] 列筛选
- [ ] 列显隐控制
- [ ] 导出 Excel

#### 4.2 表单功能增强
- [ ] 表单验证
- [ ] 级联选择
- [ ] 日期范围选择
- [ ] 富文本编辑器

#### 4.3 交互优化
- [ ] 批量操作（批量删除、批量修改状态）
- [ ] 拖拽排序
- [ ] 快捷键支持
- [ ] 操作确认弹窗

---

## 三、技术实现要点

### 3.1 前端组件复用

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

### 3.2 后端 API 规范

所有新 API 遵循以下规范：
- 列表查询：`GET /api/system/xxx` → 返回 `{:rows [...] :total N}`
- 详情查询：`GET /api/system/xxx/:id` → 返回单个对象
- 新增：`POST /api/system/xxx` → 返回 `{:xxx_id N}`
- 更新：`PUT /api/system/xxx/:id` → 返回成功消息
- 删除：`DELETE /api/system/xxx/:id` → 返回成功消息

### 3.3 数据库迁移规范

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

## 四、里程碑与时间线

| 阶段 | 内容 | 预计时间 | 交付物 | 完成度目标 |
|------|------|---------|--------|----------|
| **M1** | 多Tab支持 + 核心页面 | 2 周 | Tab组件 + 角色/菜单/部门/岗位/监控页面 | 80% |
| **M2** | 代码生成器 + 服务器监控增强 | 1.5 周 | 完整代码生成 UI + 增强的监控面板 | 85% |
| **M3** | 缓存监控 + 连接池监视 | 1.5 周 | 内存缓存监控 + 数据库连接池监控 | 90% |
| **M4** | 系统接口 + 表单构建器 | 2 周 | Swagger UI + 可视化表单设计器 | 95% |
| **M5** | 文件管理 + 体验优化 | 2 周 | 文件上传/管理 + 表格增强 | 100% |

---

## 五、优先级排序

### 🔴 高优先级 (必须完成)
1. **多Tab页面支持** - 提升用户体验的核心功能
2. 角色管理页面 + 权限分配
3. 菜单管理页面
4. 部门管理页面
5. 岗位管理页面
6. 服务监控页面（增强版）

### 🟡 中优先级 (建议完成)
1. 代码生成器完善
2. 缓存监控（内存缓存）
3. 连接池监视
4. 表单构建器
5. 文件管理
6. API 文档 (Swagger)

### 🟢 低优先级 (可选)
1. 数据备份
2. 高级导出功能
3. 移动端适配

---

## 六、开发规范

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

## 七、风险与依赖

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| Redis 依赖 | 缓存管理需要 Redis | ✅ 已决定：使用内存缓存，无需 Redis |
| 文件存储 | 需要配置存储服务 | 先支持本地存储 |
| Excel 导出 | 需要额外依赖 | 使用 clojure.data.csv |
| 图标库 | Ant Design 图标有限 | 支持自定义图标上传 |
| 内存缓存限制 | 缓存数据重启丢失 | 可选：持久化到文件或数据库 |

---

*最后更新: 2026-06-12*
*文档版本: 2.1*
