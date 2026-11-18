package io.github.cherry1jay.aicallagent;

import android.app.AndroidAppHelper;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 超级小爱 7.13.35.0012 · AI 通话接管（完整功能版）
 *
 * 真机验证过的链路（Redmi K50 Ultra / HyperOS / 小爱 7.13.35.0012）：
 *   NlpManager.doNlp(query, type)        query=对方 ASR 文本；auto_answer/user_custom_query 为真实对话
 *     -> 云端 NLP -> 回复文本
 *   TtsManager.buildTtsEvent(text)       text=小爱准备播报的回复 ← 替换这里
 *
 * 号码来源：InCallAiServiceImpl.h()（public static）返回当前 android.telecom.Call
 *   Call.getDetails().getHandle().getSchemeSpecificPart() 取号码
 *
 * 策略：只替换“播报文本”，不碰状态机 / AudioTrack / 回调。
 *   doNlp（工作线程）里查来电类型 + 按类型调模型，把回复存进 pendingReply；
 *   buildTtsEvent 到来时替换参数。分流不命中 / 模型失败 / 无 Key —— 放行原生，通话零中断。
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "AICallAgent";
    private static final String TARGET_PKG = "com.xiaomi.aiasst.service";
    private static final String NLP_CLS = "com.xiaomi.aiasst.service.aicall.process.nlp.NlpManager";
    private static final String TTS_CLS = "com.xiaomi.aiasst.service.aicall.process.tts.TtsManager";
    private static final String INCALL_CLS = "com.xiaomi.aiasst.service.aicall.incall.InCallAiServiceImpl";
    /** 字幕源头：ProcessManage.notifyPresenterOnNlpBack(String,String,String,CallScreenCommonBean) */
    private static final String PM_CLS = "com.xiaomi.aiasst.service.aicall.process.ProcessManage";
    private static final String BEAN_CLS = "com.xiaomi.aiasst.service.aicall.model.CallScreenCommonBean";
    private static final String YELLOW_PAGE_CLS = "miui.yellowpage.YellowPageUtils";
    private static final String CONFIG_AUTHORITY = "com.xiaomi.aiasst.service.aicall.provider";
    private static final String KEY_OPENING_ACQ = "ai_custom_opening_acquaintance";
    private static final String KEY_OPENING_STR = "ai_custom_opening_stranger";

    /** 当前这轮的回复：字幕(setText)和语音(buildTtsEvent)共享，不立即清空，靠下一轮更新 */
    private static volatile String sPendingReply = null;
    private static volatile long sPendingAt = 0L;
    private static final long REPLY_TTL_MS = 15000L;

    private static final Object LOCK = new Object();
    private static final List<LlmClient.Msg> sHistory = new ArrayList<>();

    /** 当前来电信息 */
    private static volatile String sNumber = "";
    private static volatile String sCallerName = "";
    private static volatile boolean sManualTakeover = false;

    private static final Map<String, Cached> sCategoryCache = new ConcurrentHashMap<>();
    private static final long CATEGORY_TTL_MS = 10 * 60 * 1000L;

    private static final class Cached {
        final String category;
        final String tag;
        final long at;
        Cached(String category, String tag) {
            this.category = category;
            this.tag = tag;
            this.at = System.currentTimeMillis();
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) {
        if (!TARGET_PKG.equals(lp.packageName)) return;
        log("注入成功（完整版）pkg=" + lp.packageName + " proc=" + lp.processName);
        final ClassLoader cl = lp.classLoader;

        // 1) 抓 ASR 文本 + 查询号码，并生成模型回复
        try {
            Class<?> nlp = XposedHelpers.findClass(NLP_CLS, cl);
            XposedBridge.hookAllMethods(nlp, "doNlp", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try { onNlp(p, cl); }
                    catch (Throwable t) { log("doNlp 异常（放行原生）：" + t); }
                }
            });
            log("hook doNlp 成功");
        } catch (Throwable e) { log("hook doNlp 失败：" + e); }

        // 2) 替换播报文本（语音）
        try {
            Class<?> tts = XposedHelpers.findClass(TTS_CLS, cl);
            XposedBridge.hookAllMethods(tts, "buildTtsEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try { onBuildTts(p); }
                    catch (Throwable t) { log("buildTtsEvent 异常（放行原生）：" + t); }
                }
            });
            log("hook buildTtsEvent 成功");
        } catch (Throwable e) { log("hook buildTtsEvent 失败：" + e); }

        // 3) 替换字幕：在 NLP 结果回传界面的源头改 bean.text，让字幕与语音一致
        try {
            Class<?> pm = XposedHelpers.findClass(PM_CLS, cl);
            XposedBridge.hookAllMethods(pm, "notifyPresenterOnNlpBack", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam p) {
                    try { onNlpBack(p); }
                    catch (Throwable t) { log("字幕替换异常（放行原生）：" + t); }
                }
            });
            log("hook notifyPresenterOnNlpBack 成功");
        } catch (Throwable e) { log("hook notifyPresenterOnNlpBack 失败：" + e); }
    }

    /**
     * 只处理“对方真的在说话”的回合。
     *
     * auto_answer       = 对方 ASR 出文本，这是真正的对话，要接管。
     * user_custom_query = 开场白请求（Prologue.getStartText），不是对方说话：
     *                     系统拿它去云端生成开场白。若在这里接管，会顶掉原生开场白，
     *                     表现为“AI 抢在对方前面说、且第一句重复两三个字”。必顶放行。
     */
    private static boolean isRealDialog(String type, String query) {
        if (query == null) return false;
        if (query.startsWith("call_screen") || query.contains("@#") || query.startsWith("stop_")) return false;
        return "auto_answer".equals(type);
    }

    private void onNlp(XC_MethodHook.MethodHookParam p, ClassLoader cl) {
        String query = p.args.length > 0 && p.args[0] != null ? String.valueOf(p.args[0]) : "";
        String type = p.args.length > 1 && p.args[1] != null ? String.valueOf(p.args[1]) : "";

        // 一通新电话的第一句真实对话：抓号码 + 下发开场白配置（每通只做一次）
        if (isRealDialog(type, query) && (sNumber == null || sNumber.isEmpty())) {
            captureNumber(cl);
            sManualTakeover = false;
            Context c0 = AndroidAppHelper.currentApplication();
            if (c0 != null) {
                writeOpening(c0, Config.load(c0));
            }
        }
        if (!isRealDialog(type, query)) {
            if ("user_custom_query".equals(type)) {
                // 开场白请求：原样交给小爱云端生成原生开场白，不接管
                // 清掉上一轮可能还没被消费的替换值，否则会顶掉开场白
                synchronized (LOCK) { sPendingReply = null; }
                log("开场白请求（user_custom_query）→ 放行原生，原文：" + cut(query));
            }
            return;
        }
        // 新回合：先作废上一轮的替换值，避免残留串到本轮原生播报/字幕
        synchronized (LOCK) { sPendingReply = null; }
        log("收到对方话 [" + type + "] " + cut(query));

        Context ctx = AndroidAppHelper.currentApplication();
        if (ctx == null) { log("拿不到 Context，放行原生"); return; }
        Config cfg = Config.load(ctx);
        if (cfg == null || !cfg.enabled) { log("模块已关闭，放行原生"); return; }
        if (cfg.apiKey == null || cfg.apiKey.trim().isEmpty()) {
            log("未填 API Key，放行原生"); return;
        }

        // 来电分类（黄页标签 + 通讯录 + 短号兜底）
        String[] ct = resolveCategory(ctx, cl);
        String category = ct[0], tag = ct[1];
        boolean manualBypass = sManualTakeover && cfg.manualIgnoreCategory;
        if (!cfg.shouldHandle(category) && !manualBypass) {
            log("类型=" + Category.label(category) + "（" + tag + "）→ 走原生");
            logAsync(ctx, CallLogStore.TYPE_INFO,
                    "类型【" + Category.label(category) + "】→ 原生" + (tag == null ? "" : "(" + tag + ")"));
            return;
        }

        logAsync(ctx, CallLogStore.TYPE_REMOTE, query);
        String prompt = cfg.promptFor(category);
        cfg.timeoutSec = Math.min(cfg.timeoutSec, 3); // 电话里不宜久等
        try {
            long t0 = System.currentTimeMillis();
            String reply = LlmClient.chat(cfg, snapshotHistory(), query, prompt);
            long cost = System.currentTimeMillis() - t0;
            if (reply == null || reply.trim().isEmpty()) {
                log("模型返回空（" + cost + "ms），放行原生");
                return;
            }
            String clean = stripQuotes(reply.trim());
            synchronized (LOCK) {
                sPendingReply = clean;
                sPendingAt = System.currentTimeMillis();
            }
            pushHistory(query, clean, cfg.maxHistory);
            log("[" + Category.label(category) + "] 模型回复（" + cost + "ms）：" + cut(clean));
            logAsync(ctx, CallLogStore.TYPE_LOCAL, clean + " ·" + cost + "ms · " + Category.label(category));
        } catch (Throwable t) {
            log("模型调用失败，放行原生：" + t);
            logAsync(ctx, CallLogStore.TYPE_ERROR, "模型失败：" + t);
        }
    }

    /** 语音播报文本替换（不清空，字幕侧也读同一个值） */
    private void onBuildTts(XC_MethodHook.MethodHookParam p) {
        if (p.args.length == 0 || !(p.args[0] instanceof String)) return;
        String original = (String) p.args[0];
        String ready = currentReply();
        if (ready != null) {
            p.args[0] = ready;
            log("已替换播报文本：" + cut(original) + " -> " + cut(ready));
        }
    }

    /**
     * NLP 结果回传界面：把 bean.text（字幕/记录文本）改成模型回复。
     * 签名 (String, String, String, CallScreenCommonBean)，bean 在第 4 个参数。
     */
    private void onNlpBack(XC_MethodHook.MethodHookParam p) {
        if (p.args.length < 4) return;
        Object bean = p.args[3];
        if (bean == null) return;
        String ready = currentReply();
        if (ready == null) return;
        try {
            String original = null;
            try { original = (String) XposedHelpers.callMethod(bean, "getText"); } catch (Throwable ignored) {}
            XposedHelpers.setObjectField(bean, "text", ready);
            log("已替换字幕文本：" + cut(original) + " -> " + cut(ready));
        } catch (Throwable t) {
            log("字幕字段写入失败：" + t);
        }
    }

    /** 取当前有效回复（TTL 内）。不清空——字幕和语音都要读；靠下一轮 onNlp 开头置 null 作废旧值 */
    private static String currentReply() {
        synchronized (LOCK) {
            if (sPendingReply != null && System.currentTimeMillis() - sPendingAt < REPLY_TTL_MS) {
                return sPendingReply;
            }
        }
        return null;
    }

    /** 从 InCallAiServiceImpl.h() 拿当前 Call 的号码 */
    private void captureNumber(ClassLoader cl) {
        try {
            Class<?> incall = XposedHelpers.findClass(INCALL_CLS, cl);
            Object call = XposedHelpers.callStaticMethod(incall, "h");
            if (call == null) { log("当前无 Call，号码留空"); return; }
            Object details = XposedHelpers.callMethod(call, "getDetails");
            Object handle = XposedHelpers.callMethod(details, "getHandle");
            String number = "";
            if (handle instanceof Uri) {
                String ssp = ((Uri) handle).getSchemeSpecificPart();
                if (ssp != null) number = ssp;
            }
            String name = "";
            try {
                Object n = XposedHelpers.callMethod(details, "getCallerDisplayName");
                if (n != null) name = String.valueOf(n);
            } catch (Throwable ignored) {}
            sNumber = number == null ? "" : number;
            sCallerName = name;
            sCategoryCache.clear();
            log("来电号码：" + sNumber + " / " + name);
        } catch (Throwable t) {
            log("取号码失败（分类按陌生号处理）：" + t);
            sNumber = "";
            sCallerName = "";
        }
    }

    private String[] resolveCategory(Context ctx, ClassLoader cl) {
        String number = sNumber == null ? "" : sNumber;
        Cached c = sCategoryCache.get(number);
        if (c != null && System.currentTimeMillis() - c.at < CATEGORY_TTL_MS) {
            return new String[]{c.category, c.tag};
        }
        String tag = null;
        try { tag = queryYellowPageTag(ctx, cl, number); }
        catch (Throwable t) { log("黄页查询异常：" + t); }

        boolean isContact = sCallerName != null && !sCallerName.isEmpty()
                && !sCallerName.equals(number) && !isPhoneLike(sCallerName);
        if (!isContact) {
            String cn = lookupContactName(ctx, number);
            if (cn != null && !cn.isEmpty()) { sCallerName = cn; isContact = true; }
        }
        String category = Category.classify(tag, isContact, number);
        log("归类：" + Category.label(category) + "（联系人=" + isContact + "，黄页=" + tag + "）");
        sCategoryCache.put(number, new Cached(category, tag));
        return new String[]{category, tag};
    }

    private String lookupContactName(Context ctx, String number) {
        if (number == null || number.isEmpty()) return null;
        Cursor c = null;
        try {
            Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
            c = ctx.getContentResolver().query(uri,
                    new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Throwable t) {
            log("通讯录查询失败：" + t);
        } finally {
            if (c != null) try { c.close(); } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 黄页标签：优先用目标进程 classLoader，兜底用系统 ClassLoader */
    private String queryYellowPageTag(Context ctx, ClassLoader cl, String number) throws Exception {
        if (number == null || number.isEmpty()) return null;
        Class<?> yp = null;
        try { yp = Class.forName(YELLOW_PAGE_CLS, false, cl); }
        catch (Throwable t) { yp = Class.forName(YELLOW_PAGE_CLS); }
        for (Method m : yp.getMethods()) {
            if (!"getPhoneInfo".equals(m.getName())) continue;
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 3 && ps[0].isAssignableFrom(ctx.getClass()) && ps[1] == String.class) {
                Object third = (ps[2] == boolean.class || ps[2] == Boolean.class) ? Boolean.TRUE : (Object) 1;
                Object phone = m.invoke(null, ctx, number, third);
                if (phone != null) {
                    Object tag = XposedHelpers.callMethod(phone, "getTag");
                    return tag == null ? null : String.valueOf(tag);
                }
                return null;
            }
        }
        return null;
    }

    private static void writeOpening(Context ctx, Config cfg) {
        if (cfg == null) return;
        try {
            String acq = cfg.openingAcquaintance == null ? "" : cfg.openingAcquaintance.trim();
            String str = cfg.openingStranger == null ? "" : cfg.openingStranger.trim();
            if (!acq.isEmpty()) putConfig(ctx, KEY_OPENING_ACQ, acq);
            if (!str.isEmpty()) putConfig(ctx, KEY_OPENING_STR, str);
        } catch (Throwable t) { log("写开场白失败：" + t); }
    }

    private static boolean putConfig(Context ctx, String key, String value) {
        try {
            Uri uri = Uri.parse("content://" + CONFIG_AUTHORITY + "/settings/" + key);
            ContentValues cv = new ContentValues();
            cv.put("key", key);
            cv.put("value", value);
            return ctx.getContentResolver().insert(uri, cv) != null;
        } catch (Throwable t) { log("写配置失败 " + key + "：" + t); return false; }
    }

    private static void logAsync(final Context ctx, final String type, final String text) {
        if (ctx == null) return;
        new Thread(() -> {
            try { LogProvider.appendFromHook(ctx, type, text); }
            catch (Throwable ignored) {}
        }, "aicall-log").start();
    }

    private static List<LlmClient.Msg> snapshotHistory() {
        synchronized (LOCK) { return new ArrayList<>(sHistory); }
    }

    private static void pushHistory(String user, String assistant, int maxKeep) {
        synchronized (LOCK) {
            sHistory.add(new LlmClient.Msg("user", user));
            sHistory.add(new LlmClient.Msg("assistant", assistant));
            while (sHistory.size() > Math.max(2, maxKeep)) sHistory.remove(0);
        }
    }

    private static String stripQuotes(String s) {
        if (s == null) return s;
        String r = s.trim();
        if (r.length() >= 2) {
            char a = r.charAt(0), b = r.charAt(r.length() - 1);
            if ((a == '"' && b == '"') || (a == '“' && b == '”')
                    || (a == '\'' && b == '\'') || (a == '「' && b == '」')) {
                r = r.substring(1, r.length() - 1).trim();
            }
        }
        return r;
    }

    private static boolean isPhoneLike(String s) {
        return s != null && s.matches("[+0-9\\-\\s()]{5,}");
    }

    private static String cut(String s) {
        if (s == null) return "<null>";
        return s.length() > 80 ? s.substring(0, 80) + "…(" + s.length() + "字)" : s;
    }

    private static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }
}
