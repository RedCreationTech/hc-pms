# 代码质量度量 (crapper / dryer / mutator)

项目用 ub-mode 的三件度量工具追踪 CRAP 分数、重复代码与变异分数.
本文记录它们的安装, 用法, 以及踩过的坑.

## 安装

三件工具是独立的 Python 包, 与本项目仓库无关:

```sh
git clone <ub-tools>/crapper  /path/to/ub-tools/crapper
git clone <ub-tools>/dryer    /path/to/ub-tools/dryer
git clone <ub-tools>/mutator  /path/to/ub-tools/mutator   # 要求 ../crapper 同父目录

for t in crapper dryer mutator; do
  d=/path/to/ub-tools/$t
  $d/.venv/bin/pip install setuptools wheel                  # 必须, 否则下面报 BackendUnavailable
  $d/.venv/bin/pip install --no-build-isolation -e $d
done
```

装好后在 `~/.local/bin` 里放**薄包装脚本**, 不要用符号链接:

```sh
#!/bin/sh
exec /path/to/ub-tools/crapper/crapper "$@"
```

脚本按自身路径推断项目根, 符号链接会让它去 `~/.local/bin` 找 `pyproject.toml`.

## 用法

在项目根目录执行:

```sh
crapper --use-existing-coverage --source-root src/clj   # 写 .metrics/crap.edn
dryer   --source-root src/clj                           # 写 .metrics/dry.edn
mutator --scan --source-root src/clj --use-existing-coverage   # 只列变异点, 不跑测试
```

三件工具都会跳过 `test`/`spec`/`vendor`/`node_modules`/`target`; 产物 `.metrics/` 已 gitignore.

## 坑

### 覆盖率必须来自 HTML 报告

crapper 与 mutator 的逐行覆盖率只解析 `target/coverage/**/*.html` 里的
`<span title="N out of M forms covered">`. `coverage.txt` 只有文件级数字, 不能被它们使用.

**HTML 不存在时所有函数的覆盖率都被当成 0%**, CRAP 会算出 4032 这样的荒谬值.
看到这类数字先确认 `find target/coverage -name "*.html" | wc -l` 不是 0.

### `bb coverage` 跑完会清理 HTML

全量 cloverage 跑完后 `target/coverage/*.html` 会被清掉, 只剩 `coverage.txt` 和空目录.
需要逐行数据时, 在清理前备份 HTML, 或改用下面的定向输出目录.

### 全量覆盖率约 75 分钟

875+ 个测试全量插桩非常慢. 只看某几个模块时用定向参数:

```sh
clojure -M:coverage -t "com.ruoyi.business.office-test" -o target/coverage-office --text
```

`-t` 是**测试**命名空间正则; `-n` 会同时过滤被测命名空间, 容易一个测试都选不中;
`--test-ns` 不存在.

### mutator 的覆盖率获取链

mutator 默认为 Clojure 跑 `clj -M:cov --lcov`, 本项目没有 `:cov` 别名, 必然失败,
结果是每个变异点都被判为 UNCOVERED. `--coverage-command` 不能解决: 命令跑完后它仍然
从磁盘读覆盖率 bundle, HTML 不在就还是没有数据.

要拿到真实变异分数需要两步:

1. 先产出 HTML 覆盖率 (见上), 并确认 `find target/coverage -name "*.html"` 有内容;
2. 用 `--test-command` 指定**定向**测试, 不要用全量 —— 单次 `clojure -M:test` 启动就要
   22-37 秒, 乘以变异点数不可行.

```sh
mutator --source-root src/clj \
        --test-command "clojure -M:test -n <ns-regex>" \
        src/clj/com/ruoyi/domain/business/leave.clj
```

另外 `mutator` 传具体文件路径时仍会扫描全项目, 需要配 `--changed` 或 `--lines` 收窄.

### CRAP 只有在 HTML 覆盖率在场时才可比较

CRAP = CC^2 x (1 - 覆盖率)^3 + CC. 缺少 HTML 覆盖率时所有函数覆盖率被当成 0,
分数退化成 CC^2 + CC, 整个项目会被误算成上千个函数超标, 最高分会上万.
不同轮次之间**只能在同一覆盖率口径下比较**, 否则数字没有意义.

## 基线 (2026-11-23)

| 指标 | 数值 |
|------|------|
| 函数总数 | 1982 |
| CRAP > 10 | 101 个 (bpm 16, bpm.core 13, obs.trace 8) |
| 最高 CRAP | `bpm/resolve-candidate-users` 217.8 (CC 43, 覆盖 54.4%) |
| 相似度 >= 0.82 的重复对 | 632 (566 对完全一致), 涉及 69 个文件 |
| 变异点 | 5762 (其中 369 个在当前覆盖率下未被覆盖) |

修正 `domain/business/paging` 之后重复对从 632 降到 620.

把 BPM 的 Webhook/节点监听器实现抽到 `domain/business/webhook`, 业务表 JSON 解析抽到
`domain/business/json_field` 之后仍是 620: BPM 原文件内部的重复基本已被同一次抽取消掉,
剩下的重复集中在系统管理页面与 `pms/planning` 的模板化代码, 不是 BPM 的属性.