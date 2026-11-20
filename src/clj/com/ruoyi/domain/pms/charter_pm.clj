(ns com.ruoyi.domain.pms.charter-pm
  "H01 章程授权项目经理的权限联动.
   章程批准时冻结的 authorized_pm_id 此前只是一个展示字段, 与项目实际的编辑权限毫无关系.
   本命名空间把它变成真实的授权边界: 已批准章程的授权 PM 获得该项目编辑范围 (与 manager_id 同级,
   但仅在章程已批准后生效); 章程批准时由 approval 侧把通知投递到该 PM 的本地收件箱.
   独立成叶节点 (只依赖 cheshire) 供 rules/access! 查询: 不能 require rules, 否则与
   rules -> finance_money -> ... -> styles 形成环."
  (:require
    [cheshire.core :as json]))


(defn- payload
  [row]
  (let [p (:payload row)]
    (cond (map? p) p (string? p) (try (json/parse-string p true) (catch Exception _ {})) :else {})))


(defn authorized-pm
  "已批准章程 (取最高 revision) 中冻结的 authorized_pm_id, 无已批准章程或未指定时 nil."
  [q project]
  (let [rows (q :gov/approved-charter-pm {:project_id (:project_id project)})]
    (some (fn [row]
            (let [uid (get (payload row) :authorized_pm_id)]
              (when (integer? uid) uid)))
          rows)))


(defn authorized-pm?
  "当前用户是否为该项目已批准章程指定的授权项目经理."
  [q project user-id]
  (and (some? user-id) (= user-id (authorized-pm q project))))
