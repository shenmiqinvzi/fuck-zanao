# 《赞奥热榜 App 开发设计文档》v1.2 评审意见

> 评审时间：2026-10-31 15:3x（Asia/Shanghai）
> 评审方式：不是读文档，是**拿文档当假设逐条打真接口验证**（全部 `http://api.app.zanao.com`，用自实现 makeSign 直连，errno 0 为通过）；打包章节在真机上跑完整工具链验证。
> 被评审文件：`F:\待破解\zanao\热榜App设计文档.md`（167 行，v1.2）
> 验证脚本：`F:\待破解\zanao\_live_verify4.py` ~ `_live_verify13.py`、`_header_probe.py`

---

## 一、总评

**骨架成立，细节有毒。** 签名算法（§3）逐字正确，我照文档实现一遍直连 errno 0，11/11 用例复现；三级采集链（§2）逻辑成立；§7.1 两种边界场景的处理哲学（"快照记流水、存档只增不减"）是对的；§5 逐条评论存档相比电脑端"每帖一行 JSON"确实是升级。

但文档里 **12 处与实测不符、10 处关键缺失**，其中 **5 处按文档写代码会直接跑不通或静默残废**（BASE 用 https、count=50、评论头像字段 avatar_url、aapt2 在中文路径静默失败、`type` 省略/`=1` 时字段退化到 8 个）。另外 §9 定的 Kotlin 路线在本机**根本不可行**（无 kotlinc、无 gradle）。

判定：**v1.2 不能直接照着开发，需修订为 v1.3 后开工。** 修订量不大（约 27 处局部改动），但每一处都是"照抄就翻车"的级别。

### 一条贯穿全文的规律：这个 API 有 5 种"errno 0 的假成功"

| 假成功场景 | 表现 | 后果 |
|---|---|---|
| BASE 用 https | 直接连不上（这个还算好，会报错） | 明确失败 |
| `count ≥ 11` | errno 0，只回 3 条 | 你以为榜上只有 3 帖 |
| `X-Sc-Version` 缺失 | errno 0，list 为空 | 每期都存空快照 |
| `alias` 合法但非本校（如 pku） | errno 0，list 为空 | 同上 |
| **`type` 省略或 `=1`** | **errno 0，10 条，但只有 8 个字段** | **榜单能显示，存档全是空壳** |
| `id` 不存在 | errno 0，`detail: {}` | 入库一堆空壳帖 |

**文档最大的缺陷不是写错了哪个参数，是没意识到这个 API 会用 errno 0 骗你。** 所以 v1.3 必须有一节「静默失败清单 + 每类的判定方法」，否则开发时一定会在某一条上翻车，而且翻车方式是"看起来一切正常"。

---

## 二、会直接导致开发失败的 5 处（P0）

### P0-1 §2 line 27：BASE 写成 `https://api.app.zanao.com`，实际必须用 `http://`

```
https://api.app.zanao.com  → URLError(SSLEOFError(8, '[SSL: UNEXPECTED_EOF_WHILE_READING]
                              EOF occurred in violation of protocol (_ssl.c:1028)'))
http://api.app.zanao.com   → errno 0
```

不是证书问题、不是 TLS 版本问题，是**服务端不接 TLS**。反证：mitm 抓包 `traffic3.flow` 845 条 flow 里 `http://api.app.zanao.com` 812 条、`http://b1.cdn.zanao.com` 15 条——**真实客户端走的就是明文 HTTP**。

→ 文档 line 27「全部 HTTPS GET/POST」必须改成 HTTP。App 侧还要处理 Android 9+ 默认禁明文：`android:usesCleartextTraffic="true"`（或 network-security-config 放行 api.app.zanao.com），漏了这行会 `Cleartext HTTP traffic not permitted` 直接抛异常。

### P0-2 §2 line 30：`count=50` 拿不到 50 条，实际只回落 3 条；上限是 10

实测 count 全扫（`_live_verify11.py`）：

| count | 返回条数 |
|---|---|
| 1 / 2 / 3 / 0 / -1 / abc / 空 / 缺省 | 3 |
| 4 → 10 | 4 / 5 / 6 / 7 / 8 / 9 / **10** |
| 11 / 12 / 13 / 14 / 15 / 20 / 100 | **3（回落）** |

即 **count ≥ 11 不是截断到 10，是掉回默认 3**。文档写 `count=50`，实测结果就是 3 条——正好对应文档 line 30 那句「实际同时段 3 条左右」的自我辩解：**那句"3 条左右"不是平台只有 3 条帖，是你用 50 触发了回落。**

→ 必须改成 `count=10`。改完 rank 1–10 完整拿满。

### P0-3 §2 line 32 + §5 line 109/111：评论头像字段 `avatar_url` 不存在

评论对象真实字段是 **`headimgurl`**（完整 URL，已自带 `@!sm_w100_h100` 后缀，直下 200 image/jpeg 3048 bytes）。`avatar_url` 在 33 个评论字段的并集里**根本不存在**。照文档写 `json.optString("avatar_url")` 会拿到空串，头像本地化整条链路静默失效（不报错、不入库、你以为做了）。

→ 字段名改 `headimgurl`，且**不需要再拼后缀**（已带）。

### P0-4 §9 line 151：aapt2 在中文路径下静默失败

```
aapt2. W ... AssetsProvider.cpp:114] Failed to stat file
  'F:\待破解\zanao\_sdk\platform\android-UpsideDownCake\android.jar': No such file or directory
```

`F:\待破解\` 里的"待破解"三个汉字让 aapt2 的 Windows 路径处理挂掉。**exit code 还是 0**，只是产物缺资源——最难查的那类失败。工程目录在 `F:\待破解\zanao\` 下，必然踩。

→ 文档必须写明：**SDK 与工程一律放纯 ASCII 路径**（实测 `C:\Users\LENOVO\AppData\Local\dsh_android\` 一切正常）。这条不写进去，下一个人会在这里耗半天。

### P0-5 §2 line 30：`type` 不是"榜单类型"，是**字段丰富度开关**；省略或 `type=1` 会退化成 8 字段精简版

这条我一开始判错了，必须自曝：**第一轮我只比"条数"和"rank"**，看到 type=0..100 都是 10 条、rank 都是 1–10，就写了"type 完全无效"。**第二轮把 item 的 key 集也打出来，结论反了。**

| type | 条数 | item 字段数 | 含 `c_count` | thread_id 序列 |
|---|---|---|---|---|
| **缺省（不传）** | 10 | **8** | ❌ | 1..10 |
| `1` | 10 | **8** | ❌ | 1..10 |
| `0` / `2` / `3` / `4` / `5` / `6` / `9` / `100` | 10 | **25** | ✅ | 1..10 |

- **ranking 确实不受 type 影响**：`type=0/2/3/4/5/6/9/100` 与缺省的 thread_id 序列**逐位相同**（2177557959 / 2177613802 / 2177657550 / 2177712472 …）。"type 选榜"这个理解是错的——榜只有一份。
- **但 type 决定返回字段集**：不传 type 或 `type=1` 只回 8 个字段 `{thread_id, title, view_count, hot_val, hot_rank, hot_rank_show, view_show, hot_text}`，**没有 c_count / l_count / nickname / headimgurl / cate_name / img_paths / post_time / report_status**。够画榜单行，不够存档。
- 只有 **`type` 非空且 ≠1** 才回完整 25 字段。

→ 文档 line 30 写 `type=3` 是对的，但**必须补一句"`type` 不能省、不能设 1"**。开发时若图省事把 type 删掉（觉得"反正榜一样"），榜单能显示、存档全是空壳——这是第 5 个「errno 0 的假成功」。

实测精简版返回体（就这么多，一个字段都不多）：
```json
{"thread_id":"2177557959","title":"文院事件及某年级长原图","view_count":33609,
 "hot_val":"503","hot_rank":1,"hot_rank_show":"01","view_show":true,"hot_text":"3.4W"}
```

顺带一个对 §7.1 有用的实测：`hot_val` 在几分钟内 495→501→503 持续变动，`view_count` 33588→33609 也在动。**hot_val 是实时热度，不能拿它判"榜面是否变化"**，要用 thread_id 序列比。§5 存 hot_val 是对的（这是历史热度的原始记录），但 §7.1 那句"榜面不变"的判定依据要写明。

---

## 三、其余与实测不符的 7 处（P1）

### P1-1 §2 line 32：`sign=<t_sign>` 和 `with_hongbao=0` 都可省

`/comment/list` 只传 `id` 就拿到 total=114 / list=77；带 `sign="AAAA"`、不带 sign、`with_hongbao=0`、`with_hongbao=1`——**四种结果完全一致**。t_sign 没有任何校验作用。

→ 文档把它写成必需参数（"三级链第 3 步"），会让 M1 多一个串行依赖：info 失败就拿不到 t_sign，进而"拉不了评论"。实际评论可以独立拉。**建议改成：sign 可选，info 与 comment 解耦。**

### P1-2 §2 line 33：header 必需性分级错误（"缺一可能 20/10"）

逐 header drop 实测（`_header_probe.py`）：

| header | 缺失后果 | 判定 |
|---|---|---|
| X-Sc-Nd | errno 6「签名校验失败」 | 硬必需 |
| X-Sc-Ah | errno 6 | 硬必需 |
| X-Sc-Nt-V | errno 6 | 硬必需 |
| X-Sc-Td | errno 10「用户本地网络异常，请重试」 | 硬必需 |
| X-Sc-Alias | errno 20「登录中...」 | 硬必需 |
| X-Sc-Client | errno 20 | 硬必需 |
| X-Sc-Token | errno 20 | 硬必需 |
| **X-Sc-Version** | **errno 0 但 list=0（空列表）** | **硬必需，但静默失败** |
| X-Sc-Hb-V / X-Sc-Platform / X-Sc-Device / User-Agent | errno 0，正常 10 条 | 可缺 |

→ 最小可用 header 集是 **7 + Version = 8 个**（实测只发这 8 个：errno 0 list=10）。文档「缺一可能 20/10」既不准确又漏了最危险的 Version：**它缺失时 errno 0、errmsg 空、list 空**，App 会当成"这期热榜没帖子"照常写一条空快照入库——数据静默损坏。必须单独标注。

错误值也不一样：`X-Sc-Ah=AAAA` / 32 个 0 → 6；`X-Sc-Nd="x"` → 6；`X-Sc-Td="0"` → 10；`X-Sc-Client=web` → 20；但 `X-Sc-Device=zzz` / `X-Sc-Version=9.9.9` / `X-Sc-Hb-V=999` / `X-Sc-Platform=iOS` 乱填 → 仍 errno 0。即**只有 8 个里的 7 个被服务端真校验，Device/Hb-V/Platform/UA 纯摆设**。

### P1-3 §2 line 43：errno 表不全，30 的真实语义是「未知的社区」

实测（`_live_verify12.py`）：

| 条件 | errno | errmsg |
|---|---|---|
| alias=`zzu` | 0 | — |
| alias=`""` | 20 | 登录中... |
| **alias=`pku`（合法但不属于本 token）** | **0** | **list=0，静默空列表** |
| **alias=`ZZU`（大小写）** | **30** | **未知的社区** |
| alias=`zzu2` / `nonexist` | 30 | 未知的社区 |
| alias=`"zzu "`（尾空格） | 0 | 正常 10 条（服务端 trim） |
| 路径不存在 | 3 | sorry, illegal request |
| id=0 | 10 | ID非法 |

→ 文档只说「30 账号换社区」，漏了 **30 = 未知社区（alias 写错/大小写错）**；更要命的是 alias 写成一个"格式合法但非本校"的值时**静默返回空列表**——和 Version 缺失一样的静默坑。alias 必须硬编码常量 + 不做任何大小写转换。

### P1-4 §2 line 27：`data` 不恒为 dict，有时是 list

- 正常：`data` = dict（含 list / detail / t_sign / is_show / empty_tip / forbid_tip）
- errno 3 / 10 / 20 / 30：`data` = **list**（`[]`）
- `/thread/info` id=0：`data` = `[]` 且 errmsg「ID非法」

→ 文档写「响应统一 `{"errno":0,"errmsg":"","data":{...}}`」，Java 侧若直接 `JSONObject data = root.getJSONObject("data")` 会在任何错误码上抛 `JSONException`，把"业务错误"变成"崩溃"。必须写：data 可能是 array，先判类型。

### P1-5 §2 line 31：detail 是 72 字段，但 `share_num` / `user_name` 不存在，`pt_time` 是字符串

| 文档 §5 用的列名 | 实际 |
|---|---|
| `user_name` | **不存在**，真实字段是 `nickname` |
| `share_num` | **不存在**（detail 里没有转发数） |
| `pt_time INTEGER` | 是 **str**，形如 `'2026/10/30 16:38'`（绝对时间字符串） |
| `like_num INTEGER` | 是 **str** `'358'` |
| `mark_num INTEGER` | 是 **str** `'70'` |
| `user_level INTEGER` | 是 **str**（可能为 `''` 空串） |
| `contact_qq` | 存在，脱敏/空串 |
| `post_time` | 是 **相对时间 str**（`'昨天16:38'`），不是 unix |

→ §5 的 threads 表列类型要整体重排：`pt_time TEXT`（或解析后存两列：pt_time_raw TEXT + pt_time_ts INTEGER），`like_num`/`mark_num` 入库前 `Integer.parseInt` 但要防空串，`user_name` 删掉换 `nickname`，`share_num` 删掉。

### P1-6 §5 line 75/76：`comment_count` / `like_count` 不是 API 字段名，且语义要分清

热榜 item 真实字段 25 个，**没有 `comment_count`、没有 `like_count`**。对应关系：

| 文档列 | 真实字段 | 语义 | 陷阱 |
|---|---|---|---|
| `comment_count` | **`c_count`**（str） | 评论总数 | **含已删楼**，与 `/comment/list` 的 total 不等 |
| `like_count` | **`l_count`**（str） | **点赞数**（与 detail.like_num 恒等：356↔356、161↔161、116↔116…） | 名字像"楼层数"，实际是赞 |
| `view_count` | `view_count`（int） | 浏览数 | ok |
| `hot_text` | `hot_text` | view_count 的紧凑格式（3.3W↔33240） | **不是 hot_val 的格式** |
| `hot_val` | `hot_val`（str） | 热度值 | 是字符串 |

`c_count` 与真实评论数的差额实测：126 vs 108、63 vs 59、33 vs 33、32 vs 23、44 vs 44、23 vs 22。差额 = 已删楼数（该帖 `is_deleted` 11 条，126-108=18）。**列表页展示 c_count 是对的（跟官方 App 一致），但存档页的"共 N 条"要用 `/comment/list` 的 total**，两者混用会出现"显示 126 条、点进去 108 条"。

### P1-7 §5 line 109/112：图片下载规则缺 `@!common` 后缀，裸 URL 全部 403

```
http://b1.cdn.zanao.com/upload/2026/10/30/vmjzhk3w5uxxazl.jpg          → 403 Forbidden
http://b1.cdn.zanao.com/upload/2026/10/30/vmjzhk3w5uxxazl.jpg@!common  → 200 image/jpeg 169847 bytes
http://...@!sm_w200_h200                                               → 200 10944 bytes（缩略图）
```

http/https 结果一样。detail 的 `img_paths` 是**相对路径数组**（`['upload/2026/10/30/vmjzhk3w5uxxazl.jpg']`，不带域名），必须自己拼 `http://b1.cdn.zanao.com/` + 路径 + **`@!common`**。文档只说"逐张下载到私有目录"，没写后缀 → 100% 403，整条图片本地化链路全废。

而头像 `headimgurl` **已经带好 `@!sm_w100_h100`**，不能再拼，否则变 `@!sm_w100_h100@!common` 403。**两类 URL 处理规则相反**，文档必须分开写。

---

## 四、缺失项 10 处（P1/P2）

### M-1 缺 `img_list`——比 `img_paths` 更好用

评论对象同时有：
```json
"img_paths": ["upload/2026/10/31/g4jwpdyg8gq5ksw.jpeg"],
"img_list": [{"prv_url": "https://b1.cdn.zanao.com/upload/...jpeg@!sm_w200_h200",
              "raw_url": "https://b1.cdn.zanao.com/upload/...jpeg@!common"}]
```
10 帖全量扫描：`img_paths` 出现 10 次、`img_list` 出现 10 次（成对）。**`img_list` 直接给好了完整 URL + 正确后缀**，用它就不用手拼域名、不用记 `@!common` 规则。文档 §5 只提 `img_paths`，等于放着现成的不用自己去踩坑。

### M-2 缺 `is_deleted`——永久存档必须标已删楼

108 条评论里 11 条 `is_deleted: true` + content「此内容已被用户自行删除」。文档的 comments 表没有这一列 → 已删楼会被当成正常评论永久存下来，"平台删楼也无损"这句话在语义上是错的（内容其实已被平台替换成占位文本）。→ 加 `is_deleted INTEGER`，UI 上灰显/折叠。

### M-3 缺「帖不存在」的判定规则——errno 0 但 detail 为空

```
id=9999999999 → errno 0，data={detail:{}, empty_tip, forbid_tip, is_show}，t_sign=''
id=0          → errno 10「ID非法」，data=[]
```

→ 文档没写。App 里 `if (errno == 0) 入库` 会把空 detail 写进 threads 表，产生一堆空壳帖。**必须判 `detail` 是否为空对象（或 t_sign 是否为空）**。这是"用 errno 判断成功"的通病，全文多处犯。

### M-4 缺 `delete_status` / `status` / `report_status` 的语义——"10" 是正常值

热榜正常帖常态：`delete_status="10"`、`status="10"`、`report_status="10"`。文档 §5 收了这些列但没定义语义，开发时几乎必然写成 `if (delete_status == "10") 标记已删`——直接全站误判。

补充证据：本地 `threads_zzu.jsonl` 1764 帖里 `report_status="10"` 的 17 条，逐条打 info 全部 errno 0、detail 70–74 字段、`is_show=True`、能拉全量评论（2177557959 total=108、2177233049 total=48、2177120998 total=0）→ **这些帖一条都没被隐藏**。`"10"` 是"状态正常"的编码值，判断删除应看 detail 是否为空 / 评论 total 是否为 0 / `delete_reason_text` 是否有内容。

### M-5 缺「热榜只有前 10，无分页」

page / offset / start / p / last_rank / rank / from_rank **全部无效**，加上去结果与 baseline 逐字节相同（same_as_baseline=True）。`/thread/v2/list` 也一样（count 3→100 恒 10 条，page/offset/last_id 无效）。`/thread/toplist` 恒返回空 list。

→ 「每期热榜」的定义必须钉死为 **top 10**。文档 §6 历史期数页写「在榜 3 帖」的示例也要改成 10。

### M-6 缺「楼中楼不能带图」

`reply_list` 元素字段并集里 **没有 `img_paths` / `img_list`**（也没有嵌套 `reply_list`），但有一级没有的 `reply_nickname`、`is_layer`。→ 文档 §5 line 112「楼中楼同规则（图片本地化）」是空转：楼中楼本来就没有图。可以删掉这句，省一轮开发。

### M-7 缺「图片扩展名不能硬编码 .jpg」

§5 写死 `p_<n>.jpg` / `c_<n>.jpg` / `avatar.jpg`，但实测 CDN 返回的是 `.jpeg`（image/jpeg）、`.jpg`、`.png` 混排。→ 落盘扩展名按 `Content-Type` 或原路径后缀决定，否则 BitmapFactory 可能因后缀不符拒绝解码（尤其 png 存成 .jpg）。

### M-8 §8 引用的 `zc.py` 不存在

`F:\待破解\zanao\zc.py` **没有这个文件**（其余 15 个资产全在）。§8 line 144 拿它当 Python 参考实现 → 失效引用。改成 `direct_client.py`（含 call() 直连+签名，可直接当接口调试器）/ `sign_final5.py`（11 条签名用例）。

### M-9 附录数据过期

- 附录 line 166：「hot.db（threads **34 帖**…）」→ 实际 `threads` 表 **4 行**，`hot_snap` 39 行、distinct thread 4 个、rank 范围 1–3，`details` 4 / `comments` 4 / `comment_snap` 26。
- 附录 line 164：「threads_zzu.jsonl 1764 帖」→ 正确（1764 行，字段 33 个，含 `comment_list` 内嵌评论）。
- 附录 line 167 提到 `libAesNew.so`，实际文件名是 `libAesNew_x64.so`（另有 `frida-server`）。

### M-10 §5 与电脑端 schema 的差异没交代

电脑端 `hot.db` 的 `comments` 表是 **`thread_id` 主键 + 一整个 JSON 大字段**（每帖一行），不是逐条评论一行。文档 §5 的逐条评论表是新设计——**这是升级，不是照抄**，值得在文档里点一句，否则后续"和电脑端对齐"会踩空。另电脑端 `comment_snap` 多一列 `comment_ids`（全量 id 快照），文档只有 `new_ids`；差集虽可从 comments 表重算，但保留全量列更稳（能发现"评论被删"）。

---

## 五、§9 打包路线：Kotlin 不可行，Java 已实测跑通

### 文档定的 Kotlin 路线在本机做不到

- `kotlinc` **不存在**（全盘搜 `kotlin-compiler*.jar` 零结果）
- 无 gradle 发行版（只有 VSCode 插件的 `gradle-server.bat`、别的项目的 `gradlew.bat`，都不是可用发行版）
- 无 Android SDK / Android Studio

→ §9「Kotlin，依赖仅 okhttp + kotlinx-coroutines」是**空中楼阁**。改成：**纯 Java + `HttpURLConnection` + `Thread`/`Executor`，零第三方依赖**（文档 line 150 的括号备选其实是唯一可行方案，应提为主方案）。

顺带：okhttp 需要 maven 拉 jar，本机无 maven 环境，也没必要——`HttpURLConnection` 完全够（只有 3 个 GET 接口 + 2 张图）。

### 工具链已实测跑通（这份流程可直接抄进文档）

下载（URL 要写对，`platforms;android-34` 的 `<url>` 不是 `platform-34*.zip`）：

```
https://dl.google.com/android/repository/build-tools_r34-windows.zip          → 58,253,258 B（6.8s）
  解压 → bt/android-14/{aapt.exe,aapt2.exe,d8.bat,d8.jar,zipalign.exe,apksigner.bat,apksigner.jar,lib/}
https://dl.google.com/android/repository/platform-UpsideDownCake_r04.zip      → 63,483,782 B（7.5s）
  解压 → platform/android-UpsideDownCake/android.jar（26,351,733 B）
```

构建（工程与 SDK 必须在纯 ASCII 路径，见 P0-4）：

```bat
:: 1. 资源编译
aapt2 compile --dir res -o out\res.zip
:: 2. 资源链接 + 生成 R.java（base.apk 只有 1471 B，正常）
aapt2 link -o out\base.apk -I android.jar --manifest AndroidManifest.xml ^
      -R out\res.zip --java out\gen --auto-add-overlay
:: 3. 编译 Java —— 只能用 -source 8 -target 8 -bootclasspath
::    --release 8 + -bootclasspath 会报"选项 --boot-class-path 不能与 --release 一起使用"
::    只给 -classpath android.jar 会报"未与 -source 8 一起指定引导类路径"
javac -source 8 -target 8 -bootclasspath android.jar -d out\classes src\*.java out\gen\**\R.java
:: 4. dex（必须 --lib android.jar --min-api 24）
d8.bat --lib android.jar --min-api 24 --output out out\classes\**\*.class
:: 5. 把 classes.dex 塞进 base.apk
jar uf out\unsigned.apk -C out classes.dex
:: 6. 对齐
zipalign -f -p 4 out\unsigned.apk out\aligned.apk
:: 7. 自签（一次生成，长期复用，保证升级覆盖安装）
keytool -genkeypair -keystore zanao.jks -storepass <pass> -keypass <pass> ^
        -alias zanao -dname "CN=ZanaoHot,O=Local,C=CN" -keyalg RSA -keysize 2048 -validity 3650
apksigner sign --ks zanao.jks --ks-pass pass:<pass> --key-pass pass:<pass> ^
        --out zanao_hot_1.0.apk out\aligned.apk
:: 8. 验证
apksigner verify --print-certs zanao_hot_1.0.apk
```

实测产物：`base.apk 1471 B → unsigned 2243 B → signed 8597 B`，`apksigner verify` 输出证书 DN/SHA-256/SHA-1/MD5 全通过。

已知无害噪声（写进文档免得被当成错误）：
- `javac` 报「源值 8 已过时 / 目标值 8 已过时」→ 加 `-Xlint:-options` 可静音
- `apksigner` 在 JDK 25 下报 `WARNING: java.lang.System::loadLibrary ... restricted method` → 不影响签名结果

AndroidManifest 必须补的两条：
```xml
<uses-permission android:name="android.permission.INTERNET"/>
<application android:usesCleartextTraffic="true" ...>   <!-- P0-1 的 HTTP 明文 -->
```

---

## 六、修订清单（v1.3 要改的地方，按文档行号）

| # | 行 | 现状 | 改成 |
|---|---|---|---|
| 1 | 27 | BASE `https://` | `http://` + manifest 开 `usesCleartextTraffic` |
| 2 | 27 | 响应统一 data 为 `{...}` | data 可能是 `[]`，先判类型再取 |
| 3 | 30 | `count=50&type=3` | `count=10`（≥11 回落 3）；**type 必须非空且 ≠1**（否则退化成 8 字段） |
| 4 | 30 | 「实际同时段 3 条左右」 | 删。默认 3 条是缺省值，显式 count=10 拿满 10 |
| 5 | 31 | `share_num` / `user_name` / `pt_time INTEGER` | 删 share_num；user_name→nickname；pt_time 是 str |
| 6 | 32 | `sign=<t_sign>` 必需 | 可省；comment 与 info 解耦 |
| 7 | 32 | `avatar_url` | `headimgurl`（已带 @!sm_w100_h100） |
| 8 | 32 | 评论含 `img_paths` | 再加 `img_list`（现成 raw_url，优先用） |
| 9 | 32 | 「三帖验证 41/37/21」 | 换新数：108/59/33/23/44/105 全部 = 一级+楼中楼 |
| 10 | 33 | 「缺一可能 20/10」 | 8 个必需（含 Version）+ 4 个可缺；Version 缺失=静默空列表 |
| 11 | 43 | errno 表 | 补 30=未知社区（alias 大小写敏感）；补 alias 合法但非本校→errno 0 空列表 |
| 12 | 47 | 「5 步」后列 6 条 | 改「6 步」 |
| 13 | 75 | `hot_val INTEGER` | str；`hot_text` 是 view_count 的紧凑格式 |
| 14 | 76 | `comment_count` / `like_count` | `c_count`（含已删楼）/ `l_count`（是点赞数） |
| 15 | 85-87 | `user_name` / `share_num` / `pt_time INTEGER` / `user_level INTEGER` | nickname / 删 / TEXT / TEXT |
| 16 | 93-96 | `avatar_local` 来源 `avatar_url` | 来源 `headimgurl` |
| 17 | 90-98 | comments 表缺列 | 加 `is_deleted`、`uid`、`root_comment_id` |
| 18 | 109 | 图片下载规则 | 拼 `http://b1.cdn.zanao.com/` + path + **`@!common`**；头像已带后缀不再拼 |
| 19 | 110-111 | 扩展名硬编码 `.jpg` | 按 Content-Type / 原后缀 |
| 20 | 112 | 「楼中楼同规则图片本地化」 | 删——楼中楼无图字段 |
| 21 | 118 | 「在榜 3 帖」示例 | 10 帖；并注明热榜只有 top 10、无分页 |
| 22 | 144 | `zc.py` | `direct_client.py` / `sign_final5.py` |
| 23 | 150 | Kotlin + okhttp + coroutines | 纯 Java + HttpURLConnection + 线程，零依赖 |
| 24 | 151 | 「本机手搓 APK」 | 补：ASCII 路径、javac `-source 8 -target 8 -bootclasspath`、d8 `--lib --min-api`、下载 URL |
| 25 | 166 | hot.db「threads 34 帖」 | 4 行；并注明电脑端 comments 是每帖一行 JSON（App 逐条是升级） |
| 26 | 167 | `libAesNew.so` | `libAesNew_x64.so` |
| 27 | §7.1 | 「榜面不变」判据未写明 | 用 **thread_id 序列**比，不能用 hot_val（hot_val 几分钟内 495→501→503 一直变） |

新增建议章节：
- **§2.1 静默失败清单**（5 种 errno 0 的假成功：Version 缺失空列表、alias 非本校空列表、`type` 省略/`=1` 退 8 字段、count≥11 回落 3 条、detail 空对象；外加 errno 6/10/20/30/3 的真失败表）
- **§2.2 最小请求集**（8 个 header + `count=10&type=3` + `id`，三条请求跑通全部数据）
- **§9.1 构建脚本全文**（上面那段 bat，直接可用）
- **§11 验收用例**（改完必跑的 11 条：签名 match、三接口 errno 0、count=10 得 10 条、type 省略退 8 字段的检测、detail 空判定、评论 total=一级+楼中楼、图片 @!common 200、头像直下 200、alias 大小写、Version 缺失空列表、apksigner verify 通过）

---

## 七、结论

- **签名算法 §3**：正确，可原样移植（我照文档实现，直连 errno 0）。
- **三级链 §2**：逻辑对，参数与 header 分级错（5 个 P0 全在这节）。
- **数据模型 §5**：设计思路对，字段名/类型与 API 有 8 处对不上，另有 4 个关键列缺失。
- **UI §6 / 定时 §7 / 边界 §7.1**：成立，只需把"3 帖"改"10 帖"、补 is_deleted 的展示规则、把"榜面不变"的判定从 hot_val 改成 thread_id 序列。
- **打包 §9**：Kotlin 路线不可行，Java 路线已实测跑通（含 8 个坑的解法）。
- **§8 验证用例**：一处失效引用（zc.py）。

按上表 27 条改完即为 v1.3，可以直接开工。**不修订就开工的话，第一天会死在 https 和 count=50 上，第三天会死在 aapt2 中文路径上，第五天会发现头像和图片一张都没存下来，第一周会发现存档里全是只有标题和浏览数的空壳。**
