(ns com.ruoyi.rouyi.frontend.pages.profile
  "个人中心页面。"
  (:require
    [reagent.core :as r]
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

(defn- profile-form []
  (let [user @(rf/subscribe [:auth/user])
        profile @(rf/subscribe [:profile/data])
        data (or profile user)
        form-data (r/atom (or data {}))
        show-pwd? (r/atom false)
        pwd-data (r/atom {:old_password "" :new_password ""})]
    (fn []
      [:div {:style {:maxWidth 600}}
       [:h3 "基本信息"]
       [:div {:style {:marginBottom 12}}
        [:label "昵称"]
        [antd/input {:value (:nick_name @form-data)
                     :onChange #(swap! form-data assoc :nick_name (-> % .-target .-value))}]]
       [:div {:style {:marginBottom 12}}
        [:label "邮箱"]
        [antd/input {:value (:email @form-data)
                     :onChange #(swap! form-data assoc :email (-> % .-target .-value))}]]
       [:div {:style {:marginBottom 12}}
        [:label "手机号"]
        [antd/input {:value (:phonenumber @form-data)
                     :onChange #(swap! form-data assoc :phonenumber (-> % .-target .-value))}]]
       [:div {:style {:marginBottom 12}}
        [:label "性别"]
        [antd/select {:value (or (:sex @form-data) "0")
                      :style {:width 200}
                      :onChange #(swap! form-data assoc :sex %)
                      :options #js [#js {:value "0" :label "男"}
                                    #js {:value "1" :label "女"}
                                    #js {:value "2" :label "未知"}]}]]
       [antd/space {:style {:marginTop 16}}
        [antd/button {:type "primary"
                      :onClick #(rf/dispatch [:profile/update @form-data])} "保存"]
        [antd/button {:onClick #(reset! show-pwd? true)} "修改密码"]]

       ;; Password change modal
       [antd/modal {:title "修改密码"
                    :open @show-pwd?
                    :onOk (fn []
                            (rf/dispatch [:profile/change-password @pwd-data])
                            (reset! show-pwd? false))
                    :onCancel #(reset! show-pwd? false)
                    :destroyOnClose true}
        [:div
         [:div {:style {:marginBottom 12}}
          [:label "旧密码"]
          [antd/password {:value (:old_password @pwd-data)
                          :onChange #(swap! pwd-data assoc :old_password (-> % .-target .-value))}]]
         [:div {:style {:marginBottom 12}}
          [:label "新密码"]
          [antd/password {:value (:new_password @pwd-data)
                          :onChange #(swap! pwd-data assoc :new_password (-> % .-target .-value))}]]]]])))

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
