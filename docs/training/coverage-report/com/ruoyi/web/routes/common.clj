✔ (ns com.ruoyi.web.routes.common
?   "通用路由。"
?   (:require
?    [com.ruoyi.web.controllers.common :as common]
?    [com.ruoyi.web.controllers.captcha :as captcha]))
  
✔ (defn common-routes [_opts]
✔   [["/captchaImage" {:get {:summary "生成验证码" :handler (partial captcha/captcha-image {})}}]
✔    ["/common/upload" {:post {:summary "通用文件上传" :handler (partial common/upload {})}}]
✔    ["/common/download" {:get {:summary "通用文件下载" :handler (partial common/download {})}}]
✔    ["/common/download/resource" {:get {:summary "下载资源文件" :handler (partial common/download-resource {})}}]])
