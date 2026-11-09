# 赞奥热榜 App 开发设计文档

> 版本 1.2（2026-10-31；v1.1 评论逐条存档+评论图/头像本地化；v1.2 §7.1 边界行为定案：榜面不变仅涨评论 / 掉榜又回榜）
> 目标：独立安卓 App「赞奥热榜」——手机端自动采集并永久存档赞奥校园集市（郑州大学站）热榜内容，平台下架不影响本地数据。
> 本文档自足：全部签名算法、接口、数据模型、UI、定时、打包细节均已定案，新对话可直接按此开发，无需追问历史。

---

## 0. 项目背景一句话

赞奥校园集市（H5 站 c.zanao.com，API api.app.zanao.com，郑州大学站 cid=zzu）的「热榜」帖子会被下架；本项目 = 把每期热榜及在榜帖子的正文/图片/全量评论抓进手机本地 SQLite 永久存档，并提供实时热榜浏览。签名算法（makeSign）已逆向定案并经 11/11 oracle + 20/20 真实流量双重验证。

## 1. 功能清单（定稿）

1. **实时热榜页**：/thread/hot 实时拉取，展示 rank、标题、hot_val、hot_text（如 6.1K）、view_count、comment_count；下拉刷新。
2. **帖子存档页**：点开榜单帖看正文+图片+全量评论（含楼中楼），App 本地 SQLite 永久存档；**进过榜就永久保留**，平台下架不影响。
3. **历史期数页**：每轮榜单快照按时间排列，可回看"某时刻热榜长什么样"。
4. **自动爬取**：自定义间隔 15m/30m/1h/3h/仅手动（默认 30m），WorkManager 周期任务；每轮 hot → 新帖入库 → 快照 → 可选新帖通知。
5. **Token 设置页**：token 手动粘贴 + 可随时更新（token 失效约 1-2 个月一次，由电脑侧微信授权获取新 token 粘贴进来）；alias 固定 zzu。

边界（如实告知）：
- 国产 ROM 杀后台 → 首启引导「自启动 + 电池白名单」+ 前台常驻通知「热榜监控运行中」缓解。
- token 失效 → App 请求遇 errno 20 时弹提醒引导更新。

## 2. 服务端接口定案（已验证，直接用）

- BASE：`https://api.app.zanao.com`，全部 HTTPS GET/POST，响应统一 `{"errno":0,"errmsg":"","data":{...}}`。
- 登录态请求必须带 header `X-Sc-Alias: zzu`（游客态为空串）——**errno 20 头号根因，勿漏**。
- 三级采集链（每轮爬取顺序）：
  1. `GET /thread/hot?count=50&type=3` → 在榜列表（实际同时段 3 条左右）。data.list[] 含 thread_id/title/hot_rank/hot_val/hot_text/view_show 等。
  2. `POST /thread/info` body=`from=hot&id=<thread_id>` → data.t_sign + data.detail（60+ 字段：content、img_paths、contact_qq（脱敏）、like_num/mark_num/share_num、pt_time、short_url…）。
  3. `GET /comment/list?with_hongbao=0&sign=<t_sign>&id=<thread_id>` → **一次返回全部评论**：一级 list + 楼中楼内嵌 reply_list；total == 一级+楼中楼总数（已三帖验证 41/37/21），**无分页参数**（page/offset/limit 均无效）。评论对象含 content/img_paths（内嵌图）/avatar_url/like_num/user_level/post_time 等，逐条入库并本地化图片。
- 请求 header 全套（缺一可能 20/10）：
  - `X-Sc-Token: <token>`（URL 编码态，如 `cG8r...dz0%3D`，mitm 抓包原样即可）
  - `X-Sc-Alias: zzu`
  - `X-Sc-Nd`（20 位随机数字）、`X-Sc-Td`（unix 秒）
  - `X-Sc-Ah`（= makeSign 结果）
  - `X-Sc-Hb-V: 210`、`X-Sc-Nt-V: 310`、`X-Sc-Client: app`、`X-Sc-Platform: Android`
  - `X-Sc-Device: 00000000-53ef-1f0d-ffff-ffffef05ac4a-345076e6d5406f4f`（沿用即可）
  - `X-Sc-Version: 3.1.0`
  - `User-Agent: okhttp/4.10.0`
- GET 参数拼进签名 map；POST 参数进签名 map。
- errno：0 成功 / 3 路径不存在 / 10 参数非法 / 20 需登录（token 失效或缺 alias）/ 27/28/30 账号关闭/注销/换社区。

## 3. makeSign 签名算法（Java 移植目标，逐行定案）

header `X-Sc-Ah` 的生成，5 步：

1. **body 排序串**：请求参数 map（含 `randnum__`、`timestamp__`，POST 含 body 参数）按 key 升序（TreeMap）拼 `"k=v"` 用 `&` 连接；**value == "key" 字面量的 entry 跳过**。
2. **concat** = `map.getOrDefault("randnum__","null") + map.getOrDefault("timestamp__","null")`（无则字面量 "null"）。
3. **K1** = codebook_base64(concat)：自定义码本 `HySuiITldDszAQ9ZCx2pkePtE0qgUr_JYn1KNjO-afc4b7M658BFGLRVWXhmovw3`（64 字符，索引即值）实现标准 b64 变换，**无 padding**。
4. **H1** = md5_hex_lower(K1)（32 位小写）。
5. **K2pre[i] = (K1_ascii[i] + H1_ascii[i%32]) & 0xFF**（i 遍历 K1 长度），**K2 = 标准 base64(K2pre)**（带 padding）。
6. **sign = md5_hex_upper( body + "&secretkey=" + K2 )**（32 位大写）。

实现要点：全部 ASCII 字节运算；码本表硬编码常量；`java.security.MessageDigest.getInstance("MD5")` 即可；验证方式见 §8 用例。

- token 与 alias **不参与** makeSign（本地重算与真实 X-Sc-Ah 完全 MATCH 已验证）。

## 4. Token 获取与更新（运维约定）

- 电脑侧已有 Python 全套（`（工作目录）\`）：direct_client.py / zanao_hot.py / zanao_crawler.py / zanao_hot_loop.py / show_comments.py；token 现存 `（工作目录）\token.txt`。
- 电脑每小时任务（Windows 计划程序 ZanaoHotCrawler）继续跑，与 App 互为独立采集腿，互不依赖。
- token 失效时：电脑 mitm+雷电模拟器走微信授权（splash「微信登录」按钮 → 授权 → 选择学校）拿新 token → 用户粘贴进 App。
- App 首启：设置页粘贴 token（默认 alias=zzu），即可开始采集。

## 5. 数据模型（App 本地 SQLite `zanao.db`）

```sql
-- 在榜快照（每轮每帖一行；raw_json 存当期完整榜面条目，可还原整期榜单）
CREATE TABLE rank_snapshot(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER NOT NULL,            -- 抓取轮次时间
  thread_id TEXT NOT NULL,
  hot_rank INTEGER, hot_val INTEGER, hot_text TEXT,
  view_count INTEGER, comment_count INTEGER, like_count INTEGER,
  raw_json TEXT                   -- 当期完整 JSON（含 title 等）
);
-- 帖子存档（主键 thread_id，重复帖覆盖更新）
CREATE TABLE threads(
  thread_id TEXT PRIMARY KEY,
  token TEXT,                     -- 首次入库时的采集 token（历史留痕，可空）
  title TEXT, content TEXT,
  img_paths TEXT,                 -- JSON 数组；图片本地化后改存本地路径
  cate_name TEXT, user_name TEXT, user_level INTEGER, is_anon INTEGER,
  contact_qq TEXT, like_num INTEGER, mark_num INTEGER, share_num INTEGER,
  pt_time INTEGER, first_ts INTEGER, last_ts INTEGER, raw_json TEXT
);
-- 评论（逐条存档，全量覆盖更新；楼中楼 parent_reply_id 关联）
CREATE TABLE comments(
  comment_id TEXT PRIMARY KEY,
  thread_id TEXT NOT NULL, parent_reply_id TEXT,
  content TEXT, img_paths TEXT,           -- 评论内嵌图：本地化后改存本地路径
  avatar_local TEXT,                       -- 评论者头像本地路径（匿名则空）
  like_num INTEGER,
  user_name TEXT, user_level INTEGER, is_anon INTEGER,
  post_time INTEGER, first_ts INTEGER, raw_json TEXT
);
-- 评论增量快照
CREATE TABLE comment_snap(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  ts INTEGER, thread_id TEXT NOT NULL,
  total INTEGER, new_ids TEXT     -- 本轮新评论 id 的 JSON 数组
);
```

增量逻辑（照抄电脑端定案）：每轮全量重拉评论（/comment/list 一次返回全部，成本极低），写库前与已有 comment_id 差集算 new_ids 记入 comment_snap；评论行全量覆盖更新（评论编辑/点赞数变化跟随最新）。

图片本地化（**必做，评论级**）：帖子图 `img_paths`、**每条评论的内嵌图 `comment.img_paths`、评论者头像 `avatar_url`** 逐张下载到 App 私有目录：
- 帖子图 → `files/hot_images/<thread_id>/p_<n>.jpg`，threads.img_paths 改存本地路径
- 评论图/头像 → `files/hot_images/<thread_id>/<comment_id>/c_<n>.jpg`、`avatar.jpg`，comments.img_paths/avatar_local 改存本地路径
- 楼中楼同规则；下载失败保留原 CDN URL 重试下轮；raw_json 始终保留原始 URL 双保险——评论逐个保存 = 文本+图+头像全套落地，平台删楼也无损。

## 6. UI 结构（单 Activity + 3 Fragment 或 Compose 三页）

- **热榜页**：RecyclerView 列表（rank 徽标 / 标题 / hot_val / 热度文本 / 浏览·评论数），下拉刷新（SwipeRefreshLayout），点击进存档页。
- **存档详情页**：正文（含图片点击放大可选）+ 评论列表（一级评论 + 楼中楼缩进折叠"展开回复"——纯本地渲染，无需网络）。
- **历史期数页**：按轮次 ts 倒序列出每期（"10-30 21:00 第 N 期 · 在榜 3 帖"），点开看该期 raw_json 还原的榜面。
- **设置页**：token 输入框（粘贴+保存到 SharedPreferences）、alias（默认 zzu 固定）、间隔档位选择、新帖通知开关、"立即爬一轮"按钮、引导开启自启动/电池白名单。
- 风格随意但保持暗色可读；无外部 CDN 依赖（内网/离线不挂）。

## 7. 定时采集（WorkManager）

- `PeriodicWorkRequest` 间隔=设置档位（默认 30m，注意 WorkManager 最小周期 15m），约束 NetworkType.CONNECTED。
- Worker：读 token/alias → 爬一轮（§2 三级链）→ 写库（§5）→ 更新前台通知"上轮 21:00 · 在榜 3 · 新帖 1"。
- 前台服务常驻通知（可选档）：防 ROM 杀后台，配合电池白名单引导。
- errno 20 → 通知"token 失效，请更新"。

### 7.1 边界行为定案（两种典型场景）

**场景 A：半小时后榜面没变，只有帖子涨了新评论**
- 每轮动作固定：拉 /thread/hot → 逐帖写 rank_snapshot（新一行快照，哪怕内容与上轮相同）→ /thread/info 全量覆盖更新 threads → /comment/list 全量重拉 → comment_id 差集算出新增评论 → 逐条入库（含图/头像本地化）→ comment_snap 记 (ts, thread_id, total, new_ids)。
- 结果：榜面没变 → 不弹"新上榜"通知；评论涨了 → **新增评论照常逐条入库 + 本地化**，comment_snap.new_ids 记录（如 `["c_881","c_882"]`），可选通知"文院事件帖 +2 评论"。threads.last_ts 更新，历史评论行不动（raw_json 覆盖为最新）。

**场景 B：帖子掉榜半小时后又回榜**
- 掉榜期间：rank_snapshot 不再有它的行（榜单快照如实记录"当期不在榜"），但 threads/comments 表数据**原样保留，不删不改**——存档永不因掉榜失效。
- 回榜当轮：rank_snapshot 新增该帖行（新快照记录回榜时刻的 rank/val）→ /thread/info、/comment/list 照常全量拉 → 与上次离榜状态做正常增量比对（新评论照常入库）。**thread_id 是主键，回榜≠新帖**，threads.first_ts 保留首次上榜时间，不产生重复记录。
- 历史期数页回看：掉榜那期的榜面没有它（如实），回榜那期的榜面有它（如实），帖子详情始终可点开（数据一直在）。

一句话：**榜单快照如实记流水，帖子/评论存档只增不减**——这就是"保住每期热榜内容"的落地形态。

## 8. 签名验证用例（移植后必跑）

- 电脑端 Python 参考实现：`（工作目录）\zc.py`（make_sign）/ `sign_final5.py` / `verify_flows2.py`（20/20 真实流量全过）。
- 验证法：Java 实现对同一组 (map, randnum__, timestamp__) 输出与 Python 完全一致的 X-Sc-Ah；再用真实请求直连 api.app.zanao.com 得 errno 0 即通过。
- device_id 沿用 `00000000-53ef-1f0d-ffff-ffffef05ac4a-345076e6d5406f4f`；reg_id 用 `xNBWgHgHM8dJ4wu2ALucsBCKbLe/8xJq8BcxjFG4nCVBreewXb/M5WrfLBN6HUI4`（devicereg 场景才需要，App 纯读可以不带）。

## 9. 打包与交付

- minSdk 24 / targetSdk 34，Kotlin，依赖仅 okhttp + kotlinx-coroutines（或纯 HttpURLConnection+线程，零第三方更稳）。
- 本机手搓 APK：Android build-tools（aapt2/d8/zipalign/apksigner）脚本打包，产出 `zanao_hot_1.0.apk`；签名 key 自签（keystore 保留供后续升级同签名覆盖安装）。
- 交付物：apk + 源码目录 + 本文档。

## 10. 里程碑

1. **M1（半天）**：工程骨架 + makeSign Java 移植 + 三接口直连 errno 0。
2. **M2（1 天）**：UI 三页 + SQLite 存档 + WorkManager 定时 + 通知。
3. **M3（半天）**：图片本地化 + token 设置页 + 打包签名交付。

## 附：电脑侧资产索引（（工作目录）\）

- direct_client.py（直连+签名客户端，可当接口调试器）
- zanao_hot.py / zanao_hot_loop.py（热榜单轮/循环爬虫，hot.db）
- zanao_crawler.py / zanao_detail.py（全量列表/详情爬虫，threads_zzu.jsonl 1764 帖）
- show_comments.py（评论打印器）；token.txt（当前 token）；traffic3.flow（mitm 全量抓包）
- hot.db（threads 34 帖 / hot_snap / comment_snap / details / comments 表，电脑侧已运行的数据样本）
- makeSign 逆向全套：frida16_oracle.py / makeSign_oracle.js / verify_flows2.py / sign_final5.py；libAesNew.so 逆向笔记（x64 rva：makeSign@0x24980、digest@0x27720、b64core@0x26a60）
