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
 * Copyright (C) 2021 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static android.content.Context.BIND_AUTO_CREATE;
import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.annotation.SuppressLint;
import android.app.IServiceConnection;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.VersionedPackage;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.SystemProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.IWindowManager;

import androidx.annotation.NonNull;

import org.lsposed.daemon.BuildConfig;
import org.lsposed.lspd.ILSPManagerService;
import org.lsposed.lspd.models.Application;
import org.lsposed.lspd.models.UserInfo;
import org.lsposed.lspd.util.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import hidden.HiddenApiBridge;
import io.github.libxposed.service.IXposedService;
import rikka.parcelablelist.ParcelableListSlice;

public class LSPManagerService extends ILSPManagerService.Stub {
    private static Intent managerIntent = null;
    private boolean enabled = true;
    private volatile Map<String, PackageInfo> packageInfoCache = new ConcurrentHashMap<>();
    private long cacheLastUpdated = 0;
    private static final long CACHE_EXPIRY_MS = TimeUnit.MINUTES.toMillis(5);
    private final Map<String, Long> lastCallTime = new ConcurrentHashMap<>();
    private static final long MIN_CALL_INTERVAL = TimeUnit.SECONDS.toMillis(1);

    public class ManagerGuard implements IBinder.DeathRecipient {
        private final @NonNull IBinder binder;
        private final int pid;
        private final int uid;
        private final IServiceConnection connection = new IServiceConnection.Stub() {
            @Override
            public void connected(ComponentName name, IBinder service, boolean dead) {
            }
        };

        public ManagerGuard(@NonNull IBinder binder, int pid, int uid) {
            guard = this;
            this.pid = pid;
            this.uid = uid;
            this.binder = binder;
            try {
                this.binder.linkToDeath(this, 0);
                if (Utils.isMIUI) {
                    var intent = new Intent();
                    intent.setComponent(ComponentName.unflattenFromString("com.miui.securitycore/com.miui.xspace.service.XSpaceService"));
                    ActivityManagerService.bindService(intent, intent.getType(), connection, BIND_AUTO_CREATE, "android", 0);
                }
            } catch (Throwable e) {
                Log.e(TAG, "manager guard", e);
                guard = null;
            }
        }

        @Override
        public void binderDied() {
            try {
                binder.unlinkToDeath(this, 0);
                ActivityManagerService.unbindService(connection);
            } catch (Throwable e) {
                Log.e(TAG, "manager guard", e);
            }
            guard = null;
        }

        boolean isAlive() {
            return binder.isBinderAlive();
        }
    }

    public ManagerGuard guard = null;
    private boolean pendingManager = false;
    private int managerPid = -1;

    LSPManagerService() {
    }

    private static Intent getManagerIntent() {
        try {
            String managerPackageName = ConfigManager.isManagerInstalled() ? BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME : BuildConfig.MANAGER_INJECTED_PKG_NAME;
            var intent = PackageService.getLaunchIntentForPackage(BuildConfig.MANAGER_INJECTED_PKG_NAME);
            if (intent == null) {
                var pkgInfo = PackageService.getPackageInfo(managerPackageName, PackageManager.GET_ACTIVITIES, 0);
                if (pkgInfo != null && pkgInfo.activities != null && pkgInfo.activities.length > 0) {
                    for (var activityInfo : pkgInfo.activities) {
                        if (activityInfo.processName.equals(activityInfo.packageName)) {
                            intent = new Intent();
                            intent.setComponent(new ComponentName(activityInfo.packageName, activityInfo.name));
                            intent.setAction(Intent.ACTION_MAIN);
                            break;
                        }
                    }
                }
            }
            if (intent != null) {
                if (intent.getCategories() != null) intent.getCategories().clear();
                intent.addCategory("org.lsposed.manager.LAUNCH_MANAGER");
                intent.setPackage(managerPackageName);
                managerIntent = new Intent(intent);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "get Intent", e);
        }
        return managerIntent;
    }

    static void openManager(Uri withData) {
        var intent = getManagerIntent();
        if (intent == null) return;
        intent = new Intent(intent);
        intent.setData(withData);
        try {
            ActivityManagerService.startActivityAsUserWithFeature("android", null, intent, intent.getType(), null, null, 0, 0, null, null, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to open manager");
        }
    }

    @SuppressLint("WrongConstant")
    public static void broadcastIntent(Intent inIntent) {
        String managerPackageName = ConfigManager.isManagerInstalled() ? BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME : BuildConfig.MANAGER_INJECTED_PKG_NAME;
        var intent = new Intent("org.lsposed.manager.NOTIFICATION");
        intent.putExtra(Intent.EXTRA_INTENT, inIntent);
        intent.addFlags(0x01000000);
        intent.addFlags(0x00400000);
        intent.setPackage(managerPackageName);
        try {
            ActivityManagerService.broadcastIntentWithFeature(null, intent,
                    null, null, 0, null, null,
                    null, -1, null, true, false,
                    0);
            intent.setPackage(managerPackageName);
            ActivityManagerService.broadcastIntentWithFeature(null, intent,
                    null, null, 0, null, null,
                    null, -1, null, true, false,
                    0);
        } catch (RemoteException t) {
            Log.e(TAG, "Broadcast to manager failed: ", t);
        }
    }

    private void ensureWebViewPermission(File f) {
        if (!f.exists()) return;
        SELinux.setFileContext(f.getAbsolutePath(), "u:object_r:magisk_file:s0");
        try {
            Os.chown(f.getAbsolutePath(), BuildConfig.MANAGER_INJECTED_UID, BuildConfig.MANAGER_INJECTED_UID);
        } catch (ErrnoException e) {
            Log.e(TAG, "chown of webview", e);
        }
        if (f.isDirectory()) {
            for (var g : f.listFiles()) {
                ensureWebViewPermission(g);
            }
        }
    }

    private void ensureWebViewPermission() {
        try {
            var pkgInfo = PackageService.getPackageInfo(BuildConfig.MANAGER_INJECTED_PKG_NAME, 0, 0);
            if (pkgInfo != null) {
                var cacheDir = new File(HiddenApiBridge.ApplicationInfo_credentialProtectedDataDir(pkgInfo.applicationInfo) + "/cache");
                cacheDir.mkdirs();
                ensureWebViewPermission(cacheDir);
            }
        } catch (Throwable e) {
            if (BuildConfig.DEBUG) Log.d(TAG, "cannot ensure webview dir", e);
            else Log.w(TAG, "cannot ensure webview dir");
        }
    }

    private boolean verifyCallingIdentity(int pid, int uid) {
        try {
            int actualUid = ActivityManagerService.getUidForPid(pid);
            return actualUid == uid;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to verify calling identity", e);
            return false;
        }
    }

    private boolean isValidPackageName(String packageName) {
        return packageName != null && packageName.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)*$");
    }

    private void logError(String methodName, String message, Throwable throwable) {
        Log.e(TAG, methodName + ": " + message + " - " + (throwable != null ? throwable.getMessage() : "No exception"));
        if (throwable != null) {
            Log.e(TAG, "Stack trace:", throwable);
        }
    }

    private void logDebug(String methodName, String message) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, methodName + ": " + message);
        }
    }

    private void checkRateLimit(String methodName) {
        long now = System.currentTimeMillis();
        Long lastCall = lastCallTime.get(methodName);
        
        if (lastCall != null && (now - lastCall) < MIN_CALL_INTERVAL) {
            throw new SecurityException("Rate limit exceeded for method: " + methodName);
        }
        
        lastCallTime.put(methodName, now);
    }

    synchronized boolean preStartManager() {
        pendingManager = true;
        managerPid = -1;
        return true;
    }

    synchronized boolean shouldStartManager(int pid, int uid, String processName) {
        if (!enabled || uid != BuildConfig.MANAGER_INJECTED_UID || !BuildConfig.DEFAULT_MANAGER_PACKAGE_NAME.equals(processName) || !pendingManager)
            return false;
        pendingManager = false;
        managerPid = pid;
        logDebug("shouldStartManager", "starting injected manager: pid = " + pid + " uid = " + uid + " processName = " + processName);
        return true;
    }

    synchronized boolean setEnabled(boolean newValue) {
        enabled = newValue;
        logDebug("setEnabled", "manager enabled = " + enabled);
        return enabled;
    }

    boolean postStartManager(int pid, int uid) {
        return enabled && uid == BuildConfig.MANAGER_INJECTED_UID && pid == managerPid;
    }

    public @NonNull IBinder obtainManagerBinder(@NonNull IBinder heartbeat, int pid, int uid) {
        if (!verifyCallingIdentity(pid, uid)) {
            throw new SecurityException("Identity verification failed");
        }
        
        new ManagerGuard(heartbeat, pid, uid);
        if (uid == BuildConfig.MANAGER_INJECTED_UID)
            ensureWebViewPermission();
        return this;
    }

    public boolean isRunningManager(int pid, int uid) {
        return false;
    }

    void onSystemServerDied() {
        guard = null;
    }

    @Override
    public IBinder asBinder() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "asBinder called", new Throwable("Stack trace"));
        }
        return super.asBinder();
    }

    @Override
    public int getXposedApiVersion() {
        return IXposedService.API;
    }

    @Override
    public int getXposedVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @Override
    public String getXposedVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @Override
    public String getApi() {
        return ConfigManager.getInstance().getApi();
    }

    @Override
    public ParcelableListSlice<PackageInfo> getInstalledPackagesFromAllUsers(int flags, boolean filterNoProcess) throws RemoteException {
        return PackageService.getInstalledPackagesFromAllUsers(flags, filterNoProcess);
    }

    @Override
    public String[] enabledModules() {
        return ConfigManager.getInstance().enabledModules();
    }

    @Override
    public boolean enableModule(String packageName) throws RemoteException {
        checkRateLimit("enableModule");
        logDebug("enableModule", "Attempting to enable module: " + packageName);
        
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name cannot be null or empty");
        }
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name format");
        }
        
        try {
            boolean result = ConfigManager.getInstance().enableModule(packageName);
            logDebug("enableModule", "Result for " + packageName + ": " + result);
            return result;
        } catch (Exception e) {
            logError("enableModule", "Failed to enable module: " + packageName, e);
            throw new RemoteException("Failed to enable module");
        }
    }

    @Override
    public boolean setModuleScope(String packageName, List<Application> scope) throws RemoteException {
        checkRateLimit("setModuleScope");
        logDebug("setModuleScope", "Setting scope for module: " + packageName);
        
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name cannot be null or empty");
        }
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name format");
        }
        
        try {
            boolean result = ConfigManager.getInstance().setModuleScope(packageName, scope);
            logDebug("setModuleScope", "Result for " + packageName + ": " + result);
            return result;
        } catch (Exception e) {
            logError("setModuleScope", "Failed to set module scope: " + packageName, e);
            throw new RemoteException("Failed to set module scope");
        }
    }

    @Override
    public List<Application> getModuleScope(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name cannot be null or empty");
        }
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name format");
        }
        
        return ConfigManager.getInstance().getModuleScope(packageName);
    }

    @Override
    public boolean disableModule(String packageName) {
        checkRateLimit("disableModule");
        logDebug("disableModule", "Attempting to disable module: " + packageName);
        
        if (packageName == null || packageName.trim().isEmpty()) {
            throw new IllegalArgumentException("Package name cannot be null or empty");
        }
        if (!isValidPackageName(packageName)) {
            throw new IllegalArgumentException("Invalid package name format");
        }
        
        try {
            boolean result = ConfigManager.getInstance().disableModule(packageName);
            logDebug("disableModule", "Result for " + packageName + ": " + result);
            return result;
        } catch (Exception e) {
            logError("disableModule", "Failed to disable module: " + packageName, e);
            return false;
        }
    }

    @Override
    public boolean isVerboseLog() {
        return ConfigManager.getInstance().verboseLog();
    }

    @Override
    public void setVerboseLog(boolean enabled) {
        ConfigManager.getInstance().setVerboseLog(enabled);
    }

    @Override
    public ParcelFileDescriptor getVerboseLog() {
        return ConfigManager.getInstance().getVerboseLog();
    }

    @Override
    public ParcelFileDescriptor getModulesLog() {
        ServiceManager.getLogcatService().checkLogFile();
        return ConfigManager.getInstance().getModulesLog();
    }

    @Override
    public boolean clearLogs(boolean verbose) {
        return ConfigManager.getInstance().clearLogs(verbose);
    }

    @Override
    public PackageInfo getPackageInfo(String packageName, int flags, int uid) throws RemoteException {
        String cacheKey = packageName + ":" + flags + ":" + uid;
        PackageInfo cached = packageInfoCache.get(cacheKey);
        
        if (cached != null && System.currentTimeMillis() - cacheLastUpdated < CACHE_EXPIRY_MS) {
            return cached;
        }
        
        PackageInfo info = PackageService.getPackageInfo(packageName, flags, uid);
        if (info != null) {
            packageInfoCache.put(cacheKey, info);
            cacheLastUpdated = System.currentTimeMillis();
        }
        return info;
    }

    public void clearPackageInfoCache() {
        packageInfoCache.clear();
    }

    @Override
    public void forceStopPackage(String packageName, int userId) throws RemoteException {
        ActivityManagerService.forceStopPackage(packageName, userId);
    }

    @Override
    public void reboot() throws RemoteException {
        PowerService.reboot(false, null, false);
    }

    @Override
    public boolean uninstallPackage(String packageName, int userId) throws RemoteException {
        try {
            if (ActivityManagerService.startUserInBackground(userId)) {
                var pkg = new VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST);
                return PackageService.uninstallPackage(pkg, userId);
            } else {
                return false;
            }
        } catch (InterruptedException | ReflectiveOperationException e) {
            logError("uninstallPackage", "Failed to uninstall package: " + packageName, e);
            return false;
        }
    }

    @Override
    public boolean isSepolicyLoaded() {
        return SELinux.checkSELinuxAccess("u:r:dex2oat:s0", "u:object_r:dex2oat_exec:s0",
                "file", "execute_no_trans");
    }

    @Override
    public List<UserInfo> getUsers() throws RemoteException {
        var users = new LinkedList<UserInfo>();
        for (var user : UserService.getUsers()) {
            var info = new UserInfo();
            info.id = user.id;
            info.name = user.name;
            users.add(info);
        }
        return users;
    }

    @Override
    public int installExistingPackageAsUser(String packageName, int userId) {
        try {
            if (ActivityManagerService.startUserInBackground(userId))
                return PackageService.installExistingPackageAsUser(packageName, userId);
            else return PackageService.INSTALL_FAILED_INTERNAL_ERROR;
        } catch (Throwable e) {
            logError("installExistingPackageAsUser", "Failed to install existing package: " + packageName, e);
            return PackageService.INSTALL_FAILED_INTERNAL_ERROR;
        }
    }

    @Override
    public boolean systemServerRequested() {
        return ServiceManager.systemServerRequested();
    }

    @Override
    public int startActivityAsUserWithFeature(Intent intent, int userId) throws RemoteException {
        if (!intent.getBooleanExtra("lsp_no_switch_to_user", false)) {
            intent.removeExtra("lsp_no_switch_to_user");
            var currentUser = ActivityManagerService.getCurrentUser();
            if (currentUser == null) return -1;
            var parent = UserService.getProfileParent(userId);
            if (parent < 0) return -1;
            if (currentUser.id != parent) {
                if (!ActivityManagerService.switchUser(parent)) return -1;
                var window = android.os.ServiceManager.getService(Context.WINDOW_SERVICE);
                if (window != null) {
                    var wm = IWindowManager.Stub.asInterface(window);
                    wm.lockNow(null);
                }
            }
        }
        return ActivityManagerService.startActivityAsUserWithFeature("android", null, intent, intent.getType(), null, null, 0, 0, null, null, userId);
    }

    @Override
    public ParcelableListSlice<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) throws RemoteException {
        return PackageService.queryIntentActivities(intent, intent.getType(), flags, userId);
    }

    @Override
    public boolean dex2oatFlagsLoaded() {
        return SystemProperties.get("dalvik.vm.dex2oat-flags").contains("--inline-max-code-units=0");
    }

    private void setHiddenIconPreR(Bundle args) throws RemoteException {
        var contentProvider = ActivityManagerService.getContentProvider("settings", 0);
        if (contentProvider != null) {
            contentProvider.call("android", "settings", "PUT_global", "show_hidden_icon_apps_enabled", args);
        }
    }

    private void setHiddenIconR(Bundle args) throws RemoteException {
        var contentProvider = ActivityManagerService.getContentProvider("settings", 0);
        if (contentProvider != null) {
            contentProvider.call("android", null, "settings", "PUT_global", "show_hidden_icon_apps_enabled", args);
        }
    }

    private void setHiddenIconS(AttributionSource attributionSource, Bundle args) throws RemoteException {
        var contentProvider = ActivityManagerService.getContentProvider("settings", 0);
        if (contentProvider != null) {
            contentProvider.call(attributionSource, "settings", "PUT_global", "show_hidden_icon_apps_enabled", args);
        }
    }

    @Override
    public void setHiddenIcon(boolean hide) {
        Bundle args = new Bundle();
        args.putString("value", hide ? "0" : "1");
        args.putString("_user", "0");
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AttributionSource attributionSource = new AttributionSource.Builder(1000)
                    .setPackageName("android")
                    .build();
                setHiddenIconS(attributionSource, args);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.R) {
                setHiddenIconR(args);
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                setHiddenIconPreR(args);
            }
        } catch (Throwable e) {
            logError("setHiddenIcon", "Failed to set hidden icon", e);
        }
    }

    @Override
    public void getLogs(ParcelFileDescriptor zipFd) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try (zipFd) {
                ConfigFileManager.getLogs(zipFd);
            } catch (Exception e) {
                logError("getLogs", "Error getting logs", e);
            }
        });
    }

    @Override
    public void restartFor(Intent intent) throws RemoteException {
    }

    @Override
    public List<String> getDenyListPackages() {
        return ConfigManager.getInstance().getDenyListPackages();
    }

    @Override
    public void flashZip(String zipPath, ParcelFileDescriptor outputStream) {
        Process process = null;
        FileOutputStream fdw = null;
        
        try (outputStream) {
            var processBuilder = new ProcessBuilder("magisk", "--install-module", zipPath);
            var fd = new File("/proc/self/fd/" + outputStream.getFd());
            processBuilder.redirectOutput(ProcessBuilder.Redirect.appendTo(fd));
            
            fdw = new FileOutputStream(fd, true);
            process = processBuilder.start();
            
            if (process.waitFor(10, TimeUnit.SECONDS)) {
                int exit = process.exitValue();
                if (exit == 0) {
                    fdw.write("- Reboot after 5s\n".getBytes());
                    Thread.sleep(5000);
                    reboot();
                } else {
                    String s = "! Flash failed, exit with " + exit + "\n";
                    fdw.write(s.getBytes());
                }
            } else {
                process.destroy();
                fdw.write("! Timeout, abort\n".getBytes());
            }
        } catch (IOException | InterruptedException | RemoteException e) {
            logError("flashZip", "Error flashing zip", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (fdw != null) {
                try {
                    fdw.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing FileOutputStream", e);
                }
            }
        }
    }

    @Override
    public void clearApplicationProfileData(String packageName) throws RemoteException {
        PackageService.clearApplicationProfileData(packageName);
    }

    @Override
    public boolean enableStatusNotification() {
        return ConfigManager.getInstance().enableStatusNotification();
    }

    @Override
    public void setEnableStatusNotification(boolean enable) {
        ConfigManager.getInstance().setEnableStatusNotification(enable);
        if (enable) {
            LSPNotificationManager.notifyStatusNotification();
        } else {
            LSPNotificationManager.cancelStatusNotification();
        }
    }

    @Override
    public boolean performDexOptMode(String packageName) throws RemoteException {
        return PackageService.performDexOptMode(packageName);
    }

    @Override
    public boolean getDexObfuscate() {
        return ConfigManager.getInstance().dexObfuscate();
    }

    @Override
    public void setDexObfuscate(boolean enabled) {
        ConfigManager.getInstance().setDexObfuscate(enabled);
    }

    @Override
    public int getDex2OatWrapperCompatibility() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ServiceManager.getDex2OatService().getCompatibility();
        } else {
            return 0;
        }
    }
}
