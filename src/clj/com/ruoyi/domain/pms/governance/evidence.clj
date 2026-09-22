(ns com.ruoyi.domain.pms.governance.evidence
  "需求与真实文本附件的不可变版本,CSV预检和双向证据关联."
  (:require
    [clojure.data.csv :as csv]
    [clojure.string :as str]
    [com.ruoyi.domain.pms.governance.store :as s]
    [com.ruoyi.domain.pms.kernel :as k]
    [com.ruoyi.domain.pms.rules :as r])
  (:import
    (java.math
      BigInteger)
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)))


(def requirement-fields
  "需求条目的明确字段."
  [:code :text :category :priority :owner_id])


(defn requirement!
  "校验需求编号,内容,分类,必要性和责任人."
  [q project body]
  (s/input! body requirement-fields)
  {:code (s/text! body :code 100) :text (s/text! body :text 10000)
   :category (s/text! body :category 100)
   :priority (s/enum! (:priority body) #{"required" "desired"} "priority")
   :owner_id (k/user! q project (:owner_id body) "需求负责人")})


(def document-classifications
  "文档密级取值, 仅用于归集与追踪, 不替代项目授权."
  #{"public" "internal" "confidential"})


(defn document!
  "校验真实文本附件并由服务器计算字节数和SHA256; 密级/阶段/结构节点用于归集追踪, 密级缺省为内部."
  [_ _ body]
  (s/input! body [:code :title :filename :content :classification :stage :structure_node])
  (let [filename (s/text! body :filename 150)
        content (:content body)
        _ (when-not (and (string? content) (not (str/blank? content)))
            (r/fail! 400 "content必须为非空文本"))
        bytes (.getBytes ^String content StandardCharsets/UTF_8)]
    (when (or (re-find #"[/\\\r\n]" filename) (> (alength bytes) 1048576))
      (r/fail! 400 "文件名非法或文本附件超过1MiB"))
    {:code (s/text! body :code 100) :title (s/text! body :title 200)
     :filename filename :content content :byte_size (alength bytes)
     :classification (if (contains? body :classification)
                       (s/enum! (:classification body) document-classifications "classification")
                       "internal")
     :stage (s/optional-text! body :stage 100)
     :structure_node (s/optional-text! body :structure_node 100)
     :content_type "text/plain; charset=utf-8"
     :sha256 (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256") bytes)))}))


(defn create!
  "创建经过明确字段校验的需求或文档首版."
  [svc actor id kind body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".created")
             (fn [q project]
               (s/insert! q project actor kind ((if (= kind "requirement") requirement! document!) q project body)
                          {:status "registered"}))))


(defn revise!
  "新增不可变修订,保持原编号与已引用版本不变."
  [svc actor id kind rid body]
  (k/mutate! svc actor id "pms:project:edit" body (str kind ".revised")
             (fn [q project]
               (let [old (s/latest! q project (s/record! q project kind rid))
                     fields ((if (= kind "requirement") requirement! document!) q project body)]
                 (when-not (= (:code old) (:code fields)) (r/fail! 400 "修订不得改变业务编号"))
                 (s/insert! q project actor kind (assoc fields :previous_id rid)
                            {:revision (inc (:revision old)) :status "registered"})))))


(defn content
  "读取已授权项目中的确切文件版本内容."
  [svc actor id rid]
  (k/read! svc actor id "pms:project:query"
           (fn [q project] (s/record! q project "document" rid))))


(defn batch-content
  "读取同一项目内多个确定文档版本的正文, 供打包批量下载; 任一引用非法则整体失败, 不泄露跨项目对象."
  [svc actor id body]
  (r/object! body [:record_ids])
  (k/read! svc actor id "pms:project:query"
           (fn [q project]
             (let [ids (:record_ids body)]
               (when-not (and (vector? ids) (<= 1 (count ids) 50) (= (count ids) (count (set ids))))
                 (r/fail! 400 "批量下载须为1到50个不重复的文档版本ID"))
               {:documents (mapv #(select-keys (s/record! q project "document" %)
                                               [:id :code :revision :filename :content_type :content :sha256 :byte_size
                                                :classification :stage :structure_node])
                                 ids)}))))


(defn- csv-rows!
  "解析限定大小的CSV并校验列名和列宽."
  [text]
  (let [text (r/text! text "csv" 1048576 true)
        _ (when (> (alength (.getBytes ^String text StandardCharsets/UTF_8)) 1048576)
            (r/fail! 400 "CSV超过1MiB"))
        rows (try (doall (csv/read-csv text))
                  (catch Exception _ (r/fail! 400 "CSV格式不合法")))
        header (mapv keyword (first rows))]
    (when-not (= header requirement-fields)
      (r/fail! 400 "CSV表头必须为code,text,category,priority,owner_id"))
    (when-not (<= 1 (count (rest rows)) 500) (r/fail! 400 "CSV必须包含1到500条需求"))
    (mapv (fn [line cells]
            {:line line :cells cells :body (when (= 5 (count cells)) (zipmap requirement-fields cells))})
          (range 2 (+ 2 (count (rest rows)))) (rest rows))))


(defn preflight
  "逐行预检并返回全部错误,不产生任何业务写入."
  [q project text]
  (let [seen (atom (set (map :code (s/records q project "requirement"))))
        checked (mapv (fn [{:keys [line body]}]
                        (try
                          (when-not body (r/fail! 400 "列数必须为5"))
                          (let [row (requirement! q project body)]
                            (when (@seen (:code row)) (r/fail! 409 "需求编号已存在或在文件内重复"))
                            (swap! seen conj (:code row))
                            {:line line :row row})
                          (catch clojure.lang.ExceptionInfo e
                            (if (:pms-error (ex-data e))
                              {:line line :error (.getMessage e)} (throw e))))) (csv-rows! text))
        errors (filterv :error checked)]
    {:valid? (empty? errors) :count (count checked) :errors errors
     :rows (mapv :row (remove :error checked))}))


(defn preview
  "对当前项目预检CSV,请求不需要项目写版本."
  [svc actor id body]
  (r/object! body [:csv])
  (k/read! svc actor id "pms:project:query"
           (fn [q project] (preflight q project (:csv body)))))


(defn import!
  "预检通过后整批导入需求,任一错误导致全部不写入."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "requirement.imported"
             (fn [q project]
               (s/input! body [:csv])
               (let [checked (preflight q project (:csv body))]
                 (when-not (:valid? checked)
                   (r/fail! 400 (str "CSV预检失败: " (pr-str (:errors checked)))))
                 {:rows (mapv #(s/insert! q project actor "requirement" % {:status "registered"}) (:rows checked))
                  :count (:count checked)}))))


(defn trace!
  "将确定需求版本关联到同项目文档版本或真实WBS任务."
  [svc actor id body]
  (k/mutate! svc actor id "pms:project:edit" body "trace.created"
             (fn [q project]
               (s/input! body [:requirement_id :target_kind :target_id :relation])
               (let [req (s/record! q project "requirement" (:requirement_id body))
                     kind (s/enum! (:target_kind body) #{"document" "task"} "target_kind")
                     target (s/text! body :target_id 36)
                     relation (s/enum! (:relation body) #{"satisfies" "verifies"} "relation")]
                 (if (= kind "document") (s/record! q project "document" target)
                     (when-not (q :planning/task {:project_id (:project_id project) :task_id target})
                       (r/fail! 404 "任务不存在或不属于本项目")))
                 (s/insert! q project actor "trace"
                            {:code (str (:id req) ":" kind ":" target ":" relation)
                             :requirement_id (:id req) :target_kind kind :target_id target :relation relation}
                            {:status "registered"})))))
