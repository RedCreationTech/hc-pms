# RuoYi-Vue 18项标准功能差异审计

> 审计日期: 2026-07-02  
> 当前库: `/Users/a123/gt/ruoyi_clojure/mayor/rig`  
> 参考库: `/private/tmp/RuoYi-Vue-v3.9.2`,来源 `https://gitee.com/y_project/RuoYi-Vue.git`,tag `v3.9.2` / commit `0e2d75c23c0d7a1fa85f660f06a59a4dd1ba14c0`  
> 范围: 仅审计 `RUOYI_VUE_COMPARISON.md` 的 "RuoYi-Vue 完整功能清单" 18项.当前项目自增菜单,业务模块,工作流,Integrant 监控等不纳入缺口判定.

## 总结

当前 Clojure 库已经为 18 项标准菜单都提供了入口页面或接口,但多数模块仍是"可用 CRUD/展示"层级,未完全复刻 RuoYi-Vue 的查询条件,表格交互,弹窗字段,表单校验,按钮权限,批量操作,Excel 导出,引用检查和业务保护.

主要共性差异:

- 前端权限按钮: RuoYi-Vue 使用 `v-hasPermi`/`v-hasRole` 控制按钮级权限;当前页面多数按钮没有按 `perms` 做显隐控制.
- 表格工具栏: RuoYi-Vue 标准页普遍有搜索区显隐,刷新,列显隐;当前已补通用搜索区显隐能力,但列显隐只在少数页面完整实现.
- 批量选择: RuoYi-Vue 列表页普遍支持批量删除,批量导出或批量授权;当前多数模块只支持单行操作.
- 表单校验: RuoYi-Vue 使用 `rules` 对必填,长度,邮箱,手机,cron,唯一性等做校验;当前 Ant Design 页面很多只有轻量必填或没有后端二次校验.
- 后端权限与保护: RuoYi-Vue 控制器通过 `@PreAuthorize`,Service `check*Allowed`,`check*Unique`,删除前引用检查保护关键数据;当前后端大多只依赖登录态,业务层保护不完整.
- 标准表缺失: 当前库没有 `sys_role_dept`,`gen_table`,`gen_table_column` 等原版关键表,导致自定义数据权限,代码生成配置持久化无法等价实现.
- 路径与返回形态: 当前 API 路径适配为 `/api/system/*`,`/api/tool/gen/*`,与 RuoYi-Vue `/system/*/list`,`/monitor/*`,`/tool/gen/list` 不完全一致.若目标是行为复刻,可接受路径适配;若目标是前端/API 兼容,则需要补兼容路由.
- 导出格式: RuoYi-Vue 使用 Excel 导出;当前多处是 CSV 导出,字段少于原版.

## 逐项差异

### 1. 用户管理

源码定位:

- 参考前端: `ruoyi-ui/src/views/system/user/index.vue`,`ruoyi-ui/src/views/system/user/authRole.vue`.
- 参考后端: `SysUserController`,`SysUserServiceImpl`,`SysUserMapper`,`SysUserRoleMapper`,`SysUserPostMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/user.cljs`.
- 当前后端: `src/clj/com/ruoyi/web/controllers/system/user.clj`,`src/clj/com/ruoyi/domain/system/user.clj`,`resources/sql/system.sql`,`system/import_export.clj`.

参考项目业务:

- 检索区: 用户名称,手机号码,状态,创建时间范围;左侧部门树支持部门名称过滤.
- 表格: 多选,用户编号,用户名称,用户昵称,部门,手机号码,状态,创建时间,操作;右侧工具栏支持搜索显隐,刷新,列显隐.
- 操作: 新增,修改,删除,导入,导出,重置密码,分配角色,状态切换;`admin` 用户受保护.
- 弹窗/新页: 新增/修改弹窗包含部门,昵称,手机号,邮箱,用户名,密码,性别,状态,岗位,角色,备注;分配角色为独立页.
- 后端: 列表带数据权限;新增/修改检查用户名,手机号,邮箱唯一;维护用户-角色,用户-岗位关联;删除支持批量并保护 admin;导入支持 `updateSupport`;导出 Excel.

当前库差异和漏项:

- 已覆盖主要页面骨架: 左侧部门树,查询区,用户表格,多选,搜索显隐,刷新,列显隐,新增/修改/删除,导入,导出,重置密码,分配角色,详情抽屉和状态切换均已有入口.
- 左侧部门树不是完全接口驱动: `user.cljs` 中仍有 `reference-dept-tree` 硬编码兜底;部门名称过滤输入框未接入真实过滤逻辑,点击部门只按当前部门 `dept_id` 查询,未按 RuoYi 语义包含下级部门.
- 查询条件仍不完整: 创建时间范围控件已展示,但未写入 `:users/query-params`,后端 `list-users` 也未支持 `beginTime/endTime`;表格缺少 RuoYi 的后端排序能力.
- 批量删除只是前端循环调用单条删除接口;后端没有等价的批量 `userIds` 接口,事务边界和逐项 `checkUserAllowed`/数据权限保护.
- 用户表单字段基本覆盖部门,昵称,手机,邮箱,用户名,密码,性别,状态,岗位,角色,备注,但校验只覆盖少量必填;手机号格式,邮箱格式,用户名/密码长度等规则未完整复刻.
- 后端创建/修改已维护用户-角色,用户-岗位关联,并已保护 `admin` 删除,检查 `user_name` 唯一;但缺少 `phonenumber`,`email` 唯一校验,也缺少完整 `checkUserAllowed`,`checkUserDataScope` 语义.
- 列表接口虽调用 `data-perm-filter`,但 SQL 只显式支持 `user_name/phonenumber/status/dept_id`,未真正拼入角色/部门数据权限过滤条件;普通用户数据范围控制仍不等价.
- 分配角色当前用弹窗多选完成,功能入口存在,但不是 RuoYi-Vue 的独立 `authRole.vue` 页面交互,也缺少按权限过滤可分配角色的完整约束.
- 导入/导出仍是 CSV,不是 RuoYi Excel;导入模板字段少,缺少 `updateSupport` 覆盖导入语义,文件上传解析链路一致性和逐行错误回执.

### 2. 部门管理

源码定位:

- 参考前端: `ruoyi-ui/src/views/system/dept/index.vue`.
- 参考后端: `SysDeptController`,`SysDeptServiceImpl`,`SysDeptMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/dept.cljs`,`components/dept_tree_select.cljs`.
- 当前后端: `system/dept.clj`,`domain/system/dept.clj`,`resources/sql/system.sql`.

参考项目业务:

- 检索区: 部门名称,状态;支持回车搜索,重置,搜索区显隐.
- 表格: 树形表格,字段为部门名称,排序,状态,创建时间,操作;支持展开/折叠.
- 操作: 新增部门,修改,删除,新增子部门.
- 弹窗: 上级部门,部门名称,显示排序,负责人,联系电话,邮箱,部门状态;手机号和邮箱格式校验.
- 后端: 列表按数据权限过滤;新增/修改检查同父级部门名唯一;修改时维护自身和子孙 `ancestors`;删除前检查子部门和部门用户;停用部门时校验子部门状态.

当前库差异和漏项:

- 前端有树形表格,检索,CRUD,部门选择,但表格字段缺少创建时间显示规则和参考页的完整展开/折叠状态管理.
- 右上角搜索显隐能力已通过共享组件补齐;列显隐仍未实现.
- 新增/修改弹窗字段基本覆盖,但手机号,邮箱等格式校验不足;上级部门选择缺少参考项目“排除当前部门及子部门”的保护.
- 后端已计算并递归更新 `ancestors`,这是已覆盖点.
- 后端未检查同父级部门名唯一.
- 后端删除前未检查是否存在子部门或部门用户,可能删除仍被引用的部门.
- 后端未实现停用部门时对子部门状态的校验/约束.
- 列表查询未完整接入用户数据权限过滤.

### 3. 岗位管理

源码定位:

- 参考前端: `ruoyi-ui/src/views/system/post/index.vue`.
- 参考后端: `SysPostController`,`SysPostServiceImpl`,`SysPostMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/post.cljs`.
- 当前后端: `system/post.clj`,`domain/system/post.clj`,`resources/sql/system.sql`,`system/import_export.clj`.

参考项目业务:

- 检索区: 岗位编码,岗位名称,状态.
- 表格: 多选,岗位编号,岗位编码,岗位名称,岗位排序,状态,创建时间,操作.
- 操作: 新增,修改,删除,导出,支持批量删除;右侧工具栏支持搜索显隐,刷新,列显隐.
- 弹窗: 岗位名称,岗位编码,岗位顺序,岗位状态,备注;岗位名称,编码,排序必填.
- 后端: 分页列表,详情,新增,修改,删除,导出;检查岗位名称唯一,岗位编码唯一;删除前检查是否分配给用户.

当前库差异和漏项:

- 前端有岗位列表,检索,CRUD,导出;右上角搜索显隐已通过共享组件生效.
- 表格缺少可用的批量选择联动,顶部“修改/删除”按钮长期 disabled;批量删除未接通.
- 表单校验较轻,未完全按参考页要求校验岗位名称,编码,排序.
- 后端未检查 `post_name`,`post_code` 唯一.
- 后端删除前未检查 `sys_user_post` 是否引用该岗位.
- 导出为 CSV,非 RuoYi Excel.

### 4. 菜单管理

本轮已修改内容摘要:

- 修复菜单管理页加载失败问题: `@dnd-kit/core` 的传感器调用改为 `useSensors/useSensor`,避免 `PointerSensor.useSensor is not a function`.
- 菜单表格改为更接近 RuoYi-Vue 的树形展示: 第一列展示菜单图标和名称,并按父子层级缩进;默认展开树;保留展开/折叠按钮.
- 表格字段调整为参考项目顺序: 菜单名称,类型,排序,权限标识,组件路径,状态,操作;类型列用目录/菜单/按钮/外链标签;排序列改为可编辑 `InputNumber`.
- 图标体系改为使用 RuoYi-Vue `src/assets/icons/svg` 的 SVG 图标,补充 `resources/public/icons/svg`,侧边栏和菜单管理页可按菜单 `icon` 字段渲染.
- 新增/修改菜单弹窗按参考项目改为 `680px` 宽,`100px` 标签宽,两列表单布局,并按目录/菜单/按钮动态显示字段.
- 删除操作由气泡确认改为页面中间确认弹窗,文案按参考项目 `$modal.confirm` 形式显示“是否确认删除名称为\"xxx\"的数据项?”.
- 保存排序前端改为只提交变化项,并按参考项目 `menuIds`,`orderNums` 字符串格式提交;后端 `/system/menu/sort` 解析该格式后批量更新 `order_num`.
- 右上角搜索按钮已改为显示/隐藏搜索区,刷新按钮仍重新拉取列表.

源码定位:

- 参考前端: `ruoyi-ui/src/views/system/menu/index.vue`,`components/IconSelect/index.vue`,`assets/icons/svg`.
- 参考后端: `SysMenuController`,`SysMenuServiceImpl`,`SysMenuMapper`,`SysRoleMenuMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/menu.cljs`,`components/icon_picker.cljs`,`resources/public/icons/svg`.
- 当前后端: `system/menu.clj`,`domain/system/menu.clj`,`resources/sql/system.sql`.

参考项目业务:

- 检索区: 菜单名称,状态;支持搜索,重置,搜索区显隐.
- 工具栏: 新增,保存排序,展开/折叠.
- 表格: 树形表格,菜单名称列显示 SVG 图标和层级缩进;字段为菜单名称,类型,排序,权限标识,组件路径,状态,操作.
- 操作: 修改,新增子菜单,删除;删除确认文案为“是否确认删除名称为\"菜单名\"的数据项?”.
- 弹窗: 添加菜单/修改菜单,宽 `680px`;字段按目录/菜单/按钮动态显示.
- 后端: 列表按用户权限;菜单树,角色菜单树;新增/修改做菜单名唯一,外链地址,父级不能为自己,路由配置唯一校验;删除前检查子菜单和角色引用;排序事务更新.

当前库差异和漏项:

- 前端字段,树形缩进,图标,类型标签,排序输入,弹窗主要字段已基本对齐;仍存在 Ant Design 与 Element UI 的视觉差异.
- 图标选择器已使用 RuoYi SVG,但弹出层搜索,布局,尺寸,选中态与参考 `IconSelect` 仍不是逐像素一致.
- 操作按钮没有接入 `system:menu:add/edit/remove` 的按钮级权限显隐.
- 列表接口 `GET /system/menu` 支持 `menu_name/status/menu_type`,但未按非管理员角色过滤菜单列表.
- 缺少 `/system/menu/roleMenuTreeselect/{roleId}` 等价接口.
- 新增/修改后端缺少菜单名唯一,外链合法性,父级不能选择自身,路由配置唯一校验.
- 删除接口直接物理删除,缺少子菜单和角色分配检查.
- 保存排序接口已支持 `menuIds/orderNums`,但路径是 `/system/menu/sort`,参考项目是 `/system/menu/updateSort`;当前没有事务包装和统一异常文案.
- SQL 列表未实现 `visible` 条件.
- 动态路由生成逻辑未完整复刻 RuoYi `buildMenus/getRouteName/getRouterPath/getComponent/isInnerLink` 的所有分支.

### 5. 角色管理

源码定位:

- 参考前端: `system/role/index.vue`,`authUser.vue`,`selectUser.vue`.
- 参考后端: `SysRoleController`,`SysRoleServiceImpl`,`SysRoleMapper`,`SysRoleMenuMapper`,`SysRoleDeptMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/role.cljs`.
- 当前后端: `system/role.clj`,`domain/system/role.clj`,`resources/sql/system.sql`.

参考项目业务:

- 检索区: 角色名称,权限字符,状态,创建时间范围.
- 表格: 多选,角色编号,角色名称,权限字符,显示顺序,状态,创建时间,操作.
- 操作: 新增,修改,删除,导出,状态切换,数据权限,分配用户.
- 弹窗: 新增/修改角色时包含菜单权限树,支持展开/折叠,全选/全不选,父子联动;数据权限弹窗支持自定义部门树.
- 分配用户: 已分配/未分配两个列表,支持取消授权,批量取消,批量选择授权.
- 后端: 检查角色名称唯一,权限字符唯一;保护 admin 角色;删除前检查是否分配用户;维护 `sys_role_menu`,`sys_role_dept`.

当前库差异和漏项:

- 前端覆盖角色列表,CRUD,权限分配,数据权限,用户分配入口,但新增/修改角色弹窗没有直接内嵌菜单权限树,交互与 RuoYi 原版不同.
- 搜索区缺少创建时间范围;批量选择后的修改/删除联动不完整.
- 数据权限枚举语义不一致: 当前前端将 `"2"` 显示为“本部门数据”,`"3"` 为“本部门及以下”,`"4"` 为“仅本人”,`"5"` 为“自定义”;RuoYi 通常约定 `2=自定义`,`3=本部门`,`4=本部门及以下`,`5=仅本人`.
- 当前迁移缺少 `sys_role_dept`,后台 `data-scope` 只更新 `sys_role.data_scope`,没有保存 `deptIds`.
- `dept-tree-by-role` 的已选部门来源不可靠,当前 `sys_role` 表没有标准部门关联列.
- 后端未检查角色名/权限字符唯一,未完整保护 admin 角色,未检查角色是否已分配用户.
- 角色状态切换,数据权限,授权用户等接口与参考路径/参数不完全兼容.
- 标准模块列表查询未全面应用数据权限.

### 6. 字典管理

源码定位:

- 参考前端: `system/dict/index.vue`,`data.vue`,`detail.vue`.
- 参考后端: `SysDictTypeController`,`SysDictDataController`,`SysDictTypeServiceImpl`,`SysDictDataServiceImpl`,`SysDictTypeMapper`,`SysDictDataMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/dict.cljs`.
- 当前后端: `system/dict.clj`,`domain/system/dict.clj`,`resources/sql/system.sql`,`system/import_export.clj`.

参考项目业务:

- 字典类型页: 字典名称,字典类型,状态,创建时间查询;新增,修改,删除,导出,刷新缓存.
- 字典数据页: 按 `dictType` 进入详情页,支持字典标签,状态查询;字段包含字典标签,键值,排序,样式属性,回显样式,默认值,状态,备注.
- 后端: 字典类型唯一校验;删除类型前检查是否有关联数据;更新类型时同步字典数据的 `dict_type`;维护字典缓存.

当前库差异和漏项:

- 前端把字典类型和字典数据放在一个页面中切换,缺少 RuoYi `detail.vue` 独立详情页路由体验.
- 字典数据表单未完整暴露/编辑 `css_class`,`list_class`,`is_default` 等字段.
- 查询条件缺少创建时间范围;批量删除,列显隐,完整右工具栏未完全对齐.
- 后端虽然迁移有 `dict_type` 唯一索引,但 Service/Controller 未按 RuoYi 返回“字典类型已存在”的业务校验文案.
- 删除字典类型前未检查字典数据引用.
- 更新字典类型时未同步其下字典数据 `dict_type`.
- `refresh-cache` 是轻量/空操作,没有真实字典缓存与业务读取联动.
- 导出为 CSV,非 RuoYi Excel.

### 7. 参数管理

源码定位:

- 参考前端: `system/config/index.vue`.
- 参考后端: `SysConfigController`,`SysConfigServiceImpl`,`SysConfigMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/config.cljs`.
- 当前后端: `system/config.clj`,`domain/system/config.clj`,`resources/sql/system.sql`,`system/import_export.clj`.

参考项目业务:

- 检索区: 参数名称,参数键名,系统内置,创建时间范围.
- 表格: 多选,参数主键,参数名称,参数键名,参数键值,系统内置,备注,创建时间,操作.
- 操作: 新增,修改,删除,导出,刷新缓存;支持批量删除.
- 弹窗: 参数名称,参数键名,参数键值,系统内置,备注;名称,键名,键值必填.
- 后端: 参数键名唯一;查询 `configKey/{configKey}`;新增/修改/删除后刷新缓存.

当前库差异和漏项:

- 前端只按参数名称查询,缺少参数键名,系统内置,创建时间范围查询.
- 表格和操作基本可用,但批量删除,列显隐,刷新缓存按钮未完整对齐.
- 表单没有使用 AntD Form rules 强校验,只是界面标注必填.
- 后端迁移有 `config_key` 唯一索引,但 Service/Controller 未做 RuoYi 风格的唯一性业务校验和错误提示.
- 未暴露等价 `/configKey/{configKey}` 标准路由;领域层有 `find-config-by-key`.
- 缓存刷新语义不足,未形成参数缓存读写闭环.
- 导出为 CSV.

### 8. 通知公告

源码定位:

- 参考前端: `system/notice/index.vue`.
- 参考后端: `SysNoticeController`,`SysNoticeServiceImpl`,`SysNoticeMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/notice.cljs`.
- 当前后端: `system/notice.clj`,`resources/sql/system.sql`.

参考项目业务:

- 检索区: 公告标题,操作人员,公告类型.
- 表格: 多选,序号,公告标题,公告类型,状态,创建者,创建时间,操作.
- 操作: 新增,修改,删除;支持批量删除.
- 弹窗: 公告标题,公告类型,状态,公告内容;公告内容使用富文本编辑器.
- 后端: 标准字段为 `noticeTitle`,`noticeType`,`noticeContent`,`status`,CRUD 简洁,无复杂引用检查.

当前库差异和漏项:

- 当前数据库和接口使用 `notice_name`,不是 RuoYi 标准 `notice_title`;前端可展示但字段语义/API 兼容性不足.
- 前端查询条件缺少操作人员;公告类型/状态字典展示不完全等价.
- 公告内容没有富文本编辑器,只是普通输入/文本域.
- 表格批量删除,列显隐,右工具栏等未完整对齐.
- 后端 CRUD 基本存在,但路径,响应形态,字段命名与 RuoYi 不兼容.
- 当前未实现通知已读相关扩展;参考 v3.9.2 标准通知列表本身主要不依赖已读能力,因此这是扩展差异,不是核心缺口.

### 9. 操作日志

源码定位:

- 参考前端: `monitor/operlog/index.vue`,`detail.vue`.
- 参考后端: `SysOperlogController`,`SysOperLogServiceImpl`,`SysOperLogMapper`,以及 `@Log` AOP.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/oper_log.cljs`.
- 当前后端: `system/log.clj`,`domain/system/log.clj`,`web/controllers/monitor.clj`,`resources/sql/log.sql`,`web/middleware` 中操作日志中间件.

参考项目业务:

- 检索区: 系统模块,操作人员,操作类型,操作状态,操作时间范围.
- 表格: 多选,日志编号,系统模块,操作类型,请求方式,操作人员,操作地址,操作地点,操作状态,操作日期,消耗时间.
- 操作: 详情,删除,清空,导出;支持批量删除.
- 详情页: 展示请求 URL,请求方式,操作方法,请求参数,返回参数,错误消息等.
- 后端: `@Log(title, businessType)` AOP 精确记录模块,业务类型,请求/响应,异常,耗时.

当前库差异和漏项:

- 前端已实现列表,筛选,删除,清空,导出和详情弹窗,覆盖度较高.
- 查询条件与参考项仍有差异,例如操作类型,时间范围和状态字典的完整性需继续核对.
- 详情展示字段少于 RuoYi `detail.vue`,请求参数/返回参数/错误栈等展示不完整.
- 后端 `wrap-operlog` 可记录日志,但模块和业务类型主要通过 URL/HTTP 方法推断,精度低于注解式 `@Log(title, businessType)`.
- 操作地点,浏览器,操作系统,参数脱敏,排除敏感字段等能力不完整.
- 导出为 CSV,非 RuoYi Excel.

### 10. 登录日志

源码定位:

- 参考前端: `monitor/logininfor/index.vue`.
- 参考后端: `SysLogininforController`,`SysLogininforServiceImpl`,`SysLogininforMapper`,登录认证日志逻辑.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/login_log.cljs`.
- 当前后端: `system/log.clj`,`domain/system/log.clj`,`resources/sql/log.sql`,`auth.clj`.

参考项目业务:

- 检索区: 登录地址,用户名称,登录状态,登录时间范围.
- 表格: 多选,访问编号,用户名称,登录地址,登录地点,浏览器,操作系统,登录状态,操作信息,登录日期.
- 操作: 删除,清空,解锁,导出;支持排序和批量.
- 后端: 登录成功/失败都记录;解锁会清除密码错误缓存 `pwd_err_cnt`.

当前库差异和漏项:

- 前端有列表,删除,清空,导出;解锁按钮/事件存在迹象,但标准 `/unlock/{userName}` 后端能力不完整.
- 查询条件缺少登录时间范围或未完整接入;浏览器,OS,地点字段展示依赖后端采集质量.
- 后端登录日志存在,但账户锁定,密码错误次数缓存和解锁语义未按 RuoYi 实现.
- IP 地点,浏览器,OS 解析较简化.
- 导出为 CSV.

### 11. 在线用户

源码定位:

- 参考前端: `monitor/online/index.vue`.
- 参考后端: `SysUserOnlineController`,`SysUserOnlineServiceImpl`,Token/Redis 缓存服务.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/online.cljs`.
- 当前后端: `system/online.clj`,`domain/system/log.clj`,`resources/sql/log.sql`,`resources/migrations*/202406110013-create-sys-online.up.sql`.

参考项目业务:

- 检索区: 登录地址,用户名称.
- 表格: 会话编号,登录名称,部门名称,主机,登录地点,浏览器,操作系统,登录时间.
- 操作: 强退在线用户.
- 后端: 从 Redis token/session 缓存枚举在线用户,强退删除 token,使会话失效.

当前库差异和漏项:

- 前端基本覆盖查询和强退按钮.
- 当前 `sys_online` 是数据库记录式实现,不是从 token 缓存实时枚举,在线状态语义与 RuoYi 不同.
- 强退删除在线记录,但未见 JWT 黑名单/撤销机制,可能只是页面消失,原 token 仍可继续访问.
- 部门,地点,浏览器,OS 等字段完整性依赖登录记录,未达到 RuoYi token 缓存中的实时用户信息.
- 在线用户过期清理,分页,状态同步与 RuoYi 不等价.

### 12. 定时任务

源码定位:

- 参考前端: `monitor/job/index.vue`,`detail.vue`,`log.vue`,`components/Crontab`.
- 参考后端: `SysJobController`,`SysJobLogController`,`SysJobServiceImpl`,`SysJobLogServiceImpl`,`SysJobMapper`,`SysJobLogMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/job.cljs`.
- 当前后端: `web/controllers/job.clj`,`resources/sql/job.sql`,Quartz 集成代码.

参考项目业务:

- 检索区: 任务名称,任务组,任务状态.
- 表格: 多选,任务编号,任务名称,任务组,调用目标字符串,cron 表达式,状态,操作.
- 操作: 新增,修改,删除,导出,运行一次,状态切换,详情,调度日志.
- 弹窗: 任务名称,任务分组,调用目标,cron 表达式,cron 生成器,执行策略,是否并发,状态,备注.
- 后端: Quartz 调度;启动初始化任务;新增/更新/状态修改同步调度器;调用目标白名单/黑名单和 cron 合法性校验;运行一次写日志.

当前库差异和漏项:

- 后端有 Quartz/调度器集成,cron 校验,调用目标白名单,这是已覆盖重点.
- 前端缺少 RuoYi 的 cron 表达式生成器组件.
- 任务详情页和任务日志页没有按参考项目拆成独立页面;当前更多是抽屉/简化展示.
- 表单字段没有完整覆盖执行策略,是否并发,状态等字段的说明和校验.
- 后端删除主要单 ID,RuoYi 支持批量.
- `run-once` 固定或默认任务组处理可能不匹配实际 `job_group`.
- 任务日志删除单条/批量接口不完整,当前主要有列表与清空.
- 导出任务和任务日志未完整对齐 RuoYi Excel.

### 13. 代码生成

源码定位:

- 参考前端: `tool/gen/index.vue`,`editTable.vue`,`basicInfoForm.vue`,`genInfoForm.vue`,`importTable.vue`,`createTable.vue`.
- 参考后端: `GenController`,`GenTableServiceImpl`,`GenTableColumnServiceImpl`,`GenTableMapper`,`GenTableColumnMapper`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/gen.cljs`.
- 当前后端: `web/controllers/gen.clj`,`domain/gen.clj`,`resources/sql/gen.sql`.

参考项目业务:

- 已导入生成表列表,支持表名,表描述,创建时间查询.
- 支持数据库表导入,创建表,编辑生成配置,同步数据库,预览,生成到项目,下载,批量生成,删除配置.
- 编辑配置包含基本信息,生成信息,字段信息三部分.
- 持久化 `gen_table`,`gen_table_column`;每列配置插入/编辑/列表/查询/查询方式/必填/显示类型/字典类型.
- Velocity 生成 Java,Vue,XML,SQL,支持树表,主子表,生成路径和上级菜单.

当前库差异和漏项:

- 当前页面直接列数据库真实表,支持预览,下载,部署;没有 RuoYi 的“已导入生成表配置列表”.
- 当前迁移缺少 `gen_table`,`gen_table_column`,无法保存表和列的生成配置.
- 缺少数据库表导入,创建表,编辑生成配置,同步数据库,删除生成配置等标准流程.
- 当前生成器从元数据直接生成 Clojure/HugSQL/Reagent 模板,这是技术栈适配,不等价于 RuoYi-Vue 的完整代码生成业务.
- 字段显示类型,字典类型,查询方式,树表,主子表,上级菜单等配置未完整实现.
- 批量生成,生成到指定路径,Zip 下载字段结构与参考不完全一致.

### 14. 系统接口

源码定位:

- 参考前端: `tool/swagger/index.vue`.
- 参考后端: Swagger/Knife4j 配置,控制器注解.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/swagger.cljs`.
- 当前后端: `web/routes/*.clj` 路由 `:summary`,`web/controllers/common.clj`,以及 reitit/Swagger 相关配置.

参考项目业务:

- 前端通过 iframe 打开 Swagger UI.
- 后端基于 Springfox/Knife4j 配置接口文档,模型,认证,分组.

当前库差异和漏项:

- 前端有 `swagger.cljs`,iframe 指向 `/api/index.html`.
- 后端路由有 reitit `:summary`,但未确认完整 OpenAPI JSON,Swagger UI 静态资源,认证配置,分组和模型说明与 RuoYi 一致.
- 当前更像基础 reitit 文档入口,不等价于 RuoYi Knife4j 的完整体验.
- 若目标只是“能查看接口”,当前部分覆盖;若目标是 RuoYi 标准“系统接口”菜单,需补完整 UI,OpenAPI 分组和鉴权说明.

### 15. 服务监控

源码定位:

- 参考前端: `monitor/server/index.vue`.
- 参考后端: `ServerController`,`framework/web/domain/server/*`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/server.cljs`.
- 当前后端: `web/controllers/monitor.clj` 中 server 相关接口.

参考项目业务:

- 展示 CPU,内存,JVM,服务器信息,Java 参数,磁盘状态.
- CPU 展示核心数,用户使用率,系统使用率,当前空闲率.
- 内存/JVM 展示总量,已用,剩余,使用率,启动时间,运行时长,安装路径,项目路径.
- 磁盘展示盘符路径,文件系统,盘符类型,总大小,可用大小,已用大小,已用百分比.

当前库差异和漏项:

- 后端已提供 CPU,内存,JVM,系统,磁盘信息,前端也有展示页.
- 当前部分指标在无法获取时使用随机值或兼容兜底,不符合监控数据准确性要求.
- 前端字段,单位,阈值颜色,表格布局与 RuoYi 仍需逐项核对.
- JVM 输入参数,项目路径,运行时长,磁盘单位格式等信息可能缺失或与参考字段不一致.
- 监控异常时应返回明确不可用状态,而不是随机数据.

### 16. 缓存监控

源码定位:

- 参考前端: `monitor/cache/index.vue`,`monitor/cache/list.vue`.
- 参考后端: `CacheController`.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/cache.cljs`.
- 当前后端: `system/cache.clj`.

参考项目业务:

- 首页展示 Redis 基本信息,命令统计,内存信息.
- 列表页支持缓存名称,缓存键名,缓存内容查看.
- 操作支持清理缓存名,清理缓存键,清理全部.
- 标准缓存名包括 `sys_config`,`sys_dict`,`captcha_codes`,`repeat_submit`,`rate_limit`,`pwd_err_cnt` 等.

当前库差异和漏项:

- 前端首页有基本信息,命令统计,内存图;也有 names/keys/value 的事件和部分 UI,但与 RuoYi `list.vue` 的两级列表体验未完全一致.
- 后端使用模拟 atom 种子缓存,不是真实 Redis 或业务缓存.
- 清空后会重置为种子数据,不是真清空缓存.
- 缓存名称与 RuoYi 标准缓存空间不一致,不能反映验证码,参数,字典,限流,密码错误次数等真实缓存.
- Redis 指标如版本,运行模式,客户端,AOF/RDB,网络 IO 多数是兼容字段兜底.

### 17. 在线构建器

源码定位:

- 参考前端: `tool/build/index.vue`,`RightPanel.vue`,`DraggableItem.vue`,`CodeTypeDialog.vue`,`IconsDialog.vue`,`TreeNodeDialog.vue`.
- 参考后端: 主要是纯前端工具,无核心后台 CRUD.
- 当前前端: 未发现 `src/cljs/com/ruoyi/frontend/pages/form_builder.cljs`;当前 `gen.cljs` 占用了部分工具类入口.
- 当前后端: `system/form_template.clj`,`domain/system/form_template.clj`,`resources/migrations*/202406130005-create-sys-form-template.up.sql`.

参考项目业务:

- 拖拽式表单设计器,左侧组件库,中间画布,右侧属性面板.
- 支持输入框,文本域,数字,单选,多选,下拉,级联,日期/时间,上传,富文本等组件.
- 属性面板可配置表单,组件,正则,选项,栅格,标签宽度.
- 支持预览,导出 Vue 文件,复制 HTML/代码.

当前库差异和漏项:

- 当前未发现等价在线构建器前端页面,标准 `/tool/build` 未按 RuoYi 在线构建器接入.
- 当前 `sys_form_template` 后端属于项目自定义表单模板 CRUD,不能替代 RuoYi 纯前端拖拽构建器.
- 未实现左侧组件库,中间画布,右侧属性配置,拖拽排序,组件属性编辑.
- 未实现预览,导出 Vue,复制 HTML/代码等标准能力.
- AGENTS 文档中提到 `pages/form_builder.cljs`,但当前文件树未见该文件,文档与代码不一致.

### 18. 连接池监视

源码定位:

- 参考前端: `monitor/druid/index.vue`.
- 参考后端: `DruidConfig`,Druid StatViewServlet/WebStatFilter.
- 当前前端: `src/cljs/com/ruoyi/frontend/pages/datasource.cljs`.
- 当前后端: `web/controllers/monitor.clj` 中 datasource 相关接口,当前数据源/HikariCP 配置.

参考项目业务:

- iframe 打开 Druid StatView 页面.
- 提供数据源,SQL 监控,防火墙,Web URI,Session 等 Druid 控制台.
- 支持慢 SQL,SQL 执行统计,连接池状态和 Web 访问统计.

当前库差异和漏项:

- 当前展示 HikariCP/数据源基本状态,不是 Druid 控制台.
- 缺少 SQL 监控,慢 SQL,SQL 防火墙,Web URI,Session,Spring 监控等 Druid 页面.
- 前端不是 iframe 打开 Druid StatView,而是自定义数据源状态页.
- 若目标是“连接池状态监控”,当前部分覆盖;若目标是 RuoYi 标准“Druid 连接池监视”,当前差距较大.

## 优先修复建议

P0:

- 角色数据权限: 修正 `data_scope` 枚举语义,新增 `sys_role_dept`,保存/查询 `deptIds`,并在用户/角色/部门等列表中应用数据权限.
- 核心管理后台校验: 用户手机号/邮箱唯一,角色名/key 唯一,岗位名/code 唯一,部门同父级名称唯一,菜单同父级名称唯一.
- 删除前引用检查: 部门子节点/用户,岗位用户,角色用户,菜单子菜单,菜单角色引用,字典类型数据.
- admin/超级管理员保护: 用户,角色修改/删除/授权都需要统一保护.

P1:

- 菜单管理后端补角色菜单树,删除前引用检查,路由配置唯一校验,非管理员菜单过滤.
- 代码生成器按 RuoYi 业务补 `gen_table`,`gen_table_column` 和导入/编辑/同步/删除配置流程.
- 定时任务补 cron 生成器,详情页,任务日志删除/导出,批量操作和 `job_group` 正确触发.
- 缓存监控接入真实缓存命名空间,补缓存列表页.
- 登录日志补账户锁定/解锁语义.

P2:

- 所有标准页面补按钮级权限显隐,日期范围,排序,批量选择,Excel 导出.
- 通知公告补富文本编辑器和标准字段命名映射.
- 服务监控去掉随机兜底,无法采集时返回明确不可用状态.
- Swagger/OpenAPI 补完整 UI,JSON 和接口分组.
