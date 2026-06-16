(ns com.ruoyi.web.routes.business
  "工程方案与资源管理业务路由。"
  (:require
   [com.ruoyi.web.controllers.business.core :as biz]
   [com.ruoyi.web.middleware.auth :as auth-mw]))

(def PathId [:map [:id [:re #"\d+"]]])
(def TeamPath [:map [:id [:re #"\d+"]] [:team-id [:re #"\d+"]]])
(def SectionPath [:map [:id [:re #"\d+"]] [:section-key :string]])
(def ResourcePath [:map [:type :string]])
(def ResourceIdPath [:map [:type :string] [:id [:re #"\d+"]]])
(def GalleryPath [:map [:id [:re #"\d+"]]])
(def GalleryItemPath [:map [:id [:re #"\d+"]] [:item-id [:re #"\d+"]]])

(def PagingQuery [:map {:closed true}
                  [:page {:optional true} :int]
                  [:size {:optional true} :int]
                  [:status {:optional true} :string]
                  [:name {:optional true} :string]
                  [:code {:optional true} :string]
                  [:category {:optional true} :string]
                  [:project_name {:optional true} :string]
                  [:project_code {:optional true} :string]
                  [:solution_name {:optional true} :string]
                  [:solution_type {:optional true} :string]
                  [:solution_level {:optional true} :string]
                  [:engineering_name {:optional true} :string]
                  [:engineering_code {:optional true} :string]
                  [:engineering_id {:optional true} :int]
                  [:project_id {:optional true} :int]
                  [:construction_unit {:optional true} :string]
                  [:create_time {:optional true} :string]
                  [:engineering_industry {:optional true} :string]
                  [:engineering_nature {:optional true} :string]])

(def EngineeringBody [:map
                      [:engineering_name :string]
                      [:engineering_code {:optional true} :string]
                      [:description {:optional true} :string]
                      [:status {:optional true} :string]
                      [:remark {:optional true} :string]])

(def ProjectBody [:map
                  [:project_name :string]
                  [:engineering_id {:optional true} [:maybe :int]]
                  [:engineering_name {:optional true} :string]
                  [:project_code {:optional true} :string]
                  [:project_address {:optional true} :string]
                  [:construction_unit {:optional true} :string]
                  [:contractor_unit {:optional true} :string]
                  [:supervision_unit {:optional true} :string]
                  [:design_unit {:optional true} :string]
                  [:survey_unit {:optional true} :string]
                  [:project_manager {:optional true} :string]
                  [:project_leader {:optional true} :string]
                  [:contact_phone {:optional true} :string]
                  [:contract_period {:optional true} :string]
                  [:start_date {:optional true} [:maybe :string]]
                  [:end_date {:optional true} [:maybe :string]]
                  [:building_area {:optional true} :string]
                  [:project_cost {:optional true} :string]
                  [:structure_type {:optional true} :string]
                  [:building_floors {:optional true} :string]
                  [:project_overview {:optional true} :string]
                  [:extra_json {:optional true} :string]
                  [:remark {:optional true} :string]])

(def TeamBody [:map
               [:team_name :string]
               [:leader_name {:optional true} :string]
               [:contact_phone {:optional true} :string]
               [:work_scope {:optional true} :string]
               [:extra_json {:optional true} :string]
               [:remark {:optional true} :string]])

(def SolutionBody [:map
                   [:solution_name :string]
                   [:engineering_id {:optional true} [:maybe :int]]
                   [:project_id {:optional true} [:maybe :int]]
                   [:engineering_name {:optional true} :string]
                   [:project_name {:optional true} :string]
                   [:solution_type {:optional true} :string]
                   [:solution_level {:optional true} :string]
                   [:current_version {:optional true} :int]
                   [:recommend_scene {:optional true} :string]
                   [:status {:optional true} :string]
                   [:progress {:optional true} :int]
                   [:remark {:optional true} :string]])

(def SectionBody [:map
                  [:section_title {:optional true} :string]
                  [:content_json {:optional true} :string]
                  [:content {:optional true} :string]
                  [:sort_order {:optional true} :int]
                  [:remark {:optional true} :string]])

(def GenerationResourceBody [:map
                             [:schemeType {:optional true} :string]
                             [:scheme_type {:optional true} :string]
                             [:region {:optional true} :string]
                             [:chapterName {:optional true} :string]
                             [:chapter_name {:optional true} :string]
                             [:resourceTable {:optional true} :string]
                             [:resource_table {:optional true} :string]])

(def ResourceBody [:map
                   [:name :string]
                   [:code {:optional true} :string]
                   [:category {:optional true} :string]
                   [:publish_unit {:optional true} :string]
                   [:publish_date {:optional true} [:maybe :string]]
                   [:effective_date {:optional true} [:maybe :string]]
                   [:file_name {:optional true} :string]
                   [:file_type {:optional true} :string]
                   [:vector_status {:optional true} :string]
                   [:tags {:optional true} :string]
                   [:related_project_name {:optional true} :string]
                   [:structured_fields {:optional true} :string]
                   [:summary {:optional true} :string]
                   [:content {:optional true} :string]
                   [:status {:optional true} :string]
                   [:sort_order {:optional true} :int]
                   [:extra_json {:optional true} :string]
                   [:remark {:optional true} :string]])

(def AttachmentQuery [:map {:closed true}
                      [:biz_type {:optional true} :string]
                      [:biz_id {:optional true} :int]
                      [:section_key {:optional true} :string]
                      [:file_purpose {:optional true} :string]])

(def GalleryBody [:map
                  [:attachment_id :int]
                  [:image_title {:optional true} :string]
                  [:image_desc {:optional true} :string]
                  [:is_cover {:optional true} :string]
                  [:sort_order {:optional true} :int]
                  [:remark {:optional true} :string]])

(defn business-routes [{:keys [business-service]}]
  ["/business"
   {:middleware [(auth-mw/auth-middleware {:required? true})]
    :swagger {:tags ["工程方案与资源管理"]}}

   ["/engineering"
    ["" {:get {:summary "工程列表" :parameters {:query PagingQuery} :handler (partial biz/list-engineerings {:business-service business-service})}
         :post {:summary "新建工程" :parameters {:body EngineeringBody} :handler (partial biz/create-engineering {:business-service business-service})}}]
    ["/:id" {:get {:summary "工程详情" :parameters {:path PathId} :handler (partial biz/get-engineering {:business-service business-service})}
             :put {:summary "更新工程" :parameters {:path PathId :body EngineeringBody} :handler (partial biz/update-engineering {:business-service business-service})}
             :delete {:summary "逻辑删除工程" :parameters {:path PathId} :handler (partial biz/delete-engineering {:business-service business-service})}}]]

   ["/project"
    ["" {:get {:summary "项目列表" :parameters {:query PagingQuery} :handler (partial biz/list-projects {:business-service business-service})}
         :post {:summary "新建项目" :parameters {:body ProjectBody} :handler (partial biz/create-project {:business-service business-service})}}]
    ["/:id" {:get {:summary "项目详情" :parameters {:path PathId} :handler (partial biz/get-project {:business-service business-service})}
             :put {:summary "更新项目" :parameters {:path PathId :body ProjectBody} :handler (partial biz/update-project {:business-service business-service})}
             :delete {:summary "逻辑删除项目" :parameters {:path PathId} :handler (partial biz/delete-project {:business-service business-service})}}]
    ["/:id/subcontract-team"
     ["" {:get {:summary "项目分包队伍列表" :parameters {:path PathId} :handler (partial biz/list-subcontract-teams {:business-service business-service})}
          :post {:summary "新增项目分包队伍" :parameters {:path PathId :body TeamBody} :handler (partial biz/create-subcontract-team {:business-service business-service})}}]
     ["/:team-id" {:put {:summary "更新项目分包队伍" :parameters {:path TeamPath :body TeamBody} :handler (partial biz/update-subcontract-team {:business-service business-service})}
                   :delete {:summary "逻辑删除项目分包队伍" :parameters {:path TeamPath} :handler (partial biz/delete-subcontract-team {:business-service business-service})}}]]]

   ["/solution"
    ["" {:get {:summary "方案列表" :parameters {:query PagingQuery} :handler (partial biz/list-solutions {:business-service business-service})}
         :post {:summary "创建方案" :parameters {:body SolutionBody} :handler (partial biz/create-solution {:business-service business-service})}}]
    ["/generating" {:get {:summary "检查是否有生成中方案"
                          :handler (partial biz/check-generating {:business-service business-service})}}]
    ["/expire-check" {:post {:summary "检测并标记超时方案为失败"
                             :handler (partial biz/fail-expired-solutions {:business-service business-service})}}]
    ["/:id" {:get {:summary "方案详情" :parameters {:path PathId} :handler (partial biz/get-solution {:business-service business-service})}
             :put {:summary "更新方案" :parameters {:path PathId :body SolutionBody} :handler (partial biz/update-solution {:business-service business-service})}
             :delete {:summary "逻辑删除方案" :parameters {:path PathId} :handler (partial biz/delete-solution {:business-service business-service})}}]
    ["/:id/generate" {:post {:summary "触发方案AI生成"
                             :parameters {:path PathId}
                             :handler (partial biz/generate-solution {:business-service business-service})}}]
    ["/:id/generate-status" {:get {:summary "查询方案生成进度"
                                   :parameters {:path PathId}
                                   :handler (partial biz/get-generate-status {:business-service business-service})}}]
    ["/:id/sections/:section-key" {:get {:summary "方案章节详情" :parameters {:path SectionPath} :handler (partial biz/get-solution-section {:business-service business-service})}
                                   :put {:summary "保存方案章节" :parameters {:path SectionPath :body SectionBody} :handler (partial biz/save-solution-section {:business-service business-service})}}]
    ["/:id/chat/session" {:get {:summary "获取或创建AI对话会话"
                                :parameters {:path PathId}
                                :handler (partial biz/get-or-create-chat-session {:business-service business-service})}}]
    ["/:id/files" ["" {:get {:summary "查询方案文件版本" :parameters {:path PathId} :handler (partial biz/list-solution-files {:business-service business-service})}
                       :post {:summary "创建方案文件版本" :parameters {:path PathId} :handler (partial biz/create-solution-file {:business-service business-service})}}]
                 ["/:file-id/download" {:get {:summary "下载方案文件"
                                             :parameters {:path [:map [:id [:re #"\d+"]] [:file-id [:re #"\d+"]]]}
                                             :handler (partial biz/download-solution-file {:business-service business-service})}}]]
    ["/:id/export" {:get {:summary "导出方案HTML"
                          :parameters {:path PathId}
                          :handler (partial biz/export-solution-html {:business-service business-service})}}]
    ["/resource" {:post {:summary "获取生成资源"
                          :parameters {:body GenerationResourceBody}
                          :handler (partial biz/get-generation-resource {:business-service business-service})}}]
    ["/resource-chapter" {:post {:summary "获取章节生成资源"
                                  :parameters {:body GenerationResourceBody}
                                  :handler (partial biz/get-resource-chapter {:business-service business-service})}}]]

   ["/resource/match" {:get {:summary "根据方案类型/省份检索资源"
                             :handler (partial biz/find-resources-by-route {:business-service business-service})}}]

   ["/resource/:type"
    ["" {:get {:summary "资源列表" :parameters {:path ResourcePath :query PagingQuery} :handler (partial biz/list-resources {:business-service business-service})}
         :post {:summary "新增资源" :parameters {:path ResourcePath :body ResourceBody} :handler (partial biz/create-resource {:business-service business-service})}}]
    ["/:id" {:get {:summary "资源详情" :parameters {:path ResourceIdPath} :handler (partial biz/get-resource {:business-service business-service})}
             :put {:summary "更新资源" :parameters {:path ResourceIdPath :body ResourceBody} :handler (partial biz/update-resource {:business-service business-service})}
             :delete {:summary "逻辑删除资源" :parameters {:path ResourceIdPath} :handler (partial biz/delete-resource {:business-service business-service})}}]
    ["/:id/gallery"
     ["" {:get {:summary "图集图片列表" :parameters {:path GalleryPath} :handler (partial biz/list-gallery-items {:business-service business-service})}
          :post {:summary "新增图集图片" :parameters {:path GalleryPath :body GalleryBody} :handler (partial biz/create-gallery-item {:business-service business-service})}}]
     ["/:item-id" {:put {:summary "更新图集图片" :parameters {:path GalleryItemPath :body GalleryBody} :handler (partial biz/update-gallery-item {:business-service business-service})}
                   :delete {:summary "逻辑删除图集图片" :parameters {:path GalleryItemPath} :handler (partial biz/delete-gallery-item {:business-service business-service})}}]]]

   ["/attachments"
    ["" {:get {:summary "附件列表" :parameters {:query AttachmentQuery} :handler (partial biz/list-attachments {:business-service business-service})}
         :post {:summary "上传附件" :handler (partial biz/upload-attachment {:business-service business-service})}}]
    ["/:id" {:delete {:summary "逻辑删除附件" :parameters {:path PathId} :handler (partial biz/delete-attachment {:business-service business-service})}}]
    ["/:id/download" {:get {:summary "下载附件" :parameters {:path PathId} :handler (partial biz/download-attachment {:business-service business-service})}}]]

   ;; ========== 资源关联 ==========
   ["/resource/:type/:id/relations"
    ["" {:get {:summary "查询资源关联" :parameters {:path ResourceIdPath} :handler (partial biz/list-resource-relations {:business-service business-service})}
         :post {:summary "保存资源关联" :parameters {:path ResourceIdPath} :handler (partial biz/save-resource-relations {:business-service business-service})}}]]
   ;; ========== 知识库搜索 ==========
   ["/knowledge/search" {:get {:summary "全文搜索知识库"
                               :handler (partial biz/search-knowledge {:business-service business-service})}}]

   ;; ========== AI对话 ==========
   ["/chat/:session-id/messages"
    ["" {:get {:summary "获取对话消息列表"
               :parameters {:path [:map [:session-id [:re #"\d+"]]]}
               :handler (partial biz/list-chat-messages {:business-service business-service})}
         :post {:summary "保存对话消息"
                :parameters {:path [:map [:session-id [:re #"\d+"]]]}
                :handler (partial biz/save-chat-message {:business-service business-service})}}]]])
