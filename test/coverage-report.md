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
│  📁 测试命名空间:  40 个                                      │
│  🧪 测试用例:      306 个                                     │
│  ✅ 断言通过:      741 个                                     │
│  ❌ 断言失败:       0 个                                      │
│  ⚠️  错误:          0 个                                      │
├──────────────────────────────────────────────────────────────┤
│  🎉 所有测试通过!                                             │
└──────────────────────────────────────────────────────────────┘
```

## 覆盖率汇总

| 指标 | 覆盖率 |
|------|--------|
| 整体 Forms | **87.04%** |
| 整体 Lines | **91.86%** |

## 重点覆盖提升

- **Web 控制器层**：`auth`、`captcha`、`common`、`register`、`system.cache`、`system.file`、`system.import-export`、`system.notice`、`system.profile`、`system.role`、`system.user`、`job`、`gen` 等控制器均已补齐单元测试。
- **基础设施层**：`infra.db`、`infra.online`、`infra.data-perm`、`infra.security` 覆盖率达到 90% 以上。
- **中间件**：`middleware.auth`、`middleware.exception`、`middleware.formats` 覆盖率达到 90% 以上。
- **Domain 层**：`user`、`role`、`dept`、`log` 补齐更新/删除/关联等分支测试。

## 仍有提升空间的模块

以下模块当前覆盖率仍较低，可作为下一步重点：

| 命名空间 | Forms | Lines |
|----------|-------|-------|
| com.ruoyi.core | 49.60% | 70.00% |
| com.ruoyi.infra.scheduler | 57.49% | 74.81% |
| com.ruoyi.task | 52.50% | 80.00% |
| com.ruoyi.web.controllers.system.dept | 70.74% | 76.74% |
| com.ruoyi.web.controllers.system.dict | 57.98% | 63.01% |
| com.ruoyi.web.controllers.system.log | 54.95% | 60.87% |
| com.ruoyi.web.controllers.system.menu | 71.12% | 76.74% |
| com.ruoyi.web.controllers.system.online | 69.31% | 78.26% |
| com.ruoyi.web.controllers.system.post | 72.93% | 78.05% |
| com.ruoyi.web.handler | 68.75% | 76.47% |
| com.ruoyi.web.middleware.operlog | 76.89% | 89.47% |

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
