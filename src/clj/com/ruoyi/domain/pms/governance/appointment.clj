(ns com.ruoyi.domain.pms.governance.appointment
  "A08 项目成员任命书: 由服务器按当前团队快照生成受控, 不可变且可再任命保留旧版的任命书."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [com.ruoyi.domain.pms.kernel :as k]
            [com.ruoyi.domain.pms.rules :as r])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.math BigInteger]))

(def appointment-code
  "同类任命书对象的稳定业务编码."
  "APPT")

(defn- sha256
  "计算文本的SHA-256十六进制摘要, 用于任命书快照的不可变校验."
  [^String text]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                          (.getBytes text StandardCharsets/UTF_8)))))

(defn- display-name
  "优先昵称, 其次登录名, 否则回退到用户ID标签, 保证快照中人员可读."
  [row fallback]
  (or (not-empty (:nick_name row)) (not-empty (:user_name row)) fallback))

(defn team-snapshot
  "读取当前项目成员表生成按用户ID升序的团队快照; 成员表即任命依据, 经理与创建者均已登记其中."
  [q project]
  (->> (q :pms/members {:project_id (:project_id project)})
       (mapv (fn [m] {:user_id (:user_id m)
                      :name (display-name m (str "用户" (:user_id m)))
                      :role (:role m)}))
       (sort-by :user_id)
       (vec)))

(defn- canonical-snapshot
  "以固定键序序列化团队快照, 使内容摘要可稳定复算."
  [snapshot]
  (json/generate-string (mapv #(array-map :user_id (:user_id %) :name (:name %) :role (:role %)) snapshot)))

(defn- render-content
  "根据项目, 团队快照和签发信息渲染任命书正文; 正文完全由快照派生."
  [project snapshot issued-on issued-name note]
  (str "项目成员任命书\n\n"
       "项目编号: " (:project_no project) "\n"
       "项目名称: " (:name project) "\n"
       "任命生效日期: " issued-on "\n"
       "签发人: " issued-name "\n"
       (when (not-empty note) (str "备注: " note "\n"))
       "\n经确认, 本项目现任团队如下, 任命内容与本文件生成时的团队快照一致:\n"
       (str/join "\n" (for [p snapshot]
                        (str "- " (:name p) " (用户ID " (:user_id p) ") 担任 " (:role p))))
       "\n"))

(defn- decode
  "把任命书数据库行转换为平铺只读对象, 解析团队快照."
  [row]
  (when row
    {:id (:appointment_id row) :project_id (:project_id row) :code (:code row)
     :revision (:revision row) :status (:status row) :issued_by (:issued_by row)
     :issued_on (:issued_on row) :note (:note row)
     :snapshot (json/parse-string (:snapshot row) true)
     :snapshot_sha256 (:snapshot_sha256 row) :content (:content row)
     :headcount (:headcount row) :created_at (:created_at row)}))

(defn list-summaries
  "返回项目内任命书的全部不可变版本, 省略正文但保留团队快照与摘要用于列表展示."
  [q project]
  (mapv #(dissoc % :content) (mapv decode (q :appt/list {:project_id (:project_id project)}))))

(defn content
  "读取已授权项目中确定任命书版本的完整正文与团队快照."
  [svc actor id rid]
  (k/read! svc actor id "pms:project:query"
    (fn [q project]
      (when-not (string? rid) (r/fail! 400 "任命书ID必须为字符串"))
      (let [row (q :appt/record {:project_id (:project_id project) :appointment_id rid})]
        (when-not row (r/fail! 404 "任命书不存在或不属于本项目"))
        (decode row)))))

(defn issue!
  "生成不可变任命书新版本; 团队快照由服务器读取当前经理与成员得到, 客户端不得伪造内容."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "appointment.issued"
    (fn [q project]
      (r/object! body [:issued_on :note :version])
      (let [issued-on (r/date! (r/text! (:issued_on body) "任命生效日期" 10 true) "任命生效日期")
            note (r/text! (:note body) "备注" 500 false)
            snapshot (team-snapshot q project)
            _ (when (empty? snapshot) (r/fail! 409 "项目团队为空, 无法生成任命书"))
            canonical (canonical-snapshot snapshot)
            sha (sha256 canonical)
            issued-name (or (not-empty (:nick_name actor)) (not-empty (:user_name actor)) (str "用户" (:user_id actor)))
            content (render-content project snapshot issued-on issued-name note)
            revision (inc (or (:revision (q :appt/latest {:project_id (:project_id project) :code appointment-code})) 0))
            appointment-id (k/id)]
        (q :appt/insert! {:appointment_id appointment-id :project_id (:project_id project)
                          :code appointment-code :revision revision :issued_by (:user_id actor)
                          :issued_on issued-on :note note :snapshot canonical
                          :snapshot_sha256 sha :content content :headcount (count snapshot)})
        (decode (q :appt/record {:project_id (:project_id project) :appointment_id appointment-id}))))))
