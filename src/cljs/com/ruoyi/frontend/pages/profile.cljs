(ns com.ruoyi.frontend.pages.profile
  "个人中心页面。"
  (:require
    ["@ant-design/icons" :refer [ApartmentOutlined CalendarOutlined MailOutlined MobileOutlined TeamOutlined UserOutlined]]
    [com.ruoyi.frontend.antd :as antd]
    [re-frame.core :as rf]
    [reagent.core :as r]
    [reagent.hooks :as hooks]))


(def card-style
  {:border "1px solid #e4e7ed"
   :borderRadius 4
   :boxShadow "0 2px 12px rgba(0,0,0,0.06)"})


(defn- value-or
  [v fallback]
  (if (and v (not= "" v)) v fallback))


(defn- info-row
  [icon label value]
  [:div {:style {:height 44
                 :display "flex"
                 :alignItems "center"
                 :justifyContent "space-between"
                 :borderBottom "1px solid #ebeef5"
                 :fontSize 14
                 :color "#303133"}}
   [:span {:style {:display "inline-flex" :alignItems "center"}}
    icon
    [:span {:style {:marginLeft 4}} label]]
   [:span {:style {:color "#303133" :fontWeight 400 :textAlign "right"}}
    value]])


(defn- avatar-view
  [data]
  (let [avatar (or (:avatar data) "")
        nick (value-or (:nick_name data) "若")]
    [:div {:style {:textAlign "center" :padding "26px 32px 22px"}}
     [:input {:type "file"
              :accept "image/*"
              :style {:display "none"}
              :id "avatar-upload"
              :onChange (fn [e]
                          (when-let [file (-> e .-target .-files (aget 0))]
                            (let [form-data (js/FormData.)]
                              (.append form-data "avatarfile" file)
                              (rf/dispatch [:api/upload-avatar form-data]))))}]
     [:div {:style {:width 128
                    :height 128
                    :borderRadius "50%"
                    :margin "0 auto"
                    :overflow "hidden"
                    :background "linear-gradient(135deg,#f7d7c4,#9bc9ff)"
                    :display "flex"
                    :alignItems "center"
                    :justifyContent "center"
                    :color "#fff"
                    :fontSize 44
                    :fontWeight 700
                    :cursor "pointer"}
            :on-click #(.click (.getElementById js/document "avatar-upload"))}
      (if (not-empty avatar)
        [:img {:src avatar
               :style {:width "100%"
                       :height "100%"
                       :objectFit "cover"}}]
        [:span (subs nick 0 1)])]]))


(defn- profile-card
  [data]
  [antd/card {:title (r/as-element [:span {:style {:fontSize 18 :fontWeight 500}} "个人信息"])
              :styles {:body {:padding 0}}
              :style card-style}
   [avatar-view data]
   [:div {:style {:padding "0 32px 32px"}}
    [info-row [:> UserOutlined] "用户名称" (value-or (:user_name data) "admin")]
    [info-row [:> MobileOutlined] "手机号码" (value-or (:phonenumber data) "15888888888")]
    [info-row [:> MailOutlined] "用户邮箱" (value-or (:email data) "ry@163.com")]
    [info-row [:> ApartmentOutlined] "所属部门" (value-or (:dept_name data) "研发部门 / 董事长")]
    [info-row [:> TeamOutlined] "所属角色" (value-or (:role_name data) "超级管理员")]
    [info-row [:> CalendarOutlined] "创建日期" (value-or (:create_time data) "2026-01-18 10:58:15")]]])


(defn- basic-form
  [data]
  (let [[form] (antd/form-use-form)]
    (hooks/use-effect
      (fn []
        (when data
          (.setFieldsValue form (clj->js (merge {:sex "0"} data))))
        js/undefined)
      [data])
    [antd/form {:form form
                :layout "horizontal"
                :labelCol {:style {:width 110}}
                :wrapperCol {:flex 1}
                :preserve false
                :onFinish (fn [values]
                            (rf/dispatch [:profile/update (js->clj values :keywordize-keys true)]))
                :initialValues (clj->js (merge {:sex "0"} data))}
     [antd/form-item {:label "用户昵称"
                      :name "nick_name"
                      :rules [{:required true :message "请输入用户昵称"}]}
      [antd/input {:placeholder "请输入用户昵称" :style {:height 40}}]]
     [antd/form-item {:label "手机号码"
                      :name "phonenumber"
                      :rules [{:required true :message "请输入手机号码"}]}
      [antd/input {:placeholder "请输入手机号码" :style {:height 40}}]]
     [antd/form-item {:label "邮箱"
                      :name "email"
                      :rules [{:required true :message "请输入邮箱"}]}
      [antd/input {:placeholder "请输入邮箱" :style {:height 40}}]]
     [antd/form-item {:label "性别" :name "sex"}
      [antd/radio-group
       [antd/radio {:value "0"} "男"]
       [antd/radio {:value "1"} "女"]
       [antd/radio {:value "2"} "未知"]]]
     [antd/form-item {:wrapperCol {:offset 0}
                      :style {:marginLeft 110 :marginBottom 0}}
      [antd/space {:size 12}
       [antd/button {:type "primary"
                     :htmlType "submit"
                     :style {:width 64
                             :height 36
                             :background "#409eff"
                             :border "1px solid #409eff"}}
        "保存"]
       [antd/button {:danger true
                     :style {:width 64
                             :height 36
                             :background "#f56c6c"
                             :border "1px solid #f56c6c"
                             :color "#fff"}
                     :on-click #(.back js/history)}
        "关闭"]]]]))


(defn- password-form
  []
  (let [[form] (antd/form-use-form)]
    [antd/form {:form form
                :layout "horizontal"
                :labelCol {:style {:width 110}}
                :wrapperCol {:flex 1}
                :preserve false
                :onFinish (fn [values]
                            (let [params (select-keys (js->clj values :keywordize-keys true)
                                                      [:old_password :new_password])]
                              (rf/dispatch [:profile/change-password params])
                              (.resetFields form)))}
     [antd/form-item {:label "旧密码"
                      :name "old_password"
                      :rules [{:required true :message "请输入旧密码"}]}
      [antd/password {:placeholder "请输入旧密码" :style {:height 40}}]]
     [antd/form-item {:label "新密码"
                      :name "new_password"
                      :rules [{:required true :message "请输入新密码"}]}
      [antd/password {:placeholder "请输入新密码" :style {:height 40}}]]
     [antd/form-item {:label "确认密码"
                      :name "confirm_password"
                      :rules [{:required true :message "请再次输入新密码"}]}
      [antd/password {:placeholder "请再次输入新密码" :style {:height 40}}]]
     [antd/form-item {:style {:marginLeft 110 :marginBottom 0}}
      [antd/button {:type "primary"
                    :htmlType "submit"
                    :style {:width 64
                            :height 36
                            :background "#409eff"
                            :border "1px solid #409eff"}}
       "保存"]]]))


(defn- tabs-card
  [data]
  [antd/card {:title (r/as-element [:span {:style {:fontSize 18 :fontWeight 500}} "基本资料"])
              :styles {:body {:padding "30px 34px 44px"}}
              :style (merge card-style {:height 438})}
   [antd/tabs {:items [{:key "basic"
                        :label "基本资料"
                        :children (r/as-element [basic-form data])}
                       {:key "password"
                        :label "修改密码"
                        :children (r/as-element [password-form])}]}]])


(defn profile-page
  []
  (hooks/use-effect
    (fn []
      (rf/dispatch [:profile/fetch])
      js/undefined)
    [])
  (let [user @(rf/subscribe [:auth/user])
        profile @(rf/subscribe [:profile/data])
        data (or profile user {})]
    [:div {:style {:padding "22px"
                   :background "#fff"
                   :minHeight "calc(100vh - 132px)"}}
     [:div {:style {:display "grid"
                    :gridTemplateColumns "320px minmax(0, 1fr)"
                    :gap 22
                    :alignItems "start"}}
      [profile-card data]
      [tabs-card data]]]))
