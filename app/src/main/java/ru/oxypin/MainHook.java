package ru.oxypin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "OxyPin";
    private static final String SETTING_LOCK_TO_APP = "lock_to_app_enabled";
    private static final int LOCK_TASK_MODE_NONE = 0;
    private static final int LOCK_TASK_MODE_PINNED = 2;
    private volatile Handler mainHandler;
    private volatile Context cachedCtx;

    @Override
    public void handleLoadPackage(final LoadPackageParam lpp) {
        final String pkg = lpp.packageName;
        if ("android".equals(pkg)) {
            if (android.os.Process.myUid() != 1000) {
                return;
            }
            log("loaded into system_server, sdk=" + android.os.Build.VERSION.SDK_INT);
            final Thread waiter = new Thread(new Runnable() {
                @Override
                public void run() {
                    Looper looper = null;
                    for (int i = 0; i < 200 && looper == null; i++) {
                        looper = Looper.getMainLooper();
                        if (looper == null) {
                            try {
                                Thread.sleep(250);
                            } catch (InterruptedException ignored) {
                                return;
                            }
                        }
                    }
                    if (looper == null) {
                        log("ERROR: main looper never appeared");
                        return;
                    }
                    new Handler(looper).post(new Runnable() {
                        @Override
                        public void run() {
                            initSystemSide(0);
                        }
                    });
                }
            }, "OxyPin-init");
            waiter.setDaemon(true);
            waiter.start();
            return;
        }
        if ("com.android.systemui".equals(pkg) || "com.android.launcher".equals(pkg)) {
            try {
                initRecentsSide(lpp);
            } catch (Throwable ignored) {
            }
        }
    }

    private void initSystemSide(final int attempt) {
        final Context ctx = systemContext();
        if (ctx == null) {
            if (attempt < 60) {
                final Handler h = mainHandler();
                if (h != null) {
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            initSystemSide(attempt + 1);
                        }
                    }, 500);
                }
            } else {
                log("ERROR: system context unavailable after retries");
            }
            return;
        }
        synchronized (this) {
            cachedCtx = ctx;
        }
        int token = new java.util.Random().nextInt(0x7ffffff0) | 1;
        try {
            final Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", null);
            XposedHelpers.callStaticMethod(secure, "putInt", ctx.getContentResolver(), OxyPin.TOKEN_KEY, token);
        } catch (Throwable t) {
            logError("write token", t);
        }
        final IntentFilter f = new IntentFilter(OxyPin.ACTION_PIN);
        f.addAction(OxyPin.ACTION_UNPIN);
        try {
            ctx.registerReceiver(new CmdReceiver(token), f, 2);
            log("ready: tile commands armed");
        } catch (Throwable t) {
            logError("registerReceiver", t);
        }
    }

    private Context systemContext() {
        try {
            final Class<?> at = Class.forName("android.app.ActivityThread");
            final Object atObj = at.getMethod("currentActivityThread").invoke(null);
            if (atObj == null) {
                return null;
            }
            final Object c = at.getMethod("getSystemContext").invoke(atObj);
            return c instanceof Context ? (Context) c : null;
        } catch (Throwable t) {
            logError("systemContext", t);
            return null;
        }
    }

    private class CmdReceiver extends BroadcastReceiver {
        private final int token;

        CmdReceiver(int token) {
            this.token = token;
        }

        @Override
        public void onReceive(Context ctx, Intent intent) {
            try {
                final String action = intent.getAction();
                final int got = intent.getIntExtra(OxyPin.EXTRA_TOKEN, 0);
                if (got != token) {
                    log("command rejected: bad token");
                    return;
                }
                final Context c = cachedCtx != null ? cachedCtx : ctx;
                if (OxyPin.ACTION_PIN.equals(action)) {
                    int reqTaskId = intent.getIntExtra(OxyPin.EXTRA_TASK_ID, -1);
                    if (doPin(c, reqTaskId)) {
                        toast(c, tr(c, "Приложение закреплено", "App pinned"));
                    } else {
                        toast(c, tr(c, "OxyPin: закрепить не удалось, смотри логи", "OxyPin: failed to pin, see LSPosed logs"));
                    }
                } else if (OxyPin.ACTION_UNPIN.equals(action)) {
                    final int st = lockTaskState();
                    if (st == LOCK_TASK_MODE_NONE) {
                        toast(c, tr(c, "OxyPin: ничего не закреплено", "OxyPin: nothing is pinned"));
                        return;
                    }
                    if (doUnpin()) {
                        toast(c, tr(c, "Приложение откреплено", "App unpinned"));
                    } else {
                        toast(c, tr(c, "OxyPin: открепить не удалось, смотри логи", "OxyPin: failed to unpin, see LSPosed logs"));
                    }
                }
            } catch (Throwable t) {
                logError("onReceive", t);
            }
        }
    }

    private boolean doPin(Context ctx) {
        return doPin(ctx, -1);
    }

    private boolean doPin(Context ctx, int requestedTaskId) {
        try {
            final int state = lockTaskState();
            if (state != LOCK_TASK_MODE_NONE) {
                log("pin skipped: already locked/pinned, state=" + state);
                return false;
            }
            forceEnableLockToApp(ctx);
            final Object atms = atmsService();
            int taskId = requestedTaskId;
            if (taskId <= 0) {
                taskId = findForegroundTaskId(atms);
            }
            if (taskId <= 0) {
                log("no foreground task found");
                return false;
            }
            boolean ok = false;
            final Method m = findMethodByNeedle(atms, "startscreenpinning");
            if (m != null) {
                try {
                    invokeStartByArity(m, atms, taskId);
                    ok = true;
                } catch (Throwable t) {
                    logError("invoke " + m.getName(), unwrap(t));
                }
            }
            if (!ok) {
                try {
                    ok = pinViaLockTaskController(atms, taskId);
                } catch (Throwable t) {
                    logError("locktask pin", t);
                }
            }
            return ok;
        } catch (Throwable t) {
            logError("pin", t);
            return false;
        }
    }

    private boolean doUnpin() {
        try {
            final Object atms = atmsService();
            boolean done = false;
            final Method m = findMethodByNeedle(atms, "stopscreenpinning");
            if (m != null) {
                try {
                    invokeStopByArity(m, atms);
                    done = true;
                } catch (Throwable t) {
                    logError("invoke " + m.getName(), unwrap(t));
                }
            }
            if (!done) {
                try {
                    final Object ltc = findFieldOfTypeName(atms, "LockTaskController");
                    if (ltc != null) {
                        XposedHelpers.callMethod(ltc, "clearLockedTasks", "OxyPin");
                        done = true;
                    } else {
                        log("no LockTaskController field for unpin");
                    }
                } catch (Throwable t) {
                    logError("clearLockedTasks", t);
                }
            }
            return done;
        } catch (Throwable t) {
            logError("unpin", t);
            return false;
        }
    }

    private int lockTaskState() {
        try {
            final Class<?> amClass = XposedHelpers.findClass("android.app.ActivityManager", null);
            try {
                final Object am = XposedHelpers.callStaticMethod(amClass, "getService");
                final Object r = XposedHelpers.callMethod(am, "getLockTaskModeState");
                return r instanceof Integer ? (Integer) r : -1;
            } catch (Throwable t1) {
                final Object r = XposedHelpers.callStaticMethod(amClass, "isInLockTaskMode");
                return Boolean.TRUE.equals(r) ? LOCK_TASK_MODE_PINNED : LOCK_TASK_MODE_NONE;
            }
        } catch (Throwable t2) {
            logError("lockTaskState", t2);
            return -1;
        }
    }

    private Object atmsService() {
        final Class<?> atmClass = XposedHelpers.findClass("android.app.ActivityTaskManager", null);
        final Object singleton = XposedHelpers.getStaticObjectField(atmClass, "IActivityTaskManagerSingleton");
        return XposedHelpers.callMethod(singleton, "get");
    }

    private static Method findMethodByNeedle(Object target, String needleLower) {
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains(needleLower)) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object invokeStartByArity(Method m, Object atms, int taskId) throws Exception {
        final Class<?>[] ps = m.getParameterTypes();
        if (ps.length == 1) {
            return m.invoke(atms, taskId);
        }
        if (ps.length == 2) {
            return m.invoke(atms, Display.DEFAULT_DISPLAY, taskId);
        }
        return m.invoke(atms, Display.DEFAULT_DISPLAY, 0, taskId);
    }

    private static Object invokeStopByArity(Method m, Object atms) throws Exception {
        final Class<?>[] ps = m.getParameterTypes();
        if (ps.length <= 1) {
            return m.invoke(atms, Display.DEFAULT_DISPLAY);
        }
        return m.invoke(atms, Display.DEFAULT_DISPLAY, 0);
    }

    private boolean pinViaLockTaskController(Object atms, int taskId) throws Exception {
        final Object ltc = findFieldOfTypeName(atms, "LockTaskController");
        if (ltc == null) {
            log("no LockTaskController field on " + atms.getClass().getName());
            return false;
        }
        final Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
        Object task = null;
        try {
            task = XposedHelpers.callMethod(root, "anyTaskForId", taskId, 0);
        } catch (Throwable ignored) {
        }
        if (task == null) {
            task = XposedHelpers.callMethod(root, "anyTaskForId", taskId);
        }
        if (task == null) {
            log("anyTaskForId returned null for " + taskId);
            return false;
        }
        final List<Method> cands = new ArrayList<Method>();
        Class<?> c = ltc.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                final String ln = m.getName().toLowerCase();
                if (ln.startsWith("start") && ln.contains("lock")) {
                    cands.add(m);
                }
            }
            c = c.getSuperclass();
        }
        for (Method m : cands) {
            final Object[] args;
            try {
                args = buildArgs(m, task, taskId);
            } catch (Throwable t) {
                continue;
            }
            if (args == null) {
                continue;
            }
            try {
                m.setAccessible(true);
                m.invoke(ltc, args);
            } catch (Throwable t) {
                continue;
            }
            final int st = lockTaskState();
            if (st != LOCK_TASK_MODE_NONE) {
                return true;
            }
        }
        return false;
    }

    private static Object[] buildArgs(Method m, Object task, int taskId) {
        final Class<?>[] ps = m.getParameterTypes();
        boolean hasTask = false;
        for (Class<?> p : ps) {
            final String n = p.getSimpleName();
            if (n.equals("Task") || n.equals("ActivityRecord")) hasTask = true;
        }
        int boolCount = 0;
        for (Class<?> p : ps) {
            if (p.getSimpleName().equals("boolean")) boolCount++;
        }
        final Object[] args = new Object[ps.length];
        int intSeen = 0;
        for (int i = 0; i < ps.length; i++) {
            final Class<?> p = ps[i];
            final String n = p.getSimpleName();
            if (n.equals("Task") || n.equals("ActivityRecord")) {
                args[i] = task;
            } else if (n.equals("boolean") || n.equals("Boolean")) {
                args[i] = boolCount == 1 ? Boolean.TRUE : Boolean.FALSE;
            } else if (n.equals("int") || n.equals("Integer")) {
                if (hasTask) {
                    args[i] = 0;
                } else {
                    args[i] = intSeen++ == 0 ? taskId : 0;
                }
            } else if (n.equals("String")) {
                args[i] = null;
            } else {
                return null;
            }
        }
        return args;
    }

    private static Object findFieldOfTypeName(Object holder, String typeNamePart) {
        Class<?> c = holder.getClass();
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getType().getName().contains(typeNamePart)) continue;
                try {
                    f.setAccessible(true);
                    return f.get(holder);
                } catch (Throwable ignored) {
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
    }

    private int findForegroundTaskId(Object atms) {
        final Object raw;
        try {
            final Object slice = XposedHelpers.callMethod(atms, "getTasks", 1);
            if (slice instanceof List) {
                raw = slice;
            } else {
                raw = XposedHelpers.callMethod(slice, "getList");
            }
        } catch (Throwable t) {
            logError("getTasks", t);
            return -1;
        }
        if (!(raw instanceof List)) return -1;
        final List<?> tasks = (List<?>) raw;
        for (Object task : tasks) {
            if (task == null) continue;
            int displayId;
            try {
                displayId = XposedHelpers.getIntField(task, "displayId");
            } catch (Throwable t) {
                displayId = Display.DEFAULT_DISPLAY;
            }
            if (displayId != Display.DEFAULT_DISPLAY) continue;
            try {
                return XposedHelpers.getIntField(task, "taskId");
            } catch (Throwable t) {
                logError("read taskId", t);
            }
        }
        return -1;
    }

    private void forceEnableLockToApp(Context ctx) {
        try {
            final Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", null);
            final android.content.ContentResolver cr = ctx.getContentResolver();
            XposedHelpers.callStaticMethod(secure, "putInt", cr, SETTING_LOCK_TO_APP, 1);
        } catch (Throwable t) {
            logError("forceEnableLockToApp", t);
        }
    }

    private void initRecentsSide(LoadPackageParam lpp) {
        ClassLoader cl = lpp.classLoader;
        try {
            Class<?> amWrapper = XposedHelpers.findClass("com.android.systemui.shared.system.ActivityManagerWrapper", cl);
            XposedBridge.hookAllMethods(amWrapper, "isScreenPinningEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> proxy = XposedHelpers.findClass("com.android.quickstep.SystemUiProxy", cl);
            XposedBridge.hookAllMethods(proxy, "startScreenPinning", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int taskId = -1;
                    for (Object a : param.args) {
                        if (a instanceof Integer) {
                            taskId = (Integer) a;
                        }
                    }
                    if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                        for (int i = param.args.length - 1; i >= 0; i--) {
                            if (param.args[i] instanceof Integer && (Integer) param.args[i] > 0) {
                                taskId = (Integer) param.args[i];
                                break;
                            }
                        }
                    }
                    Context ctx = null;
                    try {
                        Object c = XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (c instanceof Context) ctx = (Context) c;
                    } catch (Throwable ignored2) {
                    }
                    if (ctx == null) ctx = currentApplicationContext();
                    if (ctx != null && sendPinViaBroadcast(ctx, taskId)) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            Class<?> pin = XposedHelpers.findClass("com.android.quickstep.TaskShortcutFactory$PinSystemShortcut", cl);
            XposedBridge.hookAllMethods(pin, "onClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int taskId = -1;
                    try {
                        Object taskView = XposedHelpers.getObjectField(param.thisObject, "mTaskView");
                        if (taskView != null) {
                            Object firstTask = XposedHelpers.callMethod(taskView, "getFirstTask");
                            if (firstTask == null) firstTask = XposedHelpers.callMethod(taskView, "getTask");
                            if (firstTask != null) {
                                Object key = XposedHelpers.getObjectField(firstTask, "key");
                                if (key != null) taskId = XposedHelpers.getIntField(key, "id");
                            }
                        }
                    } catch (Throwable ignored2) {
                    }
                    Context ctx = null;
                    try {
                        if (param.args.length > 0 && param.args[0] instanceof android.view.View) {
                            ctx = ((android.view.View) param.args[0]).getContext();
                        }
                    } catch (Throwable ignored2) {
                    }
                    if (ctx == null) {
                        try {
                            Object tv = XposedHelpers.getObjectField(param.thisObject, "mTaskView");
                            if (tv instanceof android.view.View) ctx = ((android.view.View) tv).getContext();
                        } catch (Throwable ignored2) {
                        }
                    }
                    if (ctx == null) ctx = currentApplicationContext();
                    if (ctx != null && sendPinViaBroadcast(ctx, taskId)) {
                        param.setResult(null);
                        try {
                            XposedHelpers.callMethod(param.thisObject, "dismissTaskMenuView");
                        } catch (Throwable t) {
                            try {
                                Object target = XposedHelpers.getObjectField(param.thisObject, "mTarget");
                                if (target != null) XposedHelpers.callMethod(target, "dismissTaskMenuView");
                            } catch (Throwable ignored3) {
                            }
                        }
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean sendPinViaBroadcast(Context ctx, int taskId) {
        if (ctx == null) return false;
        int token = 0;
        try {
            token = Settings.Secure.getInt(ctx.getContentResolver(), OxyPin.TOKEN_KEY);
        } catch (Throwable ignored) {
        }
        if (token == 0) {
            try {
                Class<?> secure = XposedHelpers.findClass("android.provider.Settings$Secure", null);
                token = (Integer) XposedHelpers.callStaticMethod(secure, "getInt", ctx.getContentResolver(), OxyPin.TOKEN_KEY);
            } catch (Throwable ignored) {
            }
        }
        if (token == 0) return false;
        Intent i = new Intent(OxyPin.ACTION_PIN);
        i.putExtra(OxyPin.EXTRA_TOKEN, token);
        if (taskId > 0) i.putExtra(OxyPin.EXTRA_TASK_ID, taskId);
        try {
            ctx.sendBroadcast(i);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Context currentApplicationContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object cur = at.getMethod("currentApplication").invoke(null);
            if (cur instanceof Context) return (Context) cur;
            Object atObj = at.getMethod("currentActivityThread").invoke(null);
            Object app = XposedHelpers.callMethod(atObj, "getApplication");
            if (app instanceof Context) return (Context) app;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Handler mainHandler() {
        Handler h = mainHandler;
        if (h != null) return h;
        synchronized (this) {
            if (mainHandler == null) {
                final Looper looper = Looper.getMainLooper();
                if (looper == null) {
                    log("main looper not ready yet");
                    return null;
                }
                mainHandler = new Handler(looper);
            }
            return mainHandler;
        }
    }

    private void toast(final Context ctx, final String msg) {
        final Handler h = mainHandler();
        if (h == null) {
            return;
        }
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    logError("toast", t);
                }
            }
        });
    }

    private static String tr(Context ctx, String ru, String en) {
        try {
            final java.util.Locale loc = ctx.getResources().getConfiguration().getLocales().get(0);
            final String lang = loc == null ? "en" : loc.getLanguage();
            return "ru".equals(lang) ? ru : en;
        } catch (Throwable t) {
            return en;
        }
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }

    private static void logError(String where, Throwable t) {
        XposedBridge.log(TAG + ": ERROR " + where + ": " + android.util.Log.getStackTraceString(t));
    }
}
