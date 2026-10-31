# jenkins-atom-bitbucket-status

Bitbucket build status — port of **stashNotifier** (6,217) as an atom.

The plugin is a `Notifier` (`prebuild` + `perform`), so it never had a Pipeline step, but the whole of it is one HTTP
call: `POST {root}/rest/build-status/1.0/commits/{sha}` with `{state, key, name, description, url}` and the three
Bitbucket states `SUCCESSFUL` / `FAILED` / `INPROGRESS`.

## What is ported, exactly

Read out of the plugin with `javap` (`stashNotifier 2.7`, `BuildStatusUriFactory` + `StashNotifier.perform` +
`createNotificationPayload`):

| 插件行为 | 原子行为 |
|---|---|
| URL = `root` 去掉尾斜杠 + `/rest/build-status/1.0/commits/<sha>` | 同 |
| 报文恰好五个字段：`state`、`key`、`name`、`description`、`url` | 同 |
| `SUCCESS` → `SUCCESSFUL`；`FAILURE`/其它 → `FAILED`；`result == null` → `INPROGRESS` | 同；`BUILDING`/`RUNNING`/`PENDING` 也算 `INPROGRESS` |
| `UNSTABLE` 默认 `FAILED`，`considerUnstableAsSuccess` 打开才 `SUCCESSFUL` | 同（参数名一致，默认 false） |
| `ABORTED`、`NOT_BUILT` 只打印一行就返回，不上报 | 同，输出 `state=SKIPPED`；`notifyAborted=true` 才按 `FAILED` 上报 |
| `description` 缺省：`building on Jenkins @ <rootUrl>` / `built by Jenkins @ <rootUrl>` | 同（`jenkinsRootUrl` 参数，留空用构建链接） |
| 发送前用 `StringUtils.abbreviate` 截断：key/name/description 255、url 450 | 同（超长时 `...` 结尾） |
| `key` 缺省 `JOB_NAME`（可选带 `-BUILD_NUMBER`） | 同。**差异**：插件还会在 key 尾部拼 Jenkins root URL 以区分多 master，原子不拼——要区分就在 `key` 里自己写 |

`key` 是 Bitbucket 里构建标识（同一 commit 上可有多个 key）。`name` 是显示名。

## 与插件不同的两处（有意为之）

1. **写完回读**。插件不看回包；原子默认 `verifyAfterWrite=true`：写完 `GET` 同一个 URL，要求列表里存在 key 相同、
   `state` 相同、`url` 相同的那条。Bitbucket 成功时回 `204 No Content`，前面挂个代理也能回 204，所以 204 本身不算证据。
2. **鉴权两种**：`token`（HTTP access token → `Authorization: Bearer`）或 `username` + `password`（个人访问令牌当密码，
   Basic）。插件只有后者。

## 输入

| 参数 | 说明 |
|---|---|
| `serverUrl` | Bitbucket Server / Data Center 根地址，必填 |
| `sha` | 提交 sha，留空取 `GIT_COMMIT` |
| `state` | 留空按 `BUILD_STATUS` 推断 |
| `key` / `name` / `description` / `buildUrl` | 留空按 `JOB_NAME` / `#BUILD_NUMBER` / 插件文案 / `BUILD_URL` 生成 |
| `username` + `password` | Basic 认证（`password` 是凭据） |
| `token` | Bearer 认证（凭据），给了就优先用它 |
| `considerUnstableAsSuccess` / `notifyAborted` / `includeBuildNumberInKey` / `verifyAfterWrite` | 布尔，默认 false / false / false / true |

输出：`status`（HTTP 码）、`state`、`key`、`apiUrl`、`verified`（回读到状态，未回读则 `not-checked`）。

## 构建与验证

```bash
./build.sh
./tools/smoke.sh
```

`tools/fake_bitbucket.py` 是**按协议重写**的 Bitbucket：每个 POST 自己校验路径、凭据、`Content-Type`、五个字段的
集合、状态词表和长度上限，然后**存盘它准备回吐的内容**——所以客户的回读是真回读。`--mode` 注入服务端说谎的各种
方式：`lying-drop`（回 204 什么都不存）、`lying-state`（回 204 存另一个状态）、`unauthorized`、`forbidden`、`get-405`。

`tools/check.py` 是 Python 第二实现：从 `input.json` 独立重算目标 URL 和五个字段（状态映射、缺省文案、截断规则），
再和请求日志、服务端存盘、`output.json` 三方比对。16 个场景全绿，含 401/403/404、两个说谎的服务端、坏掉的回读端点、
被跳过的 ABORTED、255 字符截断、key 带构建号。

变异检验（都红在预期那条）：
- 回读状态比对改 `if (false && ...)` → 红在 `lying-state`
- `UNSTABLE` 无条件映射成 `SUCCESSFUL` → 红在 `-state` 场景的 check.py 比对
- `ABORTED` 改成上报 `FAILED` → 红在 `--no-request` 的 SKIPPED 断言
