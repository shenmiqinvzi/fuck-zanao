# 赞奥热榜（zanao-hot）

把郑州大学「赞奥校园集市」（cid=zzu）的**热榜**连同**每期完整榜面、帖子正文、全部评论、全部图片**一起永久存进手机本地 SQLite。

每一轮抓取都会存成**一期**：期头一行（`hot_period`，含整期榜面 JSON）+ 该期每帖一行（`rank_snapshot`）。所以你随时可以回看「某个时刻热榜长什么样、谁第几名、热度多少」。

平台会把帖子下架、把评论删掉、把正文改掉。这个 App 的思路很简单：**在它还活着的时候，把整份内容（文字 + 图片）搬到自己手机上**，并且**每一次内容变化都留旧版**。之后不管平台怎么删改，你本地那份还在，断网也照样能翻。

---

## 它做什么

| 功能 | 说明 |
|---|---|
| 热榜抓取 | 每轮打一次 `/thread/hot?count=10&type=3`，取 rank 1~10，**存成一期**（期头 + 每帖一行榜面） |
| 实时热榜页 | 展示最新一期的 rank 徽标 / 标题 / 热度 `hot_val` / 浏览 `hot_text` / 评论数 / 赞数，前三名橙底大字 |
| 历史期数页 | 按 `ts` 倒序列出所有期，点进去可回看那一期的完整榜面 |
| 名次轨迹 | `rank_event` 只在名次变化时记一行，帖子详情页底部显示「第 N 名（时间）」 |
| 全部帖 | 时间流页签，按发帖时间倒序翻全部已入库帖子，带「榜N」角标 |
| 帖子存档 | 正文、作者、发帖时间、帖内图片、原帖短链、浏览/赞/收藏 |
| 正文版本化 | 正文 md5 变了才留旧版（`thread_detail_snap`），作者编辑后旧正文不丢 |
| 评论存档 | 全量一级评论 + 楼中楼（`root_comment_id` 挂载，缩进显示） |
| 评论原话留存 | 评论被删时**先把原话存进 `comment_content_snap` 再覆盖**，界面上紫底显示「（原话）」 |
| 图片本地化 | 帖图 + 评论图 + 头像全部下载到 `files/img/`，**按图片内容 md5 命名**（同图不同 URL 只存一份），落盘一律 `@!sm_w400` 缩略图 |
| 手动刷新 | 打一次 `/thread/hot`，几十秒内完成（存一期 + 拉在榜帖评论/图片） |
| 断点回填 / 滚动复查 | **已停用（2026-11-01）**。原来靠 `/thread/search` 的 `end_time` 游标往回补历史，该接口降级成空列表后已没有任何能向更早翻页的接口，代码里这两段整体删除；`meta` 里的 `backfill_cursor` / `refresh_cursor` 只作历史残留读取 |
| 离线可用 | 断网/飞行模式下照常浏览已存档内容，图片照常显示 |
| 定时抓取 | AlarmManager 链式重排，间隔下限 **5 分钟**（默认 **15**），开机自启重排。热榜前 10 变化很慢，不需要高频轮询 |
| 一键导出 | 把 db + 全部图片打成 zip，走 SAF 存到任意位置（不要存储权限） |

### 为什么又回到"每期存一份快照"（v4，2026-11-01）

v2/v3 一度把期快照删掉了，理由是「名次只在变化时记一行就够了」。这个取舍是错的 ——
它把「某个时刻热榜长什么样」这个信息永久丢掉了：`rank_event` 只记变化过的帖，
所以历史期里永远只有 2~3 条，不是完整榜面（本机实测 16 个伪期全部只有 2 条）。

v4 恢复期快照，并且**和存档不冲突**：
- `rank_snapshot` 每期每帖一行（`rank` / `hot_val` / `hot_text` / `view_count` / `c_count` / `l_count` / `title` / `raw_json`），**哪怕名次没变也写**；
- `hot_period` 每期一行，`raw_json` 存整期榜面条目，可以完整还原那一期的榜面；
- `threads` 仍以 `thread_id` 为主键一帖一行，`comments` 仍以 `comment_id` 为主键一条一行，正文/评论只有**内容真的变了**才额外留历史。

一行榜面约 200 字节，一轮 10 行 = 2 KB。按 15 分钟一轮算，一天 96 期 ≈ 200 KB —— 存一年也就 70 MB，完全不值得为省这点空间丢掉「历史榜面」。

---

## 装哪个

`dist/zanao_hot_1.0.apk`（379,192 字节）—— 自签名，装之前要允许「允许安装未知来源应用」。

- 包名 `com.zanao.hot`，versionCode 1 / versionName 1.0
- minSdk 24（Android 7.0）/ targetSdk 34
- 已在 **Android 14 x86_64 模拟器**上跑通全链路（热榜 10 条存成一期 · 1029 帖 / 2977 评论 / 540 图 · 三个页签 · 详情页 · 离线 · 定时 · 导出）

**注意**：仓库里的 APK 是**不带 token** 的版本（见下面「token」一节），装上后要在设置页填自己的 token 才能抓。

---

## token

赞奥的接口靠 `X-Sc-Token` 认人，token 跟账号绑。本仓库**不附带任何可用 token**——`res/values/strings.xml` 里的 `default_token` 是空的。

两种填法：

1. **装好后填**：进 App → 设置 → 粘贴 token → 保存（**必须是 60 字符**，少/多一个字符都会报 errno 20，保存时会被拦下）。
2. **构建时编进去**：`.\build.ps1 -Token "你的token"`，脚本会把 token 写进临时资源目录再打包，不改源码。

token 从哪来：抓一次 App 的 HTTPS 流量（mitmproxy / Frida 均可），在 `X-Sc-Token` 头里。它是 URL-safe base64（60 字符，结尾 `=`），原样或 URL 编码都能用，**双重编码会挂**（errno 20）。

### token 取值优先级（2026-11-01 修复）

```
1) 设置页手填 + 合法（token_manual=true 且长度 60 全 base64）  ← 最高
2) APK 内置 R.string.default_token（合法就用，并回写 prefs）
3) prefs 里的旧值（上面两个都不可用时兜底）
```

**为什么改**：旧实现是 `t = prefs.getString("token",""); if (t.length()==0) 才读内置值` ——
只要 prefs 里存过任何非空字符串，APK 内置的正确 token 就永远不生效。典型翻车现场：

- 某次把 token 手工抄错一个字符（`...FWmpsTThtmQ...`，**61 字符**，正确是 `...FWmpsTThtQ...`）
- 那版装上去写进了 SharedPreferences
- 之后换多少个正确的新包都报 `errno 20 登录中...`，因为 `tokenOf()` 见长度非 0 就直接用旧值

现在 `MainActivity.tokenOk(String)` 卡死 `TOKEN_LEN == 60` + 全 base64 字符，
`tokenOf(Context)` 先看手填标记，再拿内置值覆盖 prefs；设置页保存时手填值不合法会
Toast 提示「token 必须是 60 字符 base64（现在 N 字符），没保存」并拒绝落盘。
`AutoReceiver` 也改成走 `MainActivity.tokenOf(app)` + `tokenOk`，定时任务不再吃旧坏值。

已实测复现与修复（Android 14 x86_64 模拟器）：
手工把 61 字符坏 token 推进 `shared_prefs/zanao.xml` → 点刷新 → 状态栏
`抓取失败：未登录（errno 20）：token 失效或为空，去设置页换一个新 token`；
装新包启动后 prefs 里的 token 被自动改回 60 字符正确值，点刷新恢复正常抓取。

---

## 自己构建（零 Gradle / 零 Android Studio）

这套构建链是手搓的：`aapt2 → javac → d8 → jar → zipalign → apksigner`，不依赖 Gradle、不依赖 androidx、不依赖任何第三方库。APK 里只有 37 个 class，dex 48 KB。

### 前置

- JDK 8+（实测 JDK 25 可用），`JAVA_HOME` 指过去
- **build-tools r37**（`aapt2.exe` / `d8.bat` / `zipalign.exe` / `apksigner.bat`）
- **android.jar**（platform android-34）
- 下载地址：`https://dl.google.com/android/repository/build-tools_r37_windows.zip`、`https://dl.google.com/android/repository/platform-UpsideDownCake_r04.zip`

### 跑

```powershell
.\build.ps1 -Clean -Sdk "D:\android-sdk"
```

脚本会按 `-Sdk` → `ANDROID_SDK_ROOT` → `ANDROID_HOME` → 脚本同级目录 的顺序找 SDK。

### 四条硬性约束（踩过的坑，别改）

1. **工程和 SDK 必须放纯 ASCII 路径**。aapt2 在中文路径下**静默失败**：
   `aapt2. W ... AssetsProvider.cpp:114] Failed to stat file ...: No such file or directory`
   —— exit 0，但产物缺资源，装上去白屏。
2. **build-tools 必须 r37**。r34 的 d8 遇到**任何匿名内部类**必崩：
   `java.lang.NullPointerException: Cannot invoke "String.length()" because "<parameter1>" is null`
   （最小复现：5 行 `new Runnable(){...}` 就够；`-g:none` 无效；具名嵌套类和 lambda 不受影响）
3. **javac 必须 `-source 8 -target 8 -bootclasspath`**。用 `--release 8` 会报
   「选项 --boot-class-path 不能与 --release 一起使用」。
4. **`build.ps1` 本身必须存成 UTF-8 with BOM**，否则 PowerShell 5.1 读到中文注释直接崩
   `Unexpected token '}' in expression or statement`。

### 签名

仓库带了一份自签 keystore `zanao.jks`（storepass / keypass 都是 `***REMOVED-PASS***`，alias `zanao`），
**升级必须用同一份**，否则 Android 会拒绝覆盖安装。想换自己的：`keytool -genkeypair ...` 后改 `build.ps1` 第 7 步。

---

## 接口逆向结论（都是实测，不是猜的）

接口全走 **http 明文**（`http://api.app.zanao.com`）——https 会报
`SSLEOFError(8, '[SSL: UNEXPECTED_EOF_WHILE_READING] EOF occurred in violation of protocol')`，
所以 manifest 里必须 `android:usesCleartextTraffic="true"`。

### 三级采集链

```
GET  /thread/hot?count=10&type=3                 → data.list（rank 1..10，**主源**，自带全文）
GET  /comment/list?id=<tid>                      → data.total + data.list（一级 + 楼中楼）
GET  /thread/new/list?type=1                     → data.list（最新 10 条，不可翻页，「全部帖」页签补充用）
GET  /thread/info?id=<tid>                       → 已降级为空 detail，仅作兜底
```

**`/thread/hot` 的条目本身就是完整详情**（25 字段）：`content` 全文（实测 377 字符）、
`img_paths`（相对路径数组）、`c_count`、`l_count`、`view_count`、`hot_val`、`hot_text`、
`hot_rank`、`hot_rank_show`、`nickname`、`headimgurl`、`cate_name`、`post_time`。
所以 `/thread/info` 挂掉也不影响热榜存档 —— 正文直接来自热榜响应。

> **2026-11-01 重要变更：`/thread/v2/list` 已彻底失效。** 服务端固定返回
> `{"errno":0,"errmsg":"","data":{"list":[]}}`（99 字节，永远空）。参数（`count` /
> `from_time` / `p_time` / `last_time` / `start_time` / `max_time` / `before` / `after` /
> `cursor` / `time` / `ts` / `from` / `type` / `cate_id` / `sort` / `keyword` 全试过）、
> 版本号（`2.9.0` ~ `4.0.0` 六个）、GET/POST、路径变体（`/thread/v2/list/all` /
> `/thread/v1/list` / `/thread/v3/list` …）**一律空列表**。`/thread/hot`、
> `/thread/info`、`/comment/list`、`/thread/toplist` 在同一 token 同一签名下全部正常，
> 所以不是签名/token/时钟问题，是平台下线了该接口。
>
> 当时的替代接口是 **`/thread/search`**：它的 `keyword` 参数完全无效（传什么都返回同一批帖子），
> 实际上就是个纯时间流，用 `end_time`（unix 秒）作游标，返回 `p_time < end_time` 的帖子。
>
> **2026-11-01 11:19 二次变更：`/thread/search` 也降级了。** 现在它对
> `end_time=now / now-1h / now-6h / now-24h / now-40h / 0 / 无参数`、`keyword`、
> `page`、`p_time`、`search_type`、`scope`、`cate_id`、`sort`、`cid` / `alias` /
> `community_id`、GET 与 POST、版本号 `3.1.0`~`4.0.0` **一律返回
> `{"errno":0,"errmsg":"","data":{"list":[],"total":0,"is_finish":false,"search_tip":"","end_time":0}}`**
> （`end_time` 回显恒 0，不再回显传入值）。`/thread/list`、`/thread/search/v2`、
> `/thread/search/v3` 同样空。`/thread/info` 也一起废了 —— 对老帖和新帖都返回
> `{"errno":0,...,"data":{"is_show":false,"forbid_tip":"","empty_tip":"帖子不存在或已删除","detail":[]}}`，
> `detail` 是**空 list**（145 字节），换 `id` / `thread_id` / `tid` / `cid` / `alias` /
> `type` / `sign` / `with_comment` / `with_reply` / `from_app`、GET/POST、版本
> `3.0.0`~`4.0.0` 全部一样。
>
> **现在唯一还活着的取数路径：**
>
> | 接口 | 状态 |
> |---|---|
> | `/thread/hot` | **主源**。可用：top 10，25 字段，**自带 `content` 全文 + `img_paths` + 计数** |
> | `/thread/new/list?type=1` | 可用：最新 10 条，33 字段（含 `content` 全文、`img_paths`、`is_anon`、`c_count`），**不能翻页**（「全部帖」页签用） |
> | `/thread/toplist` | 可用：1 条 |
> | `/comment/list?id=<tid>` | 可用：对已知 id 正常，条数与 `c_count` 一致 |
> | `/thread/search` · `/thread/list` · `/thread/v2/list` · `/thread/info` | 降级为空 |
>
> `new/list` 的等价别名（返回完全相同的 10 条）：`/thread/newest`、
> `/thread/new/list/v2`、`/thread/new/list2`、`/thread/new`。它们**都不支持
> `end_time` / `from_id` 向更早翻页**，`from_id` 只能过滤出更新的那批。
>
> **2026-11-01 三次降级之后的取舍修正（v4）**：一度把主源换成 `new/list` 并压到 2 分钟轮询，
> 理由是「别把能拿到的都断了」。但 `hot` 条目自带全文，根本不需要 `info`，
> 而热榜前 10 变化很慢（几分钟到几小时才动一次），**15 分钟一轮就够、不会漏**。
> 所以 v4 把主源改回 `/thread/hot`，定时间隔回到 15 分钟（下限 5 分钟）；
> `new/list` 降级为「全部帖」页签的补充源，榜外新帖漏掉是已知代价。

### 参数的真实边界（文档里写错的都在这里）

| 项 | 实测结论 |
|---|---|
| `count` | `/thread/hot` 上限 **10**，且**越界会静默回落 3 条**：`1/2/3`→3 条、`5`→5、`8`→8、`9`→9、`10`→10、**`11/12/15/20/50`→3 条**。`/thread/search` 与 `/thread/new/list` 的 count 则完全无效（`1/5/10/20/30/50/100/200` 一律 9~10 条） |
| `hot_text` vs `hot_val` | **`hot_text` 是浏览量的紧凑写法**（`8.0K` ≈ `view_count` 7951），**不是热度**。真正的热度是 `hot_val`（`168`/`99`/`78`…）。UI 里必须分开标「热度 hot_val · 浏览 hot_text」，否则两个数字会撞在一起 |
| `end_time` | 曾经是 `/thread/search` 唯一有效的翻页参数（返回 `p_time < end_time`，窗口约 47h：`-47h` 还有 9 条、`-48h` 起 `total=0`）。**2026-11-01 11:19 起该接口整体降级，`end_time` 彻底无效，`total` 恒 0** |
| `from_id` | `/thread/new/list` 的唯一有效参数，语义是「id > from_id 的下限过滤」，**只能拿到更新的帖子，拿不到更旧的**（传末条 id 会回 9 条，传更小的 id 又跳回最新 10 条） |
| 其余分页参数 | `page` / `offset` / `last_id` / `cursor` / `last_rank` / `from_time`（对 search）… 全部无效 |
| `type` | **字段丰富度开关**。省略或 `=1` 只回 **8** 个字段；`0/2/3/4…` 回 **25** 个字段。**不影响排名**，只影响字段。`/thread/new/list` 则相反：**不带 `type` 返回空，带任意 type 值才返回 10 条** |
| `sign` | `/comment/list` 的 `t_sign` **完全可省**，传 `"AAAA"` 也回一样的数据 |
| `from` | `/thread/info` 的 `from` 参数无影响，只传 `id` 即可 |
| GET vs POST | POST 一律 `errno 6` 签名校验失败，全部接口都用 GET。（`/thread/info` 曾是唯一能拉老帖全量的接口，2026-11-01 已降级为空 detail） |

### header 必需性分级

硬必需 8 个：`X-Sc-Nd`（20 位随机数）、`X-Sc-Td`（当前 unix 秒）、`X-Sc-Ah`（签名）、`X-Sc-Nt-V:310`、`X-Sc-Alias:zzu`、`X-Sc-Client:app`、`X-Sc-Token`、`X-Sc-Version:3.1.0`。

可省：`X-Sc-Hb-V` / `X-Sc-Platform` / `X-Sc-Device` / `User-Agent`。

**最坑的一条**：`X-Sc-Version` 缺失 → `errno 0` 但 `list` 是**空数组**。errno 正常、静默失败。
同理 `X-Sc-Alias` 传成别的学校（如 `pku`）也是 `errno 0` + 空列表。

### errno 对照（App 里已翻译成人话）

| errno | 含义 | 触发原因 |
|---|---|---|
| 0 | 成功 | — |
| 3 | illegal request | 接口路径根本不存在 |
| 6 | 签名校验失败 | `X-Sc-Nd` / `X-Sc-Ah` / `X-Sc-Nt-V` 缺失或值错 |
| 10 | 请求被拒 | 「用户本地网络异常」= `X-Sc-Td` 缺失或 =0（**手机时间不准**）；「ID非法」= id 空或 0 |
| 20 | 登录中… | token 空/乱码、alias 空、`X-Sc-Alias` 或 `X-Sc-Client` 缺失、`X-Sc-Client=web` |
| 30 | 未知的社区 | alias 大小写错（`ZZU` ✗，必须小写 `zzu`）或不存在 |

### 字段名的坑

- 头像字段是 **`headimgurl`**，不是 `avatar_url`（不存在这个字段）。匿名用户的头像是 `/anon/avatar/...`，实名的是 `/upload/...`，两种前缀。
- 图片：`img_paths` 是**相对路径数组**（`upload/2026/10/30/xxx.jpg`，不带域名）；`img_list` 是 `[{"prv_url":..,"raw_url":..}]`，**优先用 `raw_url`**。
- **CDN 必须加 `@!` 后缀**。裸 URL 一律 **403 Forbidden**。实测可用后缀只有三个：
  | 后缀 | 结果 |
  |---|---|
  | `@!common` | 200，原图（1200×2664 / 3427×4570，最大 1.7 MB） |
  | `@!sm_w400` | 200，**400 宽缩略图**（本 App 落盘用的就是这个，压缩比约 11x） |
  | `@!sm_w200` / `@!sm_w100` | 200，更小的缩略图 |
  | `@!sm_w300` / `@!sm_w500` / `@!sm_h400` / `@!sm` / `@!middle` / `@!small` / `@!thumbnail` | **404** |

  头像 URL 已自带 `@!sm_w100_h100`，不要再套。
- 评论父子关联：一级评论 `root_comment_id='0'`；楼中楼 `root_comment_id=<一级cid>`，`reply_comment_id` 是"回复了谁"。**楼中楼没有 `img_paths`/`img_list`，也不能再嵌套**。
- `is_deleted=true` 的楼内容是「此内容已被用户自行删除」。
- `delete_status="10"` / `status="10"` / `report_status="10"` 是**正常值**，不是删除标记。判"是否被删"看 `delete_reason` / `delete_reason_text` 有没有内容。
- 不存在的 thread_id：`errno 0` + `detail={}` 空对象 + `t_sign` 空 → **必须靠 detail 是否为空判断**，不能靠 errno。
- `c_count` 是评论总数（含已删楼），`/comment/list` 的 `total` 不含已删楼，两个数会差；`l_count` 是**点赞数**，不是评论数（与 `detail.like_num` 逐条相等）。
- `threads.detail_json` 里 `img_paths` 是相对路径（不含后缀）；`comments.img_urls` 存的是**完整 URL 且已带 `@!common`**；`img_ref.url` 存完整 URL（带后缀）。三处格式不一致，取图时统一走 `Api.thumbUrl()`。

### 签名算法（makeSign）

```
body   = 请求参数按 key 升序拼 "k=v"，用 & 连接，value=="key" 的跳过
concat = randnum__ + timestamp__          （缺省字面量 "null"）
K1     = 用自定义码本做 base64(concat)    码本:
         HySuiITldDszAQ9ZCx2pkePtE0qgUr_JYn1KNjO-afc4b7M658BFGLRVWXhmovw3
         ⚠ 尾块不足 3 字节时**要补 '='**（不是"无 padding"）
H1     = md5_hex_lower(K1)
K2pre[i] = (K1[i] + H1[i % 32]) & 0xFF
K2     = 标准 base64(K2pre)              （带 padding）
sign   = md5_hex_upper(body + "&secretkey=" + K2)
```

token / alias 不参与签名。移植见 `src/com/zanao/hot/Sign.java`，11 条用例在 `test/SignTest.java`。

---

## 代码结构

```
src/com/zanao/hot/
  Sign.java          makeSign 算法（零 Android 依赖，桌面 JVM 可直接跑）
  Api.java           四个接口 + 12 个 header + errno 翻译 + 图片下载 + 缩略图 URL
  Db.java            10 张表 + 期快照 + 内容版本化 + 评论按 root 归并排序
  Fetcher.java       一轮抓取：/thread/hot 存一期 → 在榜帖评论/图片 → 缩略化
  ImgCache.java      图片永久缓存（按内容 md5 命名、@!sm_w400、shrink/gcOrphans）
  Exporter.java      打包 zip 导出（SAF，无需存储权限）
  AutoReceiver.java  AlarmManager 链式定时（下限 5 分钟，默认 15）
  BootReceiver.java  开机重排定时
  App.java           全局崩溃捕获（写 files/crash.log）
  MainActivity.java  三页签（实时热榜 / 历史期数 / 全部帖）+ 统计 / 设置 / 导出
  ThreadActivity.java 帖子存档页（正文 + 旧版正文 + 名次轨迹 + 全量评论 + 已删原话）
res/                图标 + strings
test/               SignTest（签名 11 用例）、ApiLiveTest（真接口连通测试，桌面 JVM 跑）
docs/               设计文档 + 评审意见 + 模拟器实测截图
dist/               已构建的 APK
```

### 数据库（v4，10 张表）

```sql
-- 帖子：thread_id 主键，一帖一行，正文被覆盖前先存快照
threads(thread_id PK, first_seen, last_seen, posted_at, content_md5, title, nickname,
        headimgurl, cate_name, content, view_count, c_count, l_count, hot_rank, hot_seen_ts,
        has_detail, comment_fetched_ts, comment_total, img_paths, detail_json, ...)
        -- idx_t_posted(posted_at DESC)

-- 正文历史版本：只在 content 的 md5 变化时写一行
thread_detail_snap(id, thread_id, ts, content_md5, title, content, detail_json, reason)

-- 评论：comment_id 主键，一条一行
comments(comment_id PK, thread_id, uid, root_comment_id, reply_comment_id, is_layer,
         nickname, headimgurl, content, content_md5, first_seen, last_seen,
         post_time, like_num, is_deleted, is_anon, img_urls, raw_json)

-- 评论原话留存：被删/被改时先把旧内容存进来，再覆盖 comments.content
comment_content_snap(id, comment_id, thread_id, ts, content, is_deleted, raw_json)

-- 热榜期头：每轮一行，raw_json 存整期榜面（可完整还原"那一刻热榜长什么样"）
hot_period(ts PK, in_list, raw_json, note)

-- 期快照：每期每帖一行，哪怕名次没变也写
rank_snapshot(id PK AUTOINCREMENT, ts, thread_id, rank, hot_val, hot_text,
              view_count, c_count, l_count, title, raw_json)
              -- idx_rs_ts(ts DESC) / idx_rs_tid(thread_id, ts DESC)

-- 名次轨迹：只在名次变化时记一行（帖子详情页底部展示用）
rank_event(id, ts, thread_id, rank, hot_val)

-- 图片：url → 内容 md5（同一张图挂不同 URL 只存一份文件）
img_ref(url PK, content_md5, bytes, first_seen)

fetch_log(id, ts, pages, new_threads, new_comments, changed_comments, img_ok, img_fail,
          oldest_posted_at, note)
meta(key PK, value)   -- last_fetch_ts（backfill_cursor / refresh_cursor 是 v2 残留，只读不用）
```

评论排序（楼中楼挂在一级下面）：

```sql
ORDER BY CAST(CASE WHEN root_comment_id='0' THEN comment_id ELSE root_comment_id END AS INTEGER) ASC,
         is_layer ASC, CAST(comment_id AS INTEGER) ASC
```

---

## 桌面侧验证（不用装 App 也能测接口）

```powershell
# 需要 org.json（https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar）
javac -encoding UTF-8 -cp json.jar -d out\live src\com\zanao\hot\Sign.java src\com\zanao\hot\Api.java test\ApiLiveTest.java
java "-Dfile.encoding=UTF-8" "-cp" "out\live;json.jar" ApiLiveTest "你的token"
```

注意 JVM 参数必须带引号，`java -Dfile.encoding=UTF-8` 会被 PowerShell 拆成 `/encoding=UTF-8` 报 `ClassNotFoundException`。

---

## 已知限制

- **没在真手机上验过**。全部验证在 Android 14 x86_64 模拟器（WHPX 加速，720×1280）。
- 国产 ROM 需要手动在「电池优化 / 自启动」里给「赞奥热榜」放行，否则后台 AlarmManager 会被杀。
- 热榜只有前 10，拿不到 11+。这是接口硬限制，不是没做。
- **热榜之外的帖子抓不全**：`/thread/v2/list`、`/thread/search`、`/thread/list`、`/thread/info` 在 2026-11-01 先后被服务端降级成空列表 / 空 detail。「全部帖」页签里的新帖来自 `/thread/new/list?type=1`（最新 10 条、不可翻页），窗口只有 10 条而新帖速率约每分钟 3~9 条，所以**榜外的新帖会漏**。热榜本身不受影响（每轮 top 10 都能拿全）。
- **老帖正文不再可更新**：`/thread/info` 降级后，已入库的非热榜帖没法再拉一次详情，`has_detail=0` 的历史遗留帖（本机 5 条，2026-10-31 20:12~20:22 那批）永远补不上。仍在榜的帖不受影响 —— 热榜响应自带全文。
- **不能补历史**：v3 之前的 16 个伪期是从 `rank_event` 回灌的，只有 2~3 条（不是完整榜面），期数页已隐藏并注明。v4 起每轮都存完整 top 10。
- 只支持 zzu 一个站（alias 写死）。换学校要改 `Api.ALIAS`。
- token 会过期，过期后 errno 20，去设置页换。**粘贴时务必数一遍长度是 60**：多一个字符（比如 `...TThtmQ...` 就是 61）服务端同样只回 `errno 20 登录中...`，看起来跟"过期"一模一样。
- 如果换过好几个正确的新包还一直报 errno 20，先看设置页输入框里到底是什么值 —— 2026-11-01 之前的版本会让 prefs 里的旧坏值永久压住 APK 内置值（修复见上面「token 取值优先级」）。
- 落盘的是 `@!sm_w400` 缩略图，**不是原图**。列表和详情页（显示宽度 660px）足够清晰；需要原图的场景（如另存、放大看细节）要自己拼 `@!common` 重新下。

---

## 免责

仅用于个人对公开可见内容的本地存档。接口是公开的 H5 接口，本项目不含任何绕过鉴权、破解加密或攻击服务端的行为——只是带着自己账号的 token，正常调它自己的接口。

MIT License，见 [LICENSE](LICENSE)。
