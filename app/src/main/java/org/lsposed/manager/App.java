/*
 * This file is part of LSPosed.
 *
 * LSPosed is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LSPosed is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2020 EdXposed Contributors
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.manager;

import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.Settings;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;

import org.lsposed.hiddenapibypass.HiddenApiBypass;
import org.lsposed.manager.adapters.AppHelper;
import org.lsposed.manager.receivers.LSPManagerServiceHolder;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.util.CloudflareDNS;
import org.lsposed.manager.util.ModuleUtil;
import org.lsposed.manager.util.Telemetry;
import org.lsposed.manager.util.ThemeUtil;
import org.lsposed.manager.util.UpdateUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import rikka.core.os.FileUtils;
import rikka.material.app.LocaleDelegate;

public class App extends Application {
    public static final int PER_USER_RANGE = 100000;
    public static FutureTask<String> HTML_TEMPLATE;
    public static FutureTask<String> HTML_TEMPLATE_DARK;
    
    private static String readWebviewHTML(String name) {
        try {
            var input = App.getInstance().getAssets().open("webview/" + name);
            var result = new ByteArrayOutputStream(1024);
            FileUtils.copy(input, result);
            return result.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.e(App.TAG, "Failed to read webview HTML", e);
            return "<html dir\"@dir@\"><body>@body@</body></html>";
        }
    }

    public static final String TAG = "LSPosedManager";
    private static final String ACTION_USER_ADDED = "android.intent.action.USER_ADDED";
    private static final String ACTION_USER_REMOVED = "android.intent.action.USER_REMOVED";
    private static final String ACTION_USER_INFO_CHANGED = "android.intent.action.USER_INFO_CHANGED";
    private static final String EXTRA_REMOVED_FOR_ALL_USERS = "android.intent.extra.REMOVED_FOR_ALL_USERS";
    private static App instance = null;
    private static OkHttpClient okHttpClient;
    private static Cache okHttpCache;
    private SharedPreferences pref;
    private static final ExecutorService executorService = Executors.newCachedThreadPool();
    private static final Handler MainHandler = new Handler(Looper.getMainLooper());

    public static App getInstance() {
        return instance;
    }

    public static SharedPreferences getPreferences() {
        return instance.pref;
    }

    public static ExecutorService getExecutorService() {
        return executorService;
    }

    public static final boolean isParasitic = !Process.isApplicationUid(Process.myUid());

    public static Handler getMainHandler() {
        return MainHandler;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Telemetry.start(this);
        var map = new HashMap<String, String>(1);
        map.put("isParasitic", String.valueOf(isParasitic));
        Telemetry.trackEvent("App start", map);
        var am = getSystemService(ActivityManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            map.clear();
            var reasons = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (reasons.size() == 1) {
                map.put("description", reasons.get(0).getDescription());
                map.put("importance", String.valueOf(reasons.get(0).getImportance()));
                map.put("process", reasons.get(0).getProcessName());
                map.put("reason", String.valueOf(reasons.get(0).getReason()));
                map.put("status", String.valueOf(reasons.get(0).getStatus()));
                Telemetry.trackEvent("Last exit reasons", map);
            }
        }
    }

    private void setCrashReport() {
        var handler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            var time = OffsetDateTime.now();
            var dir = new File(getCacheDir(), "crash");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "Failed to create crash directory");
            }
            var file = new File(dir, time.toEpochSecond() + ".log");
            try (var pw = new PrintWriter(file)) {
                pw.println(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
                pw.println(time);
                pw.println("pid: " + Os.getpid() + " uid: " + Os.getuid());
                throwable.printStackTrace(pw);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write crash log", e);
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var table = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                var values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "LSPosed_crash_report" + time.toEpochSecond() + ".zip");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);
                var cr = getContentResolver();
                var uri = cr.insert(table, values);
                if (uri == null) {
                    Log.e(TAG, "Failed to create crash report content URI");
                    return;
                }
                try (var zipFd = cr.openFileDescriptor(uri, "wt")) {
                    LSPManagerServiceHolder.getService().getLogs(zipFd);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write crash report", e);
                    try {
                        cr.delete(uri, null, null);
                    } catch (Exception ex) {
                        Log.e(TAG, "Failed to delete crash report URI", ex);
                    }
                }
            }
            if (handler != null) {
                handler.uncaughtException(thread, throwable);
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        // Initialize webview HTML templates
        HTML_TEMPLATE = new FutureTask<>(() -> readWebviewHTML("template.html"));
        HTML_TEMPLATE_DARK = new FutureTask<>(() -> readWebviewHTML("template_dark.html"));

        setCrashReport();
        pref = PreferenceManager.getDefaultSharedPreferences(this);
        if (!pref.contains("doh")) {
            var name = "private_dns_mode";
            if ("hostname".equals(Settings.Global.getString(getContentResolver(), name))) {
                pref.edit().putBoolean("doh", false).apply();
            } else {
                pref.edit().putBoolean("doh", true).apply();
            }
        }
        AppCompatDelegate.setDefaultNightMode(ThemeUtil.getDarkTheme());
        LocaleDelegate.setDefaultLocale(getLocale());
        var res = getResources();
        var config = res.getConfiguration();
        config.setLocale(LocaleDelegate.getDefaultLocale());
        //noinspection deprecation
        res.updateConfiguration(config, res.getDisplayMetrics());

        // Initialize background tasks
        getExecutorService().submit(() -> {
            var list = AppHelper.getAppList(false);
            var pm = App.getInstance().getPackageManager();
            list.parallelStream().forEach(i -> AppHelper.getAppLabel(i, pm));
            AppHelper.getDenyList(false);
            ModuleUtil.getInstance();
            RepoLoader.getInstance();
        });
        getExecutorService().submit(HTML_TEMPLATE);
        getExecutorService().submit(HTML_TEMPLATE_DARK);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction("org.lsposed.manager.NOTIFICATION");
        
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent inIntent) {
                Intent intent = inIntent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (intent == null) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Received notification without EXTRA_INTENT");
                    }
                    return;
                }
                
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onReceive: " + intent);
                }
                
                String action = intent.getAction();
                if (action == null) return;
                
                int userId = intent.getIntExtra(Intent.EXTRA_USER, 0);
                String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGES);
                boolean packageRemovedForAllUsers = intent.getBooleanExtra(EXTRA_REMOVED_FOR_ALL_USERS, false);
                boolean isXposedModule = intent.getBooleanExtra("isXposedModule", false);
                
                switch (action) {
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_CHANGED:
                    case Intent.ACTION_PACKAGE_FULLY_REMOVED:
                    case Intent.ACTION_UID_REMOVED:
                        if (packageName != null) {
                            if (isXposedModule) {
                                ModuleUtil.getInstance().reloadSingleModule(packageName, userId, packageRemovedForAllUsers);
                            } else {
                                App.getExecutorService().submit(() -> AppHelper.getAppList(true));
                            }
                        }
                        break;
                    case ACTION_USER_ADDED:
                    case ACTION_USER_REMOVED:
                    case ACTION_USER_INFO_CHANGED:
                        App.getExecutorService().submit(() -> ModuleUtil.getInstance().reloadInstalledModules());
                        break;
                }
            }
        };
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(receiver, intentFilter);
        }

        UpdateUtil.loadRemoteVersion();
    }

    @NonNull
    public static OkHttpClient getOkHttpClient() {
        if (okHttpClient != null) return okHttpClient;
        var builder = new OkHttpClient.Builder()
            .cache(getOkHttpCache())
            .dns(new CloudflareDNS());
        if (BuildConfig.DEBUG) {
            var log = new HttpLoggingInterceptor(message -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, message);
                }
            });
            log.setLevel(HttpLoggingInterceptor.Level.HEADERS);
            builder.addInterceptor(log);
        }
        okHttpClient = builder.build();
        return okHttpClient;
    }

    @NonNull
    public static Cache getOkHttpCache() {
        if (okHttpCache != null) return okHttpCache;
        long size50MiB = 50 * 1024 * 1024;
        okHttpCache = new Cache(new File(instance.getCacheDir(), "http_cache"), size50MiB);
        return okHttpCache;
    }

    public static Locale getLocale(String tag) {
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return LocaleDelegate.getSystemLocale();
        }
        return Locale.forLanguageTag(tag);
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString("language", null);
        return getLocale(tag);
    }
}
