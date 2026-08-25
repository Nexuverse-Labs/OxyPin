package ru.oxypin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
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
        if (!"android".equals(lpp.packageName)) {
            return;
        }
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
                    if (doPin(c)) {
                        toast(c, tr(c, "Приложение закреплено", "App pinned"));
                    } else {
                        toast(c, tr(c,
                                "OxyPin: закрепить не удалось, смотри логи",
                                "OxyPin: failed to pin, see LSPosed logs"));
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
                        toast(c, tr(c,
                                "OxyPin: открепить не удалось, смотри логи",
                                "OxyPin: failed to unpin, see LSPosed logs"));
                    }
                }
            } catch (Throwable t) {
                logError("onReceive", t);
            }
        }
    }

    private boolean doPin(Context ctx) {
        try {
            final int state = lockTaskState();
            if (state != LOCK_TASK_MODE_NONE) {
                log("pin skipped: already locked/pinned, state=" + state);
                return false;
            }
            forceEnableLockToApp(ctx);

            final Object atms = atmsService();
            final int taskId = findForegroundTaskId(atms);
            if (taskId <= 0) {
                log("no foreground task found");
                return false;
            }
            Throwable lastErr = null;
            boolean ok = false;
            final Method m = findMethodByNeedle(atms, "startscreenpinning");
            if (m != null) {
                log("pin method found: " + m);
                try {
                    invokeStartByArity(m, atms, taskId);
                    ok = true;
                } catch (Throwable t) {
                    lastErr = unwrap(t);
                    logError("invoke " + m.getName(), lastErr);
                }
            }
            if (!ok) {
                try {
                    ok = pinViaLockTaskController(atms, taskId);
                } catch (Throwable t) {
                    lastErr = t;
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
                log("unpin method found: " + m);
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
        log("LockTaskController instance: " + ltc.getClass().getName());
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
        log("pin candidates=" + cands.size());
        for (Method m : cands) {
            final Object[] args;
            try {
                args = buildArgs(m, task, taskId);
            } catch (Throwable t) {
                continue;
            }
            if (args == null) {
                log("skip candidate, unsupported params: " + m);
                continue;
            }
            try {
                m.setAccessible(true);
                m.invoke(ltc, args);
            } catch (Throwable t) {
                logError("candidate " + m.getName(), unwrap(t));
                continue;
            }
            final int st = lockTaskState();
            if (st != LOCK_TASK_MODE_NONE) {
                log("SUCCESS via " + m + " state=" + st);
                return true;
            }
            log("invoked " + m.getName() + ", state unchanged (" + st + ")");
        }
        dumpLockApis(ltc, atms);
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

    private void dumpLockApis(Object ltc, Object atms) {
        final StringBuilder sb = new StringBuilder("LTC dump: ");
        Class<?> c = ltc.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                final String ln = m.getName().toLowerCase();
                if (ln.contains("lock") || ln.contains("pin")) {
                    sb.append(m.toString()).append(" | ");
                }
            }
            c = c.getSuperclass();
        }
        log(sb.toString());
        final StringBuilder sb2 = new StringBuilder("ATMS locktask dump: ");
        Class<?> c2 = atms.getClass();
        while (c2 != null && c2 != Object.class) {
            for (Method m : c2.getDeclaredMethods()) {
                if (m.getName().toLowerCase().contains("locktask")) {
                    sb2.append(m.toString()).append(" | ");
                }
            }
            c2 = c2.getSuperclass();
        }
        log(sb2.toString());
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
            log("toast skipped (no handler): " + msg);
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
