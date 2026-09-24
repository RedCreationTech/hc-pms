(ns com.ruoyi.web.routes.common
  "通用路由. 文件上传与下载需要登录."
  (:require
    [com.ruoyi.web.controllers.captcha :as captcha]
    [com.ruoyi.web.controllers.common :as common]
    [com.ruoyi.web.middleware.auth :as auth-mw]))


(defn common-routes
  [_opts]
  [["/captchaImage" {:get {:summary "生成验证码" :handler (partial captcha/captcha-image {})}}]
   ["/common" {:middleware [(auth-mw/auth-middleware {:required? true})]}
    ["/upload" {:post {:summary "通用文件上传" :handler (partial common/upload {})}}]
    ["/download" {:get {:summary "通用文件下载" :handler (partial common/download {})}}]
    ["/download/resource" {:get {:summary "下载资源文件" :handler (partial common/download-resource {})}}]]])
