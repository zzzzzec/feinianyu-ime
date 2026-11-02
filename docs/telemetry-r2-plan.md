# 遥测与 R2 接入方案（0.3.4）

## 目标和现状

设置入口统一为“遥测”，承载现有性能记录、新增输入样本和云端同步。0.3.4 已实现本地采集、地址编辑、设备配对、加密凭据和持久化后台同步。通过用户指定的 Doppler Cloudflare token 已创建私有 R2 桶与 Worker，无需浏览器 OAuth。接收端代码位于 server/telemetry，9 项服务端测试、6 项脚本测试和 7 项 Android 验证通过；验证期间仅使用合成内容。

## 首版架构

手机事件先进入独立的本地 SQLite。后台将已经持久化的记录封成不可修改的 JSONL gzip 批次，使用 HTTPS 发送给 Worker。Worker 校验设备身份、批次 ID、大小和 SHA-256，然后通过 R2 binding 写入私有桶，返回持久化回执。手机核对回执后更新上传进度；网络故障和进程重启不会推进未确认的进度。

已建桶名 feinianyu-ime-telemetry，Standard 类别，apac 位置提示，r2.dev 公共访问关闭且无自定义域名；已部署 Worker 名 feinianyu-ime-telemetry-ingest。首版使用 workers.dev 入口，不修改现有域名或服务。

上传抽象为 TelemetrySink，R2 由服务端适配，手机保存服务地址和设备凭证。R2 管理凭据不进入 APK、公开仓库或设备配置。Worker 通过绑定访问桶；初期少量设备的上传凭证哈希和前缀授权由 Worker secret 管理，可单独撤销。设备凭证经一次配对配置，手机私有存储由 Android Keystore 保护。配对输入框采用密码类型及禁止个性化学习标记，避免采集自身凭证。

设备凭证仅允许上传其自己的目录；查询、下载数据和管理权限使用独立的所有者认证。Worker 普通日志仅输出状态和计数，不输出正文、鉴权头或凭证。

当前账号启用了 Default-Deny，因此为新 Worker 的精确主机名建立了独立 Access Service Auth 应用，只允许登记的专用服务令牌。账号默认拒绝和其他应用不变。专用 Access 凭据有效期一年，存于 Doppler，客户端通过配对获得 Access Client ID/Secret 及独立设备令牌；按设备授权和撤销，管理 token 与上传凭据均不编入 APK。只有显式登记的设备可以上传。

## 遥测设置

| 设置 | 首版建议 |
| --- | --- |
| 性能记录 | 沿用现有本地记录和导出 |
| 输入与候选采集 | 默认关闭，开启后从当前开始记录 |
| 不采集的应用 | 独立应用排除列表 |
| 云端同步 | 默认关闭，配对并显式开启后上传 |
| 同步条件 | 默认仅不计费网络，可选仅充电时 |
| 同步状态 | 待上传批次及大小、最近成功时间、失败原因 |
| 操作 | 立即同步、导出、清空本地 |

清空本地和删除云端是不同操作，界面名称明确区分。云端删除需要所有者身份。密码和禁止个性化学习字段维持禁采。

## 批次协议

- 每批压缩后上限建议 1 MiB；按完整事件边界封包，避免逐键联网。
- 元数据包含 schema、随机 installation_id、数据库 generation、batch_id、首尾持久化序号、事件数量、字节数、SHA-256 和版本。批次文件封存后重试仍发送相同字节。
- Worker 从授权信息确定设备目录，不接受客户端指定任意桶或路径。对象布局 v1/events/<device-id>/<generation>/<batch-id>.jsonl.gz，避免重试跨日时出现两个对象；日期保留在事件中。性能与派生评测集使用独立前缀。
- 使用条件写入防止覆盖；批次已存在且哈希一致则返回已有回执，哈希不同则报冲突。R2 put 的 sha256 用于验证上传完整性。回执表示字节已保存，不表示样本内容或标签已经正确。
- 手机仅在回执匹配批次及校验值时标记已同步。数据集拼接时按 installation_id、generation、record_id 去重与排序，不依赖跨设备时钟。
- 后台沿用项目的 Android JobScheduler，使用不计费网络、充电和低电量约束及指数退避，周期最短 15 分钟，实际运行由系统决定。新增记录或点击立即同步可安排合并后的一次性任务，遵守所选网络条件。
- 本地未确认的数据不因上传失败而删除。达到本地上限时暂停并提示。云端首版先保留原始数据，待评测集归档流程稳定后再配置生命周期；已同步本地数据可采用单独的保留策略。

## 存储与评测的边界

R2 保存批次文件；本地 SQLite 管理输入事件与待传进度。离线脚本下载、校验、去重后构建版本化回放集，按日期和会话划分训练/测试。拼音转换、候选重排和 Qwen/词典续写分别评测；自然选词标签保留 observed_behavior 标记。云存储不会自动把行为数据变成人工正确答案。

## 费用估计

官方 Standard 免费额度为 10 GB-month 存储、每月 100 万次 Class A 和 1000 万次 Class B 操作，直接 R2 出网免费。Standard 超额存储为 $0.015/GB-month；Worker 独立计费，账号中的其他服务也会消耗共享额度。

仅作容量示例：压缩后每天 10 MB，30 天新增约 300 MB，按每批 1 MB 约 300 次写入。实际压缩比、批次数和年度累计存储需要用样本测量。首版选 Standard，不启用 Infrequent Access。

## 验收顺序

1. 登录并核对 Cloudflare 账号和 R2 状态，创建私有桶与最小 Worker。
2. 用人工构造的无个人内容批次验证鉴权、大小限制、重复提交、哈希冲突、回执丢失和撤销设备。
3. 接入遥测页和后台队列，验证离线、断网、重启、升级、暂停和禁采。
4. 从云端下载测试批次，校验事件数量与原始字节，再构建首份行为回放集。

云端基础设施与合成数据链路已完成：首次写入、重复/并发重试、内容与元数据冲突、错误 SHA-256、大小上限、禁止下载、真实 R2 原字节读回和临时设备撤销均通过。Android 的采集、配对、加密、重试、地址编辑和进程重启恢复也已通过模拟器验证。真机后台调度与厂商省电策略仍需实际使用检验。

## 官方资料

- https://developers.cloudflare.com/r2/api/workers/workers-api-usage/
- https://developers.cloudflare.com/r2/api/workers/workers-api-reference/
- https://developers.cloudflare.com/r2/api/s3/presigned-urls/
- https://developers.cloudflare.com/r2/reference/consistency/
- https://developers.cloudflare.com/r2/buckets/object-lifecycles/
- https://developers.cloudflare.com/r2/pricing/
- https://developers.cloudflare.com/workers/platform/pricing/
- https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
