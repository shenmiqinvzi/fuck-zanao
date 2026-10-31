# 赞奥热榜（zanao-hot）

把郑州大学「赞奥校园集市」（cid=zzu）的**热榜前 10 名**抓下来，连同**帖子正文、全部评论、全部图片**一起永久存进手机本地 SQLite。

平台会把帖子下架、把评论删掉。这个 App 的思路很简单：**在它还活着的时候，把整份内容（文字 + 图片）搬到自己手机上**。之后不管平台怎么删，你本地那份还在，断网也照样能翻。

---

## 它做什么

| 功能 | 说明 |
|---|---|
| 热榜抓取 | 一次拿 top 10（`rank` / 标题 / 分类 / 热度 / 浏览量 / 评论数 / 赞数） |
| 帖子存档 | 正文、作者、发帖时间、帖内图片、原帖短链、浏览/赞/收藏/状态 |
| 评论存档 | 全量一级评论 + 楼中楼（`root_comment_id` 挂载，缩进显示），已删楼标灰 |
| 图片本地化 | 帖图 + 评论图 + 头像全部下载到 `files/img/`，用 URL 的 md5 命名，永不重下 |
| 多期榜面 | 每次抓取记一期（`20261031-1705`），同帖跨期 rank 变化可回溯 |
| 离线可用 | 断网/飞行模式下照常浏览已存档内容，图片照常显示 |
| 定时抓取 | AlarmManager 链式重排，间隔下限 15 分钟（默认 30），开机自启重排 |
| 一键导出 | 把 db + 全部图片打成 zip，走 SAF 存到任意位置（不要存储权限） |

---

## 装哪个

`dist/zanao_hot_1.0.apk`（33,254 字节）—— 自签名，装之前要允许「安装未知来源应用」。

- 包名 `com.zanao.hot`，versionCode 1 / versionName 1.0
- minSdk 24（Android 7.0）/ targetSdk 34
- 已在 **Android 14 x86_64 模拟器**上跑通全链路（抓取 / 入库 / 看图 / 离线 / 定时 / 导出）

**注意**：仓库里的 APK 是**不带 token** 的版本（见下面「token」一节），装上后要在设置页填自己的 token 才能抓。

---

## token

赞奥的接口靠 `X-Sc-Token` 认人，token 跟账号绑。本仓库**不附带任何可用 token**——`res/values/strings.xml` 里的 `default_token` 是空的。

两种填法：

1. **装好后填**：进 App → 设置 → 粘贴 token → 保存。
2. **构建时编进去**：`.\build.ps1 -Token "你的token"`，脚本会把 token 写进临时资源目录再打包，不改源码。

token 从哪来：抓一次 App 的 HTTPS 流量（mitmproxy / Frida 均可），在 `X-Sc-Token` 头里。它是 URL-safe base64（60 字符，结尾 `=`），原样或 URL 编码都能用，**双重编码会挂**（errno 20）。

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
GET  /thread/hot?count=10&type=3        → data.list（rank 1..10）
GET  /thread/info?id=<tid>              → data.detail（72 字段）+ data.t_sign
GET  /comment/list?id=<tid>             → data.total + data.list（一级 + 楼中楼）
```

### 参数的真实边界（文档里写错的都在这里）

| 项 | 实测结论 |
|---|---|
| `count` | 上限 **10**。`1/2/3` 都回 3 条，`4..10` 精确，**`≥11` 全部回落 3 条**。所以热榜只有前 10，分页参数（page/offset/last_rank…）全部无效 |
| `type` | **字段丰富度开关**。省略或 `=1` 只回 **8** 个字段（thread_id/title/view_count/hot_val/hot_rank/hot_rank_show/view_show/hot_text）；`0/2/3/4…` 回 **25** 个字段。**不影响排名**，只影响字段 |
| `sign` | `/comment/list` 的 `t_sign` **完全可省**，传 `"AAAA"` 也回一样的数据 |
| `from` | `/thread/info` 的 `from` 参数无影响，只传 `id` 即可 |

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
- **CDN 必须加 `@!common` 后缀**。裸 URL 一律 **403 Forbidden**；`@!common` 是原图，`@!sm_w200_h200` 是缩略图。头像 URL 已自带 `@!sm_w100_h100`，不要再加。
- 评论父子关联：一级评论 `root_comment_id='0'`；楼中楼 `root_comment_id=<一级cid>`，`reply_comment_id` 是"回复了谁"。**楼中楼没有 `img_paths`/`img_list`，也不能再嵌套**。
- `is_deleted=true` 的楼内容是「此内容已被用户自行删除」。
- `delete_status="10"` / `status="10"` / `report_status="10"` 是**正常值**，不是删除标记。判"是否被删"看 `delete_reason` / `delete_reason_text` 有没有内容。
- 不存在的 thread_id：`errno 0` + `detail={}` 空对象 + `t_sign` 空 → **必须靠 detail 是否为空判断**，不能靠 errno。
- `c_count` 是评论总数（含已删楼），`/comment/list` 的 `total` 不含已删楼，两个数会差；`l_count` 是**点赞数**，不是评论数（与 `detail.like_num` 逐条相等）。

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
  Api.java           三个接口 + 12 个 header + errno 翻译 + 图片下载
  Db.java            4 张表 + 评论按 root 归并排序
  Fetcher.java       一轮抓取：hot → 逐帖 info + comments → 图片本地化 → 落库
  ImgCache.java      图片永久缓存（files/img/<url的md5>）
  Exporter.java      打包 zip 导出（SAF，无需存储权限）
  AutoReceiver.java  AlarmManager 链式定时（下限 15 分钟）
  BootReceiver.java  开机重排定时
  App.java           全局崩溃捕获（写 files/crash.log）
  MainActivity.java  榜单 / 历史 / 设置 / 导出
  ThreadActivity.java 帖子存档页（正文 + 图 + 全量评论）
res/                图标 + strings
test/               SignTest（签名 11 用例）、ApiLiveTest（真接口连通测试，桌面 JVM 跑）
docs/               设计文档 + 评审意见 + 模拟器实测截图
dist/               已构建的 APK
```

### 数据库

```sql
rank_snapshot(id, ts, period, thread_id, rank, hot_val, view_count, c_count, l_count, title, cate)
threads      (thread_id PK, first_seen, last_seen, title, nickname, headimgurl, cate_name,
              content, pt_time, post_time, view_count, like_num, mark_num, user_level,
              short_url, is_anon, delete_status, status, report_status, img_paths, detail_json)
comments     (comment_id PK, thread_id, uid, root_comment_id, reply_comment_id, is_layer,
              nickname, headimgurl, content, post_time, post_time_text, like_num,
              is_deleted, is_anon, img_urls, raw_json)
comment_snap (id, ts, thread_id, total, ids, new_ids)
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
- 只支持 zzu 一个站（alias 写死）。换学校要改 `Api.ALIAS`。
- token 会过期，过期后 errno 20，去设置页换。

---

## 免责

仅用于个人对公开可见内容的本地存档。接口是公开的 H5 接口，本项目不含任何绕过鉴权、破解加密或攻击服务端的行为——只是带着自己账号的 token，正常调它自己的接口。

MIT License，见 [LICENSE](LICENSE)。
