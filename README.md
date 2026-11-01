# jenkins-atom-bitbucket-status

Bitbucket build status — port of **stashNotifier** (6,217) as an atom, plus the pull-request half of
**bitbucket-push-and-pull-request** (4,303).

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

## PR 那一半（1.1.0）

`bitbucket-push-and-pull-request` 4.1.0 拆包核实（`WEB-INF/lib/bitbucket-push-and-pull-request.jar`，194 个类）：
它**不写 PR 评论**，`getComment()` 只是把 hook 报文里的评论读出来当变量。它对 PR 的写只有三件事，
在 `BitBucketPPRPullRequestServerObserver.setApprovedOrDeclined` 里，body 是空 `HashMap`：

| 插件行为 | 原子行为 |
|---|---|
| 构建绿 + `shouldSendApprove()` → `POST .../pull-requests/{n}/approve` | `prAction=approve` 时同 |
| 构建 `FAILURE` + `shouldSendApprove()` → `DELETE .../approve`（撤回） | 同 |
| 构建 `FAILURE` + `shouldSendDecline()` → `POST .../decline` | `prAction=decline` 时同 |
| 写完不看回包 | 每次都回读：`GET .../pull-requests/{n}` 要求 `reviewers`/`participants` 里 `approved` 与预期一致、decline 后 `state=DECLINED` |
| 401/403 只进日志 | 归 `THIRD_PART`（40007/40008） |
| 412/409 不区分 | 409 视为「已批准」，由回读决定成败 |

**评论是插件没有的**（有意加的，不算移植）：`comment` 非空就
`POST .../pull-requests/{n}/comments {"text": "..."}`，支持 `${VAR}` 展开构建参数/环境变量；写完
`GET .../comments?limit=100`，要求列表里真有一条文本完全相同的评论，取它的 id 当输出。Bitbucket 回 201 但没存下
（代理、被审核吞掉、被截断）→ `40009`，不拿状态码当证据。

PR 目标解析：`pullRequestUrl`（形如 `.../projects/P/repos/R/pull-requests/12/overview`，原子只取其中
P/R/12 再拼到 `serverUrl` 的 `/rest/api/1.0` 上），或 `projectKey` + `repoSlug` + `pullRequestId`
（`pullRequestId` 留空取 `CHANGE_ID`）。

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
| `prAction` | `none`（默认）/ `approve` / `decline`；绿批红撤、红关，与插件一致 |
| `pullRequestUrl` 或 `projectKey` + `repoSlug` + `pullRequestId` | PR 目标；做 PR 动作或发评论时必填 |
| `comment` | PR 评论正文，支持 `${VAR}`；留空不发。**插件没有这个功能** |

输出：`status`（HTTP 码）、`state`、`key`、`apiUrl`、`verified`（回读到状态，未回读则 `not-checked`）、
`prAction`（实际做的 PR 动作）、`prVerified`（回读到的 PR 状态）、`commentId`（回读到的评论 id）。

## 构建与验证

```bash
./build.sh
./tools/smoke.sh
```

`tools/fake_bitbucket.py` 是**按协议重写**的 Bitbucket：每个 POST 自己校验路径、凭据、`Content-Type`、五个字段的
集合、状态词表和长度上限，然后**存盘它准备回吐的内容**——所以客户的回读是真回读。`--mode` 注入服务端说谎的各种
方式：`lying-drop`（回 204 什么都不存）、`lying-state`（回 204 存另一个状态）、`unauthorized`、`forbidden`、`get-405`。

PR 那半边，假 Bitbucket 自己维护 PR 状态机：`POST approve`（已批准回 409，非 OPEN 回 409）、`DELETE approve`
（没批过回 404）、`POST decline`、`comments` 增删查都按 Bitbucket 的形状实现，`GET` 读的就是它自己那份状态。
`--mode pr-lie-approve` 回 200 但不记批准，`--mode pr-lie-comment` 回 201 但只存前 10 个字符——两个都在断言里被抓住。

`tools/check.py` 是 Python 第二实现：从 `input.json` 独立重算目标 URL 和五个字段（状态映射、缺省文案、截断规则），
再和请求日志、服务端存盘（含 PR 的 `pr-<port>.json`）、`output.json` 三方比对。25 个场景全绿，含 401/403/404、两个说谎的服务端、坏掉的回读端点、
被跳过的 ABORTED、255 字符截断、key 带构建号。

变异检验（都红在预期那条）：
- 回读状态比对改 `if (false && ...)` → 红在 `lying-state`
- `UNSTABLE` 无条件映射成 `SUCCESSFUL` → 红在 `-state` 场景的 check.py 比对
- `ABORTED` 改成上报 `FAILED` → 红在 `--no-request` 的 SKIPPED 断言
- 批准后的回读删掉 → 红在「200 说批了但没记」那条
- 评论的回读换成常量 → 红在「201 但文本没存下」那条
- 撤回批准改成再 POST approve → 红在红构建那条（409 后被回读判死）
