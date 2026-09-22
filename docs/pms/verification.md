# HC-PMS 完整本地业务闭环验证记录

日期: 2026-09-22. 本次范围为新增PMS计划,治理,交付,财务,接口运维及正式收尾工作台,并保留原项目中心回归. 这是当前实现的工程验证,不表示106项PPT/标准补全功能全部完成或生产业务签收. 原Batch 1结果保留在[历史记录](verification-batch1.md).

## 已完成验证

| 验证 | 实际结果 | 范围 |
|---|---|---|
| 本地全新SQLite后端 | 62 tests / 423 assertions,0失败/错误 | 7个测试命名空间,真实迁移/身份/事务/业务服务,包含实际本地HTTP回执 |
| CI SQLite / MySQL 8 | SQLite 62 tests / 423 assertions; MySQL 62 tests / 392 assertions,两库0失败/错误 | 修复提交cfe4b15,独立空库全量PMS套件,包括真实并发 |
| 本地Chrome真实浏览器 | 8 passed,2.7分钟,0 flaky/跳过/pageerrors | 原2例+工作台5例+受控延迟刷新1例,主操作者与独立审核者分别登录 |
| Linux CI Chrome浏览器 | 8 passed,5.1分钟,均首次通过 | 8次执行,无重试/失败/flaky;包含真实完整交付/关闭/重开和受控延迟刷新 |
| 生产前端 | 4026 files / 19 compiled / 0 warnings,22.94s | shadow-cljs release app,与当前工作台完整源代码一致 |
| 后端发布包 | 构建通过,约99MiB | clojure -T:build all,生产静态文件已打包 |
| 独立发布包启动/关闭 | 通过 | 隔离业务库与Flowable库,health=up,项目页HTTP200,未登录API HTTP401,实际登录及项目创建/读取通过,引擎与HTTP正常关闭 |
| SQLite新增迁移往返 | 34项断言全部通过 | 隔离库全量up -> 009..004逐项down -> 再up,启用外键,保留上游和001-003数据 |
| 代码规模与差异 | 通过 | 69个PMS源码文件,1207个函数形式,33个SQL/迁移文件;最大namespace266行/函数39行/SQL131行 |

本轮双库结果来自[cfe4b15的CI运行](https://github.com/RedCreationTech/hc-pms/actions/runs/35682432061),不是旧Batch 1结果. 该运行SQLite/MySQL/web三项全部success. Linux浏览器8个用例首次通过,共5.1分钟,无重试或flaky;本地生产构建的同组8例2.7分钟通过.

分项后端结果,并发套件最后一行列出两库差异:

| 模块 | 测试/断言 |
|---|---|
| 原项目中心 | 10 / 75 |
| 计划排程/资源/基线 | 11 / 84 |
| 治理/风险复审/问题重开 | 10 / 92 |
| 交付执行 | 8 / 45 |
| 财务/关闭/受控重开 | 11 / 52 |
| 接口消息/真实HTTP回执 | 6 / 33 |
| 写锁等待/连接恢复/版本并发 | SQLite 6 / 42; MySQL 6 / 11 |

新增并发套件中31条断言验证SQLite驱动特有行为,其余11条也在MySQL执行;两库最终断言总数按各自CI实际结果记录.

## 可复现命令

```bash
clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
# 必须指向一次性独立空库;六个模块使用互不冲突的合成用户编号
PMS_TEST_JDBC_URL='jdbc:mysql://127.0.0.1:3306/hc_pms_test?user=USER&password=PASSWORD&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' clojure -M:test -d test/clj -r 'com.ruoyi.pms.*-test'
BASE_URL=http://127.0.0.1:3100 pnpm exec playwright test tests/e2e/pms.spec.js tests/e2e/pms-workbench.spec.js --project=chromium
pnpm exec shadow-cljs release app
clojure -T:build all
```

独立发布包冒烟在临时目录使用以下配置启动,轮询`/api/health`后核对`/pms/project`和未登录`/api/pms/projects`;再使用隔离库的模板演示账号实际登录,创建并读取一个合成草稿项目,确认version=1/status=draft. 最后终止该进程并检查正常关闭日志:

```bash
PORT=3101 HTTP_HOST=127.0.0.1 NREPL_PORT=0 FLOWABLE_ASYNC=false \
  JDBC_URL=jdbc:sqlite:/ABSOLUTE/TEMP/jar-smoke.db MIGRATION_DIR=migrations-sqlite \
  FLOWABLE_JDBC_URL='jdbc:h2:mem:pms_release_smoke;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
  java -jar target/rouyi-standalone.jar
```

`/ABSOLUTE/TEMP`需替换为独立临时目录. `DB_CLOSE_ON_EXIT=FALSE`避免H2的JVM关闭钩子先于Flowable关闭而删除内存表;当前演示服务及其数据库不参与该检查.

[迁移往返与规模检查完整复现](migration-verification.md). 测试使用合成项目和本地HTTP接收方,未连接企业生产系统或真实客户资料.

## 核心通过场景

1. 四种依赖关系,工作日/例外日历,已知并行网络的CPM与浮动,树形任务隔离和循环拒绝;跨项目人员占用仅显示匿名汇总. 提交计划锁定,独立批准形成不可变基线,执行期重基线绑定已批准变更. 审批中变更失效仍可驳回解除锁定.
2. 章程批准,URS版本/整批CSV预检与事务导入,真实文本字节与SHA256下载一致,需求版本追踪,Gate缺证据阻断和独立签核,会议行动幂等转WBS. 风险定期复评/独立关闭,风险转问题,问题整改独立验证及带证据的受控重开.
3. 物料申请,独立BOM冻结,真实齐套,装配开工及独立交检,SIT/FAT/SAT结果和失败问题,真实日期约束与上游复验使下游旧资格失效,发运及独立签收,售后异常独立关闭. 交付对象保留任务/URS/证据追踪.
4. 整数金额,小数精度,负数调整/零成本确认/零收入负毛利,同源账项去重,批准成本不可改写. 工时只参与批准后分摊,跨项目同日不超过24小时,拒绝释放容量;分摊输入冻结,总额守恒,零分母阻断,幂等不重计.
5. 实际创建并批准章程/基线/执行Gate后进入执行,完整完成物料至签收与SAT,工时/成本审核,任务完工,关闭Gate/决算/清单,独立关闭审批与归档. 审批后内容变化拒绝关闭;关闭后只读;独立重开返回closing且必须重新关闭审批.
6. 身份和功能权限实时验证,无财务权限不可读取金额,通用审计不暴露财务审核意见. 撤销成员立即失去项目范围,仍有任务/指定审批/移交责任时要求先交接. 审计故障时对象和项目版本共同回滚.
7. 入站同ID同内容幂等/冲突拒绝/低版本不倒退;外发只读取真实已批准版本. 实际HTTP2xx仍需匹配message_id/receipt_id;错误回执退避到死信,人工重试保留原业务键. 超大正文取消,完整响应超时包含正文停顿,未知配置503而不是假成功.

## CI故障修复轨迹

首次新代码运行 `6823cea` 的SQLite通过;MySQL完成新迁移及前几个模块后,发现交付和接口测试都使用角色9500,共享测试库主键冲突. 已把接口模块的合成用户隔离到9600段,没有修改业务断言或跳过模块,本地接口6/33复验通过. 修复提交 `276c52c` 重新执行完整CI,SQLite和MySQL均通过. 浏览器运行发现慢环境下下拉框旧选项和偶发SQLite写锁冲突,随后在cfe4b15修复并补充专项回归.

本地首次全量回归保留的旧断言仍要求旧提示包含Gate,而真实新实现先提示缺少批准基线. 已将断言对齐真实第一项准入规则,仍验证HTTP409且不跳过生命周期检查. 合成浏览器下拉定位/页签重挂载/差异JS对象转换问题均已修复并通过原7例回归,后续增加延迟场景后完整8例通过.

浏览器CI暴露了两个真实问题,而不是单纯的定位器超时. 保存成功后GET尚未完成时可继续打开弹窗,会固化旧关联选项及项目版本; 受控延迟回归已证明旧实现失败,修复为保留页签并在两类资源都刷新完成前禁用操作. 同时,鉴权在线心跳与PMS默认DEFERRED事务的读后写升级争用SQLite锁,新增持锁回归在旧实现出现8项失败. 修复为PMS写事务在读取前以IMMEDIATE取得写锁,最多等待5秒,不重放业务回调;等待超时,业务失败及成功均恢复池连接状态. 仍保留真实旧版本409和审计失败全回滚. 修复提交`cfe4b15`的完整最终CI已全部通过,实际结果见本页验证表. 保留全部业务断言,没有增加测试重试次数或放宽等待超时来掩盖问题.

## 实际交付边界

- 本次已实现和已验证子能力与未完功能分别列入[106项矩阵](11-feature-acceptance-matrix.md). 不能把部分实现的PPT复合条目标为全部完成.
- 八类真实系统字段/认证/责任边界/沙箱尚未提供;本地通用协议和回执测试不代表CRM/OA/ERP/PLM/MES/SRM/销服物料/BI已联通.
- 完整主子单机计划网络/进度卷积/局部暂停,专属业务模板/RACI/沟通通知,二进制/密级/批量文档及正式签发,完整财务更正封期/费率/跨项目池/汇率税务/经营口径,AI等仍按矩阵保留真实待办.
- 生产升级/回退和备份恢复,负载/兼容性/既有数据迁移/业务UAT尚未验收. 未重跑继承模板全部历史办公/BPM测试. 公开源码和本地启动不等于生产部署.
