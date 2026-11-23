# ub-mode 视角的优化盘点 (2026-11-22)

依据: 全量 Cloverage 实测 (883 tests / 8509 assertions, 整体行覆盖 87.26%),
覆盖率 HTML 的逐行未覆盖数据, 以及对 `src/clj` 的结构化检索.
工具情况: 本次盘点时环境中没有 crapper / dryer / mutator, 对应条目是用等价手工手段
(覆盖率 HTML 逐行提取, 结构检索, 重复度比对) 得到的. 三件工具随后已安装并跑出真实
基线, 见文末 "ub-mode 三工具实测基线".

---

## 一, 最高价值: 一处前提被 7 份复制持有 (Attack the Premise + Subtract Before You Add)

**前提**: "分页参数如何从查询串解析" 这件事, 在每个领域命名空间里各写一遍.

`page-params` 私有复制 **7 份**, 逐字比较结果:

| 文件 | 实现 | 非数字 `page`/`size` |
|---|---|---|
| `domain/business/hrm.clj:21` | `optional-id` | 回退默认值 |
| `domain/business/oa.clj:21` | `optional-id` | 回退默认值 |
| `domain/business/crm.clj:21` | `optional-id` | 回退默认值 |
| `domain/business/bpm.clj:348` | `Integer/parseInt` | **抛 NumberFormatException → 500** |
| `domain/business/bpm_mgmt.clj:37` | `Integer/parseInt` | **抛 NumberFormatException → 500** |
| `domain/business/leave.clj:18` | `Integer/parseInt` | **抛 NumberFormatException → 500** |
| `domain/business/reimburse.clj:16` | `Integer/parseInt` | **抛 NumberFormatException → 500** |

同一份逻辑, 4 个持有者是错的, 3 个是正确的. 这正是 "两个修复共享同一前提却卡在同一关" 的形状.
上一轮只修了 3 个, 因为当时只看了覆盖率最低的文件, **没有做前提层面的归纳**.

同样形状的还有可选 id 过滤条件:

```
leave.clj:91       :user_id ... (Integer/parseInt (str u))
reimburse.clj:83   :user_id ... (Integer/parseInt (str u))
```
两者的路由都把 `user_id` 从查询串直接传入 (`web/routes/business.clj:150`).
`?user_id=全部` 或任意非数字 → 500.

**建议**: 建 `com.ruoyi.domain.business.paging` (叶子命名空间, 只依赖 clojure.core),
提供唯一的 `params` / `optional-id`; 7 处改为调用它. 之后这一类缺陷只可能
在一个地方出现或消失. 顺带把 3 份重复的 `optional-id` 一起消掉 (dryer 语义).

**注意不要复用** `domain/pms/rules.clj:199` 的 `page-params!`: 它固定解析
`status`/`q` 并做领域校验, 语义不同, 硬套会把两类关注点再揉在一起.

---

## 二, 结构违规且拉低覆盖: 两个巨型 BPM 文件

AGENTS.md 规定 "每个 namespace 不超过 500 行, 函数不超过 40 行".

| 文件 | 行数 | 函数数 | 未覆盖行 |
|---|---|---|---|
| `domain/business/bpm.clj` | 1590 | 92 | **174** (最多) |
| `bpm/core.clj` | 1352 | 82 | **110** |

两者占了全仓未覆盖行的最大份额. 但它们**已经有清晰的分区注释**:

`bpm.clj`: 抄送处理器 / 组件 / 自动去重 / 分页 / 编号与渲染 / 分类 / 模型 / 动态表单 /
实例 / 归属校验 / 审批闭环 / 定义版本与启停 / Webhook 与监听器 (13 个区).

`bpm/core.clj`: 流程定义 / 版本启停 / 实例 / Webhook 分发 / 占位符解析 / 抄送 /
按钮配置 / 超时 / 触发器节点 / 历史 / 图示 / 加减签 / 取消撤回 / 可退回 (14 个区).

**建议**: 按既有分区拆成多个命名空间, 原文件保留 require + 转发 (或直接改调用方).
理由不是"文件大不好看", 而是: 874 行级的单一命名空间让**未覆盖行的定位成本极高**,
也无法按区给测试划定边界 —— 现在要回答 "审批闭环那段测到没有" 只能靠翻 HTML.

这一项工作量大, 建议单独排期, 且必须先补行为测试再动结构 (Refactoring: 先钉住行为).

---

## 三, 边界纪律缺口: 同一类解析散落在各处 (Boundary Discipline) -- 已完成

"Parse outside data once, where it comes in."

### 落地

- `web/middleware/params.clj` 新增 `wrap-parse-params`, 挂在 `api/route-data` 的中间件链里,
  只在声明 `:params/parse?` 的路由组上生效:
  - `/business` 声明 `:params/parse? true` (默认表单优先, multipart 部件原样保留);
  - `/pms` 声明 `:params/parse? true` + `:params/priority :query` (它的 REST 契约靠 `?page=0`
    这种查询串做校验, 创建接口的表单又是 JSON 体).
- `bu/kquery` 改为调用同一个 `parse-params`, 并同时合并 `:params`, 保持 ring 的合并语义;
  入口已解析时是幂等的, 控制器单测按 string 键构造请求也得到同样结果.
- 数字串转整数只放行会**往返一致**的值: `007` / `1.0` / `1e3` 保持字符串原样.

### 落地时暴露并修掉的真实回归 (先 Make it Work, 再 Make it Right)

入口解析改变了键类型, 三处按字符串读取查询串的代码随之失效, 全部由全量测试抓到:

1. `bpm/instance/print-data` 读 `:id` 后 `(some-> (get ... :id) Integer/parseInt)` —— 键值已是整数,
   `Integer/parseInt` 抛异常 => 500. 改用 `paging/optional-id`.
2. `bpm/sign-list` / `bpm/return-list` 把 `:taskId` 直接交给 Flowable
   `taskParentTaskId`, 要求字符串; 变成整数后 `Long cannot be cast to String` => 500.
   改为一律 `str`.
3. `bu/kquery` 原先只读 `:query-params`; 控制器测试构造的请求把字段放在 `:params`,
   于是 `name=张` 被静默丢弃. 改为两个来源合并.

`parse-params` 第一版用 `assoc` 一次性写 `:query-params` 与 `:params`, 后者读到的是
被前者覆盖后的请求 —— 这类求值顺序陷阱已通过先绑定再 `assoc` 消除, 并补了优先级用例.

### 证据

- `test/clj/com/ruoyi/web/middleware/params_test.clj`: 数字串/原样保留/负数/multipart/
  空请求/两来源优先级/kquery 幂等.
- 全量 `clojure -M:test`: 891 tests / 8550 assertions, 0 failures 0 errors.
- 真实 HTTP (admin 令牌): `oa/leave` `bpm/user-group` `hrm/employee` 带
  `page=abc&size=xyz` 均 200; `pms/projects` 的 `page=0` / `size=101` / `page=abc`
  均 400 且错误文案正确 (说明查询串真的流到了校验层, 而不是被丢弃后走默认值).
- 浏览器: 项目中心、请假、报销页面渲染正常.
- shadow-cljs: 0 warnings.

---

## 四, 覆盖率数据本身的可用性

`bb coverage` 跑完一次需 **约 75 分钟** (875 tests 全量插桩). 上一轮为了看办公三模块的数字,
中途尝试了三次参数才成功 (`-n` 同时作用于被测和测试命名空间; `--test-ns` 不存在;
正确的是 `-t <test-ns-regex>`). 这些参数弯路没有沉淀到文档里, 下次还会再踩.
本轮另发现 `bb coverage` 完成后 `target/coverage/*.html` 会被清理, 只剩 `coverage.txt`,
而 crapper/mutator 的逐行覆盖率**只认 HTML 里的 `forms covered`**. 详见待办第 4 条.

---

## 五, 顺带发现 (低优先, 仅记录)

- `domain/pms/config/catalog.clj` 140 行从未被任何测试触达 (覆盖 0%), 但它是纯数据目录,
  风险低; 若确有引用路径, 一个断言目录完整性/唯一性的测试即可锁住.
- `web/middleware/authz.clj` 覆盖 0%, 但 `test/clj/com/ruoyi/web/authz_test.clj` 存在 ——
  这是**测量口径问题** (该测试可能只校验路由声明而不经过中间件函数), 值得核对是否漏测了实际授权路径.

---

## ub-mode 三工具实测基线 (2026-11-23)

| 工具 | 基线 | 说明 |
|------|------|------|
| crapper | 1982 个函数, CRAP>10 共 101 个 | bpm 16, bpm.core 13, obs.trace 8; 最高 `resolve-candidate-users` 217.8 |
| dryer | 632 对相似度 >=0.82 (566 对 =1.00), 69 个文件 | 跨文件 478 对, 同文件 154 对 |
| mutator | 5762 个变异点 (369 个未被覆盖) | 定点跑受限于覆盖率格式, 见下 |

第一条完成后 dryer 从 632 降到 620 对.

**mutator 的真实限制**: 它的 Clojure 覆盖率只认 `target/coverage/**/*.html`, 默认试 `clj -M:cov --lcov`,
本项目没有 `:cov` 别名; `--coverage-command` 也救不回来, 因为命令之后仍从磁盘读 bundle,
而 HTML 已被清理. 要拿到变异分数需要: 先产出 HTML 覆盖率, 再为每个被测文件指定对应的定向测试命令,
否则全量测试 × 每个变异点不可行 (单次 `clojure -M:test` 启动就要 22-37 秒).

---

## 落实状态

1. **第一条 (叶子命名空间 + 7 处替换 + leave/reimburse 的 user_id)** -- 已提交 `c9b4851`.
2. **第三条 (入口解析)** -- 本轮完成, 消掉 3 处真实回归.
3. 第四条 (记录覆盖率命令) 与第二条 (BPM 拆分)、第五条 (authz 测量口径) 仍待办.
