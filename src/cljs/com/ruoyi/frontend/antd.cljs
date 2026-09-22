(ns com.ruoyi.frontend.antd
  "Ant Design 组件 Reagent 封装."
  (:require
    ["@ant-design/icons" :refer [LockOutlined UserOutlined DashboardOutlined TeamOutlined SettingOutlined SafetyOutlined FileTextOutlined EditOutlined DeleteOutlined PlusOutlined DownloadOutlined EyeOutlined SearchOutlined ReloadOutlined UploadOutlined MoreOutlined]]
    ["antd" :refer [Alert App Button Card Cascader Checkbox ConfigProvider DatePicker Descriptions Divider Drawer Dropdown Empty Form Input InputNumber Layout Menu Modal Pagination Popconfirm Popover Progress Radio Rate Select Slider Space Spin Statistic Switch Table Tabs Tag TimePicker Timeline Tooltip Tree TreeSelect Upload message Row Col]]
    [reagent.core :as r]))


(def app (r/adapt-react-class App))
(def button (r/adapt-react-class Button))
(def card (r/adapt-react-class Card))
(def checkbox (r/adapt-react-class Checkbox))
(def checkbox-group (r/adapt-react-class (.-Group Checkbox)))
(def rate (r/adapt-react-class Rate))
(def time-picker (r/adapt-react-class TimePicker))
(def timeline (r/adapt-react-class Timeline))
(def statistic (r/adapt-react-class Statistic))
(def row (r/adapt-react-class Row))
(def col (r/adapt-react-class Col))
(def date-picker (r/adapt-react-class DatePicker))
(def divider (r/adapt-react-class Divider))
(def descriptions (r/adapt-react-class Descriptions))
(def descriptions-item (r/adapt-react-class (.-Item Descriptions)))
(def drawer (r/adapt-react-class Drawer))
(def empty-component (r/adapt-react-class Empty))
(def dropdown (r/adapt-react-class Dropdown))
(def form (r/adapt-react-class Form))
(def form-item (r/adapt-react-class (.-Item Form)))
(def form-use-form (.-useForm Form))
(def input (r/adapt-react-class Input))
(def input-number (r/adapt-react-class InputNumber))
(def password (r/adapt-react-class (.-Password Input)))
(def text-area (r/adapt-react-class (.-TextArea Input)))
(def layout (r/adapt-react-class Layout))
(def layout-header (r/adapt-react-class (.-Header Layout)))
(def layout-sider (r/adapt-react-class (.-Sider Layout)))
(def layout-content (r/adapt-react-class (.-Content Layout)))
(def menu (r/adapt-react-class Menu))
(def menu-item (r/adapt-react-class (.-Item Menu)))
(def sub-menu (r/adapt-react-class (.-SubMenu Menu)))
(def modal (r/adapt-react-class Modal))
(def pagination (r/adapt-react-class Pagination))
(def popconfirm (r/adapt-react-class Popconfirm))
(def popover (r/adapt-react-class Popover))
(def progress (r/adapt-react-class Progress))
(def radio (r/adapt-react-class Radio))
(def radio-group (r/adapt-react-class (.-Group Radio)))
(def select (r/adapt-react-class Select))
(def select-option (r/adapt-react-class (.-Option Select)))
(def space (r/adapt-react-class Space))
(def spin (r/adapt-react-class Spin))
(def switch (r/adapt-react-class Switch))
(def table (r/adapt-react-class Table))
(def tag (r/adapt-react-class Tag))
(def alert (r/adapt-react-class Alert))
(def tree (r/adapt-react-class Tree))
(def slider (r/adapt-react-class Slider))
(def cascader (r/adapt-react-class Cascader))
(def upload (r/adapt-react-class Upload))
(def tabs (r/adapt-react-class Tabs))
(def tooltip (r/adapt-react-class Tooltip))
(def tree-select (r/adapt-react-class TreeSelect))

(defonce message-api (atom nil))


(defn modal-confirm!
  "确认对话框.on-ok 为确认回调."
  [on-ok & [opts]]
  (.confirm Modal
            (clj->js (merge {:title "确认操作" :content "确定执行该操作吗？"
                             :okText "确定" :cancelText "取消" :onOk on-ok}
                            opts))))


(defn use-app-message
  []
  "在 App 组件内部调用,获取 message 实例."
  (let [api (.useApp App)]
    (reset! message-api (.-message api))))


(defn success!
  [text]
  (if-let [api @message-api]
    (.success api text)
    (.success message text)))


(defn error!
  [text]
  (if-let [api @message-api]
    (.error api text)
    (.error message text)))


(defn warning!
  [text]
  (if-let [api @message-api]
    (.warning api text)
    (.warning message text)))


(def user-icon (r/adapt-react-class UserOutlined))
(def lock-icon (r/adapt-react-class LockOutlined))
(def dashboard-icon (r/adapt-react-class DashboardOutlined))
(def team-icon (r/adapt-react-class TeamOutlined))
(def setting-icon (r/adapt-react-class SettingOutlined))
(def safety-icon (r/adapt-react-class SafetyOutlined))
(def file-text-icon (r/adapt-react-class FileTextOutlined))
(def edit-icon (r/adapt-react-class EditOutlined))
(def delete-icon (r/adapt-react-class DeleteOutlined))
(def plus-icon (r/adapt-react-class PlusOutlined))
(def download-icon (r/adapt-react-class DownloadOutlined))
(def eye-icon (r/adapt-react-class EyeOutlined))
(def search-icon (r/adapt-react-class SearchOutlined))
(def reload-icon (r/adapt-react-class ReloadOutlined))
(def upload-icon (r/adapt-react-class UploadOutlined))
(def more-icon (r/adapt-react-class MoreOutlined))
