# 飞念语遥测接收端

状态：私有 R2 桶和 Worker 已创建；9 项服务端测试、Wrangler dry-run 和真实云端合成数据验证通过。0.3.4 已接入 Android 遥测页面、加密配对及持久化同步队列，7 项 Android 验证通过，包括真实输入、进程重启、地址编辑和模拟器到 R2 的上传。

此目录独立于 Android 构建。Worker 只提供健康检查和经过设备鉴权的批次上传，不提供对象查询、下载或删除接口。存储桶使用私有 R2 binding。当前部署环境同时使用 Cloudflare Access Service Auth，客户端需要专用 Access 服务凭据和独立的设备上传令牌。

## 验证

```powershell
node --test server/telemetry/test/worker.test.mjs
wrangler deploy --dry-run --config server/telemetry/wrangler.toml
```

测试使用合成内容和内存存储适配器；dry-run 验证 Worker 打包及绑定配置，不代表云端已经部署。

## 首次部署

以下为新环境的部署参考。当前环境已经创建，不要重复建桶或覆盖设备授权。在已授权的 Cloudflare 账号中，先核对同名资源，再执行创建与部署，避免覆盖现有服务。R2 未开通时需先完成官方开通流程。

```powershell
wrangler whoami
wrangler r2 bucket list
wrangler r2 bucket create feinianyu-ime-telemetry --storage-class Standard --location apac
wrangler r2 bucket dev-url get feinianyu-ime-telemetry
wrangler r2 bucket domain list feinianyu-ime-telemetry
wrangler deploy --config server/telemetry/wrangler.toml
```

不要启用 r2.dev 或绑定桶的公共域名。多个账号时使用进程环境变量 CLOUDFLARE_ACCOUNT_ID 指定核对后的账号。

部署后未设置 `DEVICE_UPLOAD_KEYS` 时，所有上传返回 503，保持拒绝状态。设备配对时通过 `wrangler secret put DEVICE_UPLOAD_KEYS --config server/telemetry/wrangler.toml` 的标准输入设置 JSON 数组，每项为：

```json
[{"device_id":"<random UUID>","token_sha256":"<64 lowercase hex characters>","enabled":true}]
```

设备令牌须用加密安全随机数生成，至少 256 bit，使用无填充 base64url 表示。服务端只保存令牌 SHA-256；明文只用于设备配对，不进入源码、命令行参数或输出日志。撤销设备时将其 enabled 设为 false 并重新写入 secret。

## Cloudflare Access

当前账号对没有对应 Access 应用的服务实行 Default-Deny。此次只为新 Worker 的精确主机名建立独立的 self_hosted Access 应用，使用 non_identity（Service Auth）策略，限定到新建的专用服务令牌。未修改账号默认拒绝规则和其他应用。

专用服务令牌有效期一年，新凭据通过标准输入保存到 Doppler。手机配对时需支持 `CF-Access-Client-Id`、`CF-Access-Client-Secret`，并继续携带 Worker 自身的设备 Bearer 令牌。Access 凭据本身不能下载 R2 对象，也不能绕过 Worker 设备鉴权。实际设备配对时应分配可单独撤销的凭据，不将任何上传凭据或管理 token 编入 APK。

运维配置使用以下 Doppler 密钥名，实际值不写入仓库：

- FEINIANYU_IME_TELEMETRY_URL
- FEINIANYU_IME_TELEMETRY_ACCESS_APP_ID
- FEINIANYU_IME_TELEMETRY_ACCESS_TOKEN_ID
- FEINIANYU_IME_TELEMETRY_ACCESS_CLIENT_ID
- FEINIANYU_IME_TELEMETRY_ACCESS_CLIENT_SECRET

## HTTP 协议 v1

`GET /health` 返回服务标识和协议版本，不检查或返回存储内容。

`GET /v1/device` 要求设备 Bearer 令牌（以及部署环境的 Access 凭据），返回 `{"schema":1,"device_id":"...","capabilities":["upload"]}`，用于客户端核验实际上传授权，不提交采集内容。

`POST /v1/batches/<batch UUID>` 发送压缩后不超过 1 MiB 的 gzip 文件原始字节，包含以下头：

| HTTP 头 | 内容 |
| --- | --- |
| CF-Access-Client-Id | 当前部署环境的专用 Access Client ID |
| CF-Access-Client-Secret | 当前部署环境的专用 Access Client Secret |
| Authorization | Bearer 加设备令牌 |
| Content-Type | application/gzip |
| X-Telemetry-Generation | 本地数据库代次 UUID |
| X-Telemetry-First-Seq | 首个持久化序号，正整数 |
| X-Telemetry-Last-Seq | 最后持久化序号，正整数 |
| X-Telemetry-Event-Count | 事件数量，正整数 |
| X-Content-SHA256 | gzip 原始字节的 SHA-256，小写十六进制 |

不要设置 Content-Encoding，避免传输层自动解压影响校验。设备目录从服务端凭证配置决定，客户端不能指定存储路径。对象路径是 `v1/events/<device>/<generation>/<batch>.jsonl.gz`；使用 generation 而非请求日期，使跨日重试仍命中同一对象。

首次成功返回 201，完全相同的重试返回 200 且 repeated=true；相同批次 ID 的不同字节或元数据返回 409。手机只在回执的设备、代次、批次、序号范围、事件数、字节数及 SHA-256 全部匹配后更新上传进度。

服务端校验身份、元数据、体积、gzip 文件头和压缩字节校验和，不在请求内解压/解析事件。回执只表示该文件已持久化；完整 gzip/JSONL、序号及事件数量由离线建集程序再校验。不能把服务端保存回执视作样本正确性或人工标注。

## 已完成的云端验证

使用临时随机设备和 89 字节合成 gzip 文件完成以下验证：匿名访问被 Access 拒绝（403），专用 Access 凭据访问健康接口成功（200），缺少设备令牌被 Worker 拒绝（401），首次上传 201，相同批次和并发重试 200，冲突 409，错误校验和 422，超过 1 MiB 返回 413，下载路由不存在（405）。通过所有者管理通道从真实 R2 读回文件，原始字节及解压后的合成事件一致。

初始链路测试后将 DEVICE_UPLOAD_KEYS 恢复为空数组，并验证临时设备返回 401。Android 接入后另用独立测试设备完成真实上传与重启恢复，所有者从 R2 下载的 381 字节批次与模拟器原文件完全一致。测试设备的 Worker 授权与专用 Access 令牌均已撤销；正式设备单独配对。验证期间仅上传合成内容，私有运维报告保存在被 Git 忽略的 .private 目录或仓库外。
