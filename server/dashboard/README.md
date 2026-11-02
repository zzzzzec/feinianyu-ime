# 飞念语数据仪表盘

独立的 Cloudflare Worker，读取遥测接收端使用的私有 R2 桶，不需要更新 APK 才能调整仪表盘。

当前入口：[飞念语数据仪表盘](https://feinianyu-ime-dashboard.zec2716548913.workers.dev/)。登录沿用现有 GitHub 身份源和“QQ邮箱”可复用策略，仅允许策略内的本人账号。

## 展示内容

- 按日期和设备筛选的事件数、成功上屏字符数、完整拼音选词样本数和设备数。
- 每日采集趋势、被选候选的原始名次与展示名次、最近 40 条拼音及实际选择。前文可展开，文本可以隐藏。
- 续写展示、采纳率、界面延迟 P50/P95、Suzu 排序耗时 P50、结果状态分布。
- 最近上传时间、批次数、存储大小、应用版本、重复记录与未关联记录数。
- 手动刷新、前台每 60 秒自动刷新、导出当前汇总。

## 统计口径

上屏字符只计 `editor_commit.success`，避免把引擎提交重复计入。拼音选词沿用 `scripts/build-input-dataset.py` 的保守关联规则，使用真实拼音，包括拼写错误；部分选词、无法关联、失败提交和有歧义的候选不计为完整样本。首选采纳是用户的自然选择行为，不能作为人工标注正确率。

输入采集和性能采集中的续写记录具有不同的 session ID，不直接相加。当前范围存在成功展示的性能记录时，展示数与采纳率统一使用性能记录，否则使用输入采集记录；页面注明来源。采纳必须能关联到同一来源的展示请求。界面延迟包含输入停顿等待；只有开启性能采集才有该指标。

合成事件及明确标记的测试应用会默认排除；同一测试编辑会话中的原生事件也会排除，可勾选“包含测试记录”查看。重复事件按 installation、generation 和 record_id 去重。异常设备时间会回退到上传时间并计入质量提示。

首次分析最近 200 个符合筛选条件的批次，可继续分析更早批次。每轮最多枚举 5000 个对象，未枚举完时显示提示和继续按钮；每次分析最多 10 万个事件，统计范围始终可见。日期按浏览器本地时间处理，趋势图最多显示最近 30 天。

## 数据和权限

Worker 先验证 Cloudflare Access JWT 的签名、issuer、audience、有效期和邮箱白名单，再提供页面与 API。Access 应用仅启用现有 GitHub 身份源，并引用既有 QQ 邮箱策略；未修改其他应用或账号默认拒绝规则。Worker 的设备上传凭据不能用于仪表盘。

`AUTH_CONFIG` 为 Worker secret，内容为以下结构。实际值通过私有运维渠道配置，不进入源码或网页：

```json
{"issuer":"https://your-team.cloudflareaccess.com","audience":"your-application-audience","emails":["owner@example.test"]}
```

API 仅支持 GET/HEAD：

- `/api/info`：当前登录邮箱。
- `/api/batches`：`v1/events/` 前缀内的批次元数据和分页游标。
- `/api/batch?key=...`：仅允许 UUID 格式的遥测批次路径，返回 gzip 原始字节。

没有上传、修改或删除接口。R2 桶保持私有。所有页面和响应均 `private, no-store`，未引用外部脚本、字体或统计服务。

压缩批次在已登录的浏览器内校验 SHA-256、解压并聚合，避免在 Worker 请求内全量扫描事件。要求浏览器支持 `DecompressionStream('gzip')`。每批限制压缩 1 MiB、解压 8 MiB、单条 1 MiB、2048 条；失败批次可刷新重试。浏览器只保留有界内存缓存，不写入 localStorage 或 IndexedDB。

## 构建与部署

独立于 Android 构建，无前端包依赖。需要 Node.js 22 和 Wrangler。

```powershell
cd server/dashboard
node scripts/build-assets.mjs
node --test test/*.test.mjs
wrangler deploy --dry-run --config wrangler.toml
wrangler deploy --config wrangler.toml
wrangler secret put AUTH_CONFIG --config wrangler.toml
```

从此目录运行 Wrangler，保证自定义构建命令使用正确工作目录。生成的 `.generated/` 不提交到 Git。首次部署应先配置精确主机名对应的 Access 应用和授权策略；没有有效 AUTH_CONFIG 时 Worker 保持拒绝访问。

验证包含签名伪造、错误受众、过期会话、未授权读取、目录越界、写操作拒绝、批次完整性和真实拼音关联。云端配置读回确认 R2 binding、AUTH_CONFIG 和 Access audience 一致，匿名请求跳转登录；现有 R2 批次通过相同解压与聚合代码读回验证。实际浏览器视觉和 GitHub 登录流程需使用者打开入口确认。

验签公钥请求使用 `redirect: 'manual'`，收到重定向时拒绝，不继续跟随。Workers 不支持 Node 中可用的 `redirect: 'error'`。除上述 Node 测试外，使用官方 Miniflare/workerd 运行真实默认验签路径，覆盖公钥获取、签名、缓存、邮箱、有效期、匿名及公钥端点重定向：

```powershell
node scripts/check-auth-runtime.mjs
```

需要可解析的 `miniflare` 测试工具；也可以用 `MINIFLARE_MODULE` 指向已安装 Wrangler 携带的 Miniflare 模块路径。测试只使用临时生成的合成签名和公钥，不连接真实账号或 R2。
