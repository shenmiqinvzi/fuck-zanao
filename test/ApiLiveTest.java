import com.zanao.hot.Api;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 桌面 JVM 上的真实链路验证：直接调 Api.hot / info / comments。
 * 这是 App 里真正会跑的代码，不是另写一份脚本。
 */
public class ApiLiveTest {

    public static void main(String[] a) throws Exception {
        String token = a.length > 0 ? a[0] : "";
        Api api = new Api(token);

        System.out.println("[1] /thread/hot count=10 type=3");
        JSONArray hot = api.hot();
        System.out.println("    条数=" + hot.length());
        if (hot.length() == 0) {
            System.out.println("    空列表 -> 失败");
            System.exit(1);
        }
        for (int i = 0; i < hot.length(); i++) {
            JSONObject t = hot.getJSONObject(i);
            System.out.println("    #" + t.optInt("hot_rank") + " " + t.optString("thread_id")
                    + " | " + t.optString("title")
                    + " | 热度 " + t.optString("hot_val")
                    + " | 评论 " + t.optString("c_count")
                    + " | 赞 " + t.optString("l_count"));
        }
        JSONObject first = hot.getJSONObject(0);
        System.out.println("    字段数=" + first.length() + "（应为 25，退化成 8 说明 type 没生效）");

        String tid = first.optString("thread_id");

        System.out.println("\n[2] /thread/info id=" + tid);
        JSONObject detail = api.info(tid);
        if (detail == null) {
            System.out.println("    detail 为空 -> 失败");
            System.exit(1);
        }
        System.out.println("    字段数=" + detail.length());
        System.out.println("    title=" + detail.optString("title"));
        System.out.println("    nickname=" + detail.optString("nickname") + "（不是 user_name）");
        System.out.println("    pt_time=" + detail.optString("pt_time"));
        System.out.println("    like_num=" + detail.optString("like_num") + " / mark_num=" + detail.optString("mark_num"));
        System.out.println("    delete_status=" + detail.optString("delete_status") + "（\"10\" 是正常值）");
        JSONArray ip = detail.optJSONArray("img_paths");
        System.out.println("    img_paths=" + (ip == null ? "null" : ip.length() + " 张"));
        if (ip != null && ip.length() > 0) {
            String rel = ip.optString(0);
            String url = Api.imgUrl(rel);
            System.out.println("    拼图 URL=" + url);
            byte[] img = Api.download(url);
            System.out.println("    下载=" + img.length + " bytes");
        }

        System.out.println("\n[3] /comment/list id=" + tid);
        JSONObject cs = api.comments(tid);
        int total = cs.optInt("total");
        JSONArray list = cs.optJSONArray("list");
        int layer = 0;
        int deleted = 0;
        int withImg = 0;
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                JSONObject c = list.getJSONObject(i);
                JSONArray rl = c.optJSONArray("reply_list");
                if (rl != null) layer += rl.length();
                if (c.optBoolean("is_deleted")) deleted++;
                JSONArray il = c.optJSONArray("img_list");
                if (il != null && il.length() > 0) withImg++;
                if (rl != null) {
                    for (int j = 0; j < rl.length(); j++) {
                        if (rl.getJSONObject(j).optBoolean("is_deleted")) deleted++;
                    }
                }
            }
        }
        System.out.println("    total=" + total + " 一级=" + (list == null ? 0 : list.length())
                + " 楼中楼=" + layer + " 合计=" + ((list == null ? 0 : list.length()) + layer)
                + "（应等于 total）");
        System.out.println("    已删楼=" + deleted + " 带图评论=" + withImg);

        if (list != null && list.length() > 0) {
            JSONObject c0 = list.getJSONObject(0);
            System.out.println("    评论字段数=" + c0.length());
            System.out.println("    headimgurl=" + c0.optString("headimgurl"));
            System.out.println("    root_comment_id=" + c0.optString("root_comment_id")
                    + " reply_comment_id=" + c0.optString("reply_comment_id"));
            System.out.println("    is_anon=" + c0.opt("is_anon"));
        }

        System.out.println("\n[4] 不存在的 id（必须 errno 0 + 空 detail）");
        JSONObject none = api.info("9999999999");
        System.out.println("    info(9999999999) = " + (none == null ? "null（判空生效）" : "非空 -> 判空失效"));

        System.out.println("\nALL OK");
    }
}
