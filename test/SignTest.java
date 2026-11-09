import com.zanao.hot.Sign;
import java.util.TreeMap;

/** 桌面 JVM 单测：11 条签名用例，期望值与 （工作目录）\sign_final5.py 完全一致 */
public class SignTest {

    private static int pass = 0;
    private static int fail = 0;

    private static TreeMap<String, String> m(String... kv) {
        TreeMap<String, String> t = new TreeMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            t.put(kv[i], kv[i + 1]);
        }
        return t;
    }

    private static void check(String label, TreeMap<String, String> p, String expect, String expectK1, String expectK2) {
        String[] mid = Sign.intermediates(p);
        String got = Sign.make(p);
        boolean ok = got.equals(expect);
        if (ok) pass++; else fail++;
        System.out.println((ok ? "OK  " : "FAIL") + " " + label);
        System.out.println("     sign=" + got + (ok ? "" : "  expect=" + expect));
        System.out.println("     K1=" + mid[0] + (mid[0].equals(expectK1) ? "" : "  expect=" + expectK1));
        System.out.println("     K2=" + mid[1] + (mid[1].equals(expectK2) ? "" : "  expect=" + expectK2));
    }

    public static void main(String[] a) {
        check("{}", m(), "C5387D25B04566FD5F1519097D8EEFB5", "g-ebgTXLgT5=", "nGLLxZi6joLNh2hw");
        check("{a=1}", m("a", "1"), "3910C853C08E1CFBD72BA3FD992C692E", "g-ebgTXLgT5=", "nGLLxZi6joLNh2hw");
        check("{a=1,b=2}", m("a", "1", "b", "2"), "65430EF7FD509BC24A178BB2A6A8EE42", "g-ebgTXLgT5=", "nGLLxZi6joLNh2hw");
        check("{rand=1,ts=2}", m("randnum__", "1", "timestamp__", "2"), "C618B0961E74B9C367740C36D78F3C60", "Apd=", "pKOdcg==");
        check("{rand=X,ts=Y}", m("randnum__", "X", "timestamp__", "Y"), "4FC09C0092A0CAF34365BDA95EFF2D7E", "PIN=", "hH6Cow==");
        check("{rand=1 only}", m("randnum__", "1"), "DEE919FF15E1EAC437EDB91B5A25BD0A", "APXLgT5=", "p4OMhZuFbqI=");
        check("{ts=2 only}", m("timestamp__", "2"), "622897801E4512830C3D84935E5610E5", "g-ebgud=", "npPJlpuoyaE=");
        check("{key=zzz 跳过}", m("key", "zzz"), "C5387D25B04566FD5F1519097D8EEFB5", "g-ebgTXLgT5=", "nGLLxZi6joLNh2hw");
        check("长串 10+10", m("randnum__", "abcdefghij", "timestamp__", "klmnopqrst"),
                "9D4E828940CDD6D1447B62A86AD88549", "EPDK0TeO0RnfqO7bgPX6UlIBUVC=", "fYOpe2eJy4KWhtDMp7NpxJmGjZmLoH91iYZ4cw==");
        check("长串 20+10", m("randnum__", "12345678901234567890", "timestamp__", "ABCDEFGHIJ"),
                "9676E9F29C2BE21F1EAD693441074C06", "ApdFQukRQFYXAuiBAFCLQKUW9pyyCNQixk0l2ijs", "ptWad4mso4m2eIu8pdrLpaKpd32Jf42KndOr3HaAiZ3d0GadaqCiqg==");
        check("{a=x,rand=5,ts=6}", m("a", "x", "randnum__", "5", "timestamp__", "6"),
                "83FCD2F2D9E72B60A7DD9601D628B4AC", "QpE=", "gqh+dA==");

        System.out.println();
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail > 0) System.exit(1);
    }
}
