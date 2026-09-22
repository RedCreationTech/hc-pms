# HC-PMS Batch 1 验证记录

日期: 2026-09-22. 范围为本轮新增PMS功能及其模板接入, 不是整个上游办公系统的全面回归.

## 环境与来源

- 上游 `ruoyi-template` commit `99706b06fea4ff9efed6822a70cbda1c2bd98552`.
- 新建工作副本和SQLite数据库, 本地HTTP监听 `127.0.0.1:3100`, 未连接外部生产业务数据.
- 本地 Java 25, Clojure CLI 1.12.4.1618, Node 24, pnpm 11.19.0, Chrome.
- CI使用Java21/Node22/pnpm10及MySQL 8.0.46. 代码提交 `5c290d587f81d507585fa15e407800c991d2d78b` 的 [GitHub Actions验证](https://github.com/RedCreationTech/hc-pms/actions/runs/35675216573)已通过sqlite/mysql/web三个作业.

## 已通过

| 验证 | 结果 | 说明 |
|---|---|---|
| Clojure编译及加载 | 通过 | 新namespace在专用nREPL加载, 实际HTTP挂载 |
| PMS后端集成 | SQLite及MySQL各10 tests / 75 assertions, 0失败, 0错误 | 本地SQLite及CI两种独立数据库 |
| SQLite迁移往返 | 通过 | 全量up, 仅PMS003/002/001逆序down, 再up |
| 前端开发编译 | 通过, 0 warnings | 新增PMS页面及路由 |
| 前端生产编译 | 通过, 0 warnings | `pnpm exec shadow-cljs release app` |
| 浏览器真实流程 | 2 passed, 0 pageerrors | Chrome, 项目闭环及驾驶舱独立用例 |
| GitHub Actions | sqlite/mysql/web全部通过 | 新建MySQL 8.0.46完整up迁移及相同领域/API测试, 前端生产编译和浏览器流程 |
| 后端uberjar构建 | 通过 | `clojure -T:build all`, 最终打包已采用生产静态文件 |
| C4图渲染 | 通过 | 本地PlantUML内置C4库, 已目视检查容器图 |
| 差异空白检查 | 通过 | `git diff --check` |

后端通过nREPL执行 `require :reload` 和 `clojure.test/run-tests` 验证. 独立复现命令: `clojure -M:test -n com.ruoyi.pms-test`.

测试覆盖: 合法创建/详情/分页, 缺身份401, 无功能权限和跨项目403, 缺对象404, 输入400, 编号重复409, 日期/用户/部门校验, 禁止状态mass-assignment, 主子单机层级, 跨项目挂接拒绝, 成员读写范围, 陈旧版本409, 实际两连接并发只有一个成功, 非法状态与Gate前置拦截, 终态只读, 审计失败回滚, 聚合版本排序, 根节点编号/名称同步, 停用用户与角色撤销, 未知异常安全500.

迁移往返确认4张PMS表, 9项菜单及9项管理员授权删除/恢复正确, 原有系统用户保留; 再通过领域服务建立项目, 主节点, 经理成员与版本1审计.

首次公开CI在模板BPM的MySQL迁移中发现`TEXT NOT NULL DEFAULT ''`不兼容, 尚未进入PMS测试即中断. 将其改为MySQL 8.0.13+支持的表达式默认值`DEFAULT ('')`后, 保留原字段类型和空字符串含义, 重新全量迁移及10 tests / 75 assertions已通过. SQLite迁移未改. 此次MySQL验证覆盖全新库up与PMS业务, 不替代生产升级/回退或历史数据迁移演练.

本记录与功能矩阵的随后更新只修改文档, 测试证据固定引用上述代码提交和运行编号, 不把文档提交虚称为新的代码测试结果.

## 浏览器验收

使用本任务全新数据库中的合成演示项目, 不包含客户真实数据. 验收用例位于 `tests/e2e/pms.spec.js`, 2个独立流程已通过, 页面未捕获JavaScript错误为0:

1. UI创建项目, 添加子项目和单机, 添加只读协作成员, 编辑资料并检查根名称同步, 立项和进入计划, 刷新后持久化, 取消及终态只读.
2. 搜索与状态筛选, 地址恢复, 详情刷新, 空态和重置, 动态菜单导航, 驾驶舱与真实接口统计一致.

演示库只有管理员初始用户, 测试前置数据增加一个无系统角色的合成协作成员. Playwright报告保存在本地 `playwright-report/`, 截图作为仓库之外的输出文件交付. 初次用例失败来自测试定位器与不存在的模板示例用户假设, 已按真实DOM/options修正; 修正后两用例均通过.

## 尚未验证或未交付

- MySQL生产升级/回退, 既有业务数据迁移与完整生产容量尚未验收. 本机Docker仍不可用, 已由CI独立MySQL 8.0.46完成全新库迁移及PMS集成实测.
- 继承模板的全部历史E2E/办公/BPM回归未重跑, 仓库既有超长namespace/函数不在本次全面重构范围. 新PMS namespace和函数符合500/40行限制.
- Docker镜像与生产部署, 大规模性能, 备份恢复, 多租户隔离未验收. 旧Dockerfile仍继承模板, 不宣称完成生产容器化交付.
- 完整计划/Gate/URS/证据/四算/企业适配/AI均为后续功能, 见路线图.
- 用户于2026-09-22明确授权将工程及其模板历史推送到公开的 `RedCreationTech/hc-pms`. 原始业务PPT, 内部逐页提炼和运行数据仍不发布. 公开源码不代表已完成生产部署.

## 主要修复

1. 沿用实际ClojureScript栈, 避免按先前Vue3设想创建不兼容前端.
2. 删除未使用的旧`react-quill`依赖, 恢复package与锁文件一致; 现有页面仍使用`react-quill-new`.
3. 项目基础信息修改同步树根, 防止台账和结构不一致.
4. 事件追加聚合版本并按版本排序, 避免同秒审计乱序.
5. 识别数据库包装异常中的锁冲突, 并发失败返回409; 未知错误不向客户端泄露SQL与异常数据.
6. 修复继承模板BPM迁移的MySQL TEXT默认值语法, 双库CI均通过, 不通过跳过模板迁移规避失败.
