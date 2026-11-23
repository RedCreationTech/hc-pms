# ub-mode 视角的优化盘点 (2026-11-22)

依据: 全量 Cloverage 实测 (883 tests / 8509 assertions, 整体行覆盖 87.26%),
覆盖率 HTML 的逐行未覆盖数据, 以及对 `src/clj` 的结构化检索.
工具情况: 环境中 **没有** crapper / dryer / mutator 可执行文件, 下述对应条目
是用等价手工手段 (覆盖率 HTML 逐行提取, 结构检索, 重复度比对) 得到的,
不冒充工具输出.

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

## 三, 边界纪律缺口: 同一类解析散落在各处 (Boundary Discipline / Type System Discipline)

"Parse outside data once, where it comes in."

目前查询串在**每个领域函数内部**各自解析:

- `Integer/parseInt` 在 `infra/scheduler.clj:98` 附近还有一份带 `re-matches` 守卫的版本
  (写法正确, 但又是一种实现).
- `templates`/`monitor` 等处直接调用 `System/getProperty`, `Runtime/getRuntime` 等
  环境读取穿插在控制器里.

**建议**: 一次性在入口 (路由或 `kquery`) 完成 "查询串 → 已解析的强类型 map",
领域层拿到的就是整数. 这样第 1 条的一类缺陷在入口就被挡住, 而不是每个函数各挡一次.

---

## 四, 覆盖率数据本身的可用性

`bb coverage` 跑完一次需 **约 75 分钟** (875 tests 全量插桩). 上一轮为了看办公三模块的数字,
中途尝试了三次参数才成功 (`-n` 同时作用于被测和测试命名空间; `--test-ns` 不存在;
正确的是 `-t <test-ns-regex>`). 这些参数弯路没有沉淀到文档里, 下次还会再踩.

**建议**: 在 `docs/guides/` 或 `bb.edn` 注释里记录: 全量约 75 分钟, 定向复测用
`clojure -M:coverage -t "ns-regex" -o target/coverage-x --text`.

---

## 五, 顺带发现 (低优先, 仅记录)

- `domain/pms/config/catalog.clj` 140 行从未被任何测试触达 (覆盖 0%), 但它是纯数据目录,
  风险低; 若确有引用路径, 一个断言目录完整性/唯一性的测试即可锁住.
- `web/middleware/authz.clj` 覆盖 0%, 但 `test/clj/com/ruoyi/web/authz_test.clj` 存在 ——
  这是**测量口径问题** (该测试可能只校验路由声明而不经过中间件函数), 值得核对是否漏测了实际授权路径.

---

## 建议的落实顺序

1. **先做第一条** (叶子命名空间 + 7 处替换 + leave/reimburse 的 user_id). 收益: 消除 5 处
   可触发的 500, 消掉 4 份重复实现, 改动局限在 `domain/business/*`.
2. 再做第三/四条 (入口解析 + 记录覆盖率命令).
3. 第二条 (BPM 拆分) 单独排期, 先补行为测试.
4. 第五条的 authz 测量口径核对.

以上均未改动任何代码, 等你确认范围后再执行.