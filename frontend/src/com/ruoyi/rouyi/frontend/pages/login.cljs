(ns com.ruoyi.rouyi.frontend.pages.login
  "登录页面。"
  (:require
    [reagent.core :as r]
    [re-frame.core :as rf]
    [com.ruoyi.rouyi.frontend.antd :as antd]))

(defn login-page []
  (let [loading? @(rf/subscribe [:auth/loading?])]
    (fn []
      [:div {:style {:display "flex" :justifyContent "center" :alignItems "center"
                     :height "100vh" :background "#f0f2f5"}}
       [antd/card {:title "若依管理系统" :style {:width 400}}
        [antd/form {:onFinish (fn [values]
                                (rf/dispatch [:auth/login (js->clj values :keywordize-keys true)]))}
         [antd/form-item {:name "username" :rules [{:required true :message "请输入用户名"}]}
          [antd/input {:placeholder "用户名" :prefix [antd/user-icon]}]]
         [antd/form-item {:name "password" :rules [{:required true :message "请输入密码"}]}
          [antd/password {:placeholder "密码" :prefix [antd/lock-icon]}]]
         [antd/form-item
          [antd/button {:type "primary" :htmlType "submit" :loading loading? :block true}
           "登录"]]]]])))
