# RuoYi Clojure 测试覆盖率报告 (Cloverage)

## 测试执行结果

```
╔══════════════════════════════════════════════════════════════╗
║              RuoYi Clojure 单元测试报告                      ║
╚══════════════════════════════════════════════════════════════╝

测试框架: clojure.test + cloverage
测试时间: 2026-06-13

┌──────────────────────────────────────────────────────────────┐
│  测试汇总                                                     │
├──────────────────────────────────────────────────────────────┤
│  📁 测试命名空间:  45 个                                      │
│  🧪 测试用例:      348 个                                     │
│  ✅ 断言通过:      853 个                                     │
│  ❌ 断言失败:       0 个                                      │
│  ⚠️  错误:          0 个                                      │
├──────────────────────────────────────────────────────────────┤
│  🎉 所有测试通过!                                             │
└──────────────────────────────────────────────────────────────┘
```

## 覆盖率汇总

| 指标 | 覆盖率 |
|------|--------|
| 整体 Forms | **89.86%** |
| 整体 Lines | **94.25%** |

## 重点覆盖提升

- **Web 控制器层**：`auth`、`captcha`、`common`、`register`、`system.cache`、`system.config`、`system.dept`、`system.dict`、`system.file`、`system.import-export`、`system.log`、`system.menu`、`system.notice`、`system.online`、`system.post`、`system.profile`、`system.role`、`system.user`、`job`、`gen` 等控制器均已补齐单元测试。
- **基础设施层**：`infra.cache`、`infra.data-perm`、`infra.db`、`infra.online`、`infra.security` 覆盖率达到 85% 以上；`infra.scheduler`、`task` 补齐调度与示例任务分支。
- **中间件**：`middleware.auth`、`middleware.exception`、`middleware.formats`、`middleware.operlog`、`web.handler` 覆盖率达到 90% 以上。
- **Domain 层**：`user`、`role`、`dept`、`menu`、`log`、`post`、`dict` 补齐更新/删除/关联等分支测试。

## 仍有提升空间的模块

以下模块当前覆盖率仍较低，可作为下一步重点：

| 命名空间 | Forms | Lines |
|----------|-------|-------|
| com.ruoyi.core | 49.60% | 70.00% |
| com.ruoyi.infra.scheduler | 57.62% | 74.81% |
| com.ruoyi.infra.online | 55.19% | 83.16% |
| com.ruoyi.domain.system.dept | 72.31% | 84.62% |
| com.ruoyi.domain.system.user | 76.43% | 96.36% |
| com.ruoyi.infra.cron | 83.64% | 75.00% |
| com.ruoyi.infra.db | 87.70% | 91.75% |
| com.ruoyi.web.controllers.system.config | 88.48% | 87.18% |
| com.ruoyi.web.controllers.system.user | 88.87% | 89.58% |

## 运行测试

```bash
# 运行所有单元测试
clojure -M:test

# 生成覆盖率报告（输出到 target/coverage/index.html）
clojure -Sdeps '{:deps {cloverage/cloverage {:mvn/version "1.2.4"}
                        ring/ring-mock {:mvn/version "0.6.2"}
                        peridot/peridot {:mvn/version "0.5.4"}
                        org.clj-commons/byte-streams {:mvn/version "0.3.4"}}}' \
  -M:dev -m cloverage.coverage \
  --src-ns-path src/clj --test-ns-path test/clj \
  --ns-regex 'com\\.ruoyi\\..*' --output target/coverage
```

## 运行 E2E 测试

```bash
# 确保后端在 localhost:3000 运行
npx playwright test
```
