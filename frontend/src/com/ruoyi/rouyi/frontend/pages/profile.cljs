(ns com.ruoyi.rouyi.frontend.pages.profile
  "个人中心页面。"
  (:require
   [reagent.hooks :as hooks]
   [re-frame.core :as rf]
   [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn- avatar-section []
  (let [user @(rf/subscribe [:auth/user])
        profile @(rf/subscribe [:profile/data])
        data (or profile user)
        avatar (or (:avatar data) "")]
    [:div {:style {:marginBottom 24 :textAlign "center"}}
     [:h3 "头像"]
     [:div {:style {:width 120 :height 120 :borderRadius "50%"
                    :margin "0 auto 12px" :overflow "hidden"
                    :background "#f0f0f0" :display "flex"
                    :alignItems "center" :justifyContent "center"}}
      (if (not-empty avatar)
        [:img {:src avatar :style {:width "100%" :height "100%" :objectFit "cover"}}]
        [:span {:style {:fontSize 40 :color "#ccc"}} "?"])]
     [:div
      [:input {:type "file" :accept "image/*"
               :style {:display "none"}
               :id "avatar-upload"
               :onChange (fn [e]
                           (when-let [file (-> e .-target .-files (aget 0))]
                             (let [form-data (js/FormData.)]
                               (.append form-data "avatarfile" file)
                               (rf/dispatch [:api/upload-avatar form-data]))))}]
      [antd/button {:onClick #(.click (.getElementById js/document "avatar-upload"))}
       "上传头像"]]]))

(defn- password-modal [{:keys [visible? on-close]}]
  "修改密码弹窗 — 使用 antd Form。"
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when visible?
         (.resetFields form))
       js/undefined)
     [visible?])
    [antd/modal {:title "修改密码"
                 :open visible?
                 :onOk #(.submit form)
                 :onCancel on-close
                 :destroyOnClose true}
     [antd/form {:form form
                 :layout "vertical"
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:profile/change-password (js->clj values :keywordize-keys true)])
                             (on-close))
                 :initialValues #js {}}
      [antd/form-item {:label "旧密码" :name "old_password"
                       :rules [{:required true :message "请输入旧密码"}]}
       [antd/password {:placeholder "请输入旧密码"}]]
      [antd/form-item {:label "新密码" :name "new_password"
                       :rules [{:required true :message "请输入新密码"}]}
       [antd/password {:placeholder "请输入新密码"}]]]]))

(defn- profile-form []
  (let [user @(rf/subscribe [:auth/user])
        profile @(rf/subscribe [:profile/data])
        data (or profile user)
        [show-pwd? set-show-pwd!] (hooks/use-state false)
        [form] (antd/form-use-form)]
    (hooks/use-effect
     (fn []
       (when data
         (.setFieldsValue form (clj->js (merge {:sex "0"} data))))
       js/undefined)
     [data])
    [:div {:style {:maxWidth 600}}
     [:h3 "基本信息"]
     [antd/form {:form form
                 :layout "vertical"
                 :preserve false
                 :onFinish (fn [values]
                             (rf/dispatch [:profile/update (js->clj values :keywordize-keys true)]))
                 :initialValues (clj->js (merge {:sex "0"} data))}
      [antd/form-item {:label "昵称" :name "nick_name"}
       [antd/input {:placeholder "请输入昵称"}]]
      [antd/form-item {:label "邮箱" :name "email"}
       [antd/input {:placeholder "请输入邮箱"}]]
      [antd/form-item {:label "手机号" :name "phonenumber"}
       [antd/input {:placeholder "请输入手机号"}]]
      [antd/form-item {:label "性别" :name "sex"}
       [antd/select {:placeholder "请选择性别" :options #js [#js {:value "0" :label "男"}
                                                                 #js {:value "1" :label "女"}
                                                                 #js {:value "2" :label "未知"}]}]]
      [antd/form-item
       [antd/space
        [antd/button {:type "primary" :htmlType "submit"} "保存"]
        [antd/button {:onClick #(set-show-pwd! true)} "修改密码"]]]]
     [password-modal {:visible? show-pwd? :on-close #(set-show-pwd! false)}]]))

(defn profile-page []
  (hooks/use-effect
   (fn []
     (rf/dispatch [:profile/fetch])
     js/undefined)
   [])
  [:div
   [:h2 "个人中心"]
   [avatar-section]
   [profile-form]])
