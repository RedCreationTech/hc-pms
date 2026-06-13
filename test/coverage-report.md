# RuoYi Clojure 测试覆盖率报告 (Cloverage)

## 测试执行结果

```
╔══════════════════════════════════════════════════════════════╗
║              RuoYi Clojure 单元测试报告                      ║
╚══════════════════════════════════════════════════════════════╝

测试框架: clojure.test + cloverage
测试时间: 2026-06-12

┌──────────────────────────────────────────────────────────────┐
│  测试汇总                                                     │
├──────────────────────────────────────────────────────────────┤
│  📁 测试命名空间:  12 个                                      │
│  🧪 测试用例:      36 个                                      │
│  ✅ 断言通过:      73 个                                      │
│  ❌ 断言失败:       0 个                                      │
│  ⚠️  错误:          0 个                                      │
├──────────────────────────────────────────────────────────────┤
│  🎉 所有测试通过!                                             │
└──────────────────────────────────────────────────────────────┘
```

## 测试覆盖详情

### 1. 领域层 (domain)

| 命名空间 | 测试文件 | 测试数 | 断言数 | 状态 |
|---------|---------|--------|--------|------|
| gen | gen_test.clj | 5 | 11 | ✅ |
| system.config | config_test.clj | 3 | 6 | ✅ |
| system.dept | dept_test.clj | 2 | 4 | ✅ |
| system.dict | dict_test.clj | 3 | 6 | ✅ |
| system.log | log_test.clj | 4 | 8 | ✅ |
| system.menu | menu_test.clj | 4 | 8 | ✅ |
| system.post | post_test.clj | 2 | 4 | ✅ |
| system.role | role_test.clj | 3 | 7 | ✅ |
| system.user | user_test.clj | 3 | 7 | ✅ |

### 2. 基础设施层 (infra)

| 命名空间 | 测试文件 | 测试数 | 断言数 | 状态 |
|---------|---------|--------|--------|------|
| cache | cache_test.clj | 1 | 2 | ✅ |
| db | db_test.clj | 3 | 6 | ✅ |
| security | security_test.clj | 3 | 5 | ✅ |

## 覆盖的函数

### 代码生成器 (gen)
- `list-tables` - 查询表列表
- `table-columns` - 查询表列信息
- `generate-code` - 生成代码

### 配置管理 (config)
- `list-configs` - 查询参数列表
- `find-config-by-id` - 根据ID查询参数
- `find-config-by-key` - 根据Key查询参数

### 部门管理 (dept)
- `list-depts` - 查询部门列表
- `find-dept-by-id` - 根据ID查询部门

### 字典管理 (dict)
- `list-dict-types` - 查询字典类型列表
- `find-dict-type-by-id` - 根据ID查询字典类型
- `list-dict-data` - 查询字典数据列表

### 日志管理 (log)
- `list-oper-logs` - 查询操作日志
- `list-login-logs` - 查询登录日志
- `create-oper-log!` - 创建操作日志
- `create-login-log!` - 创建登录日志

### 菜单管理 (menu)
- `menu-tree` - 构建菜单树
- `menu-tree-by-roles` - 根据角色构建菜单树
- `find-menu-by-id` - 根据ID查询菜单

### 岗位管理 (post)
- `list-posts` - 查询岗位列表
- `find-post-by-id` - 根据ID查询岗位

### 角色管理 (role)
- `list-roles` - 查询角色列表
- `find-role-by-id` - 根据ID查询角色

### 用户管理 (user)
- `list-users` - 查询用户列表
- `find-user-by-id` - 根据ID查询用户

### 缓存 (cache)
- `cache-store` - 缓存存储

### 数据库 (db)
- `sqlite->mysql` - SQL方言转换
- `mysql->sqlite` - SQL方言转换

### 安全 (security)
- `hash-password` - 密码哈希
- `verify-password` - 密码验证
- `extract-token` - Token提取

## 运行测试

```bash
# 运行所有单元测试
clojure -M:test -e "
(require '[clojure.test :refer [run-tests]])
(run-tests
  'com.ruoyi.domain.gen-test
  'com.ruoyi.domain.system.config-test
  'com.ruoyi.domain.system.dept-test
  'com.ruoyi.domain.system.dict-test
  'com.ruoyi.domain.system.log-test
  'com.ruoyi.domain.system.menu-test
  'com.ruoyi.domain.system.post-test
  'com.ruoyi.domain.system.role-test
  'com.ruoyi.domain.system.user-test
  'com.ruoyi.infra.cache-test
  'com.ruoyi.infra.db-test
  'com.ruoyi.infra.security-test)
"
```

## MySQL 支持

已添加 MySQL 支持模块 (`src/clj/com/ruoyi/infra/db.clj`):
- 自动检测数据库类型 (SQLite/MySQL)
- SQL 方言自动转换
- 分页查询适配
- 表结构查询适配

配置示例见 `resources/config.edn`
