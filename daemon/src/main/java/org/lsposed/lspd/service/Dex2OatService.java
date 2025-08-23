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
 * Copyright (C) 2022 LSPosed Contributors
 */

package org.lsposed.lspd.service;

import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_CRASHED;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_MOUNT_FAILED;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_OK;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_SELINUX_PERMISSIVE;
import static org.lsposed.lspd.ILSPManagerService.DEX2OAT_SEPOLICY_INCORRECT;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.FileObserver;
import android.os.Process;
import android.os.SELinux;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

@RequiresApi(Build.VERSION_CODES.Q)
public class Dex2OatService implements Runnable, AutoCloseable {
    private static final String TAG = "LSPosedDex2Oat";
    private static final String MODULE_PATH = "/data/adb/modules/zygisk_lsposed/";
    private static final String WRAPPER = MODULE_PATH + "bin/dex2oat";

    private final String[] dex2oatArray = new String[4];
    private final FileDescriptor[] fdArray = new FileDescriptor[4];
    private final FileObserver selinuxObserver;
    private int compatibility = DEX2OAT_OK;
    private Thread serviceThread;
    private boolean hasDebugVersions = false;

    public Dex2OatService() {
        initDex2oatPaths();
        selinuxObserver = initSelinuxObserver();
    }

    private void initDex2oatPaths() {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            boolean is64Bit = Process.is64Bit();
            openDex2oat(is64Bit ? 2 : 0, "/apex/com.android.runtime/bin/dex2oat");
            tryOpenDex2oat(is64Bit ? 3 : 1, "/apex/com.android.runtime/bin/dex2oatd");
        } else {
            openDex2oat(0, "/apex/com.android.art/bin/dex2oat32");
            openDex2oat(2, "/apex/com.android.art/bin/dex2oat64");
            tryOpenDex2oat(1, "/apex/com.android.art/bin/dex2oatd32");
            tryOpenDex2oat(3, "/apex/com.android.art/bin/dex2oatd64");
        }
    }

    private FileObserver initSelinuxObserver() {
        var enforce = Paths.get("/sys/fs/selinux/enforce");
        var policy = Paths.get("/sys/fs/selinux/policy");
        var list = new ArrayList<File>();
        list.add(enforce.toFile());
        list.add(policy.toFile());
        
        return new FileObserver(list, FileObserver.CLOSE_WRITE) {
            @Override
            public synchronized void onEvent(int i, @Nullable String s) {
                checkSelinuxStatus();
            }

            @Override
            public void stopWatching() {
                super.stopWatching();
            }
        };
    }

    private void openDex2oat(int id, String path) {
        try {
            var fd = Os.open(path, OsConstants.O_RDONLY, 0);
            dex2oatArray[id] = path;
            fdArray[id] = fd;
            Log.i(TAG, "Successfully opened " + path);
        } catch (ErrnoException e) {
            Log.e(TAG, "Failed to open " + path, e);
            throw new RuntimeException("Required dex2oat binary not found: " + path, e);
        }
    }

    private void tryOpenDex2oat(int id, String path) {
        try {
            var fd = Os.open(path, OsConstants.O_RDONLY, 0);
            dex2oatArray[id] = path;
            fdArray[id] = fd;
            hasDebugVersions = true;
            Log.i(TAG, "Successfully opened debug version: " + path);
        } catch (ErrnoException e) {
            Log.w(TAG, "Debug version not available: " + path);
        }
    }

    private void checkSelinuxStatus() {
        if (compatibility == DEX2OAT_CRASHED) {
            selinuxObserver.stopWatching();
            return;
        }

        boolean enforcing = false;
        try (var is = Files.newInputStream(Paths.get("/sys/fs/selinux/enforce"))) {
            enforcing = is.read() == '1';
        } catch (IOException ignored) {
        }

        if (!enforcing) {
            if (compatibility == DEX2OAT_OK) doMount(false);
            compatibility = DEX2OAT_SELINUX_PERMISSIVE;
        } else {
            checkSelinuxAccess();
        }
    }

    private void checkSelinuxAccess() {
        boolean hasAccess = SELinux.checkSELinuxAccess("u:r:untrusted_app:s0",
                "u:object_r:dex2oat_exec:s0", "file", "execute")
                || SELinux.checkSELinuxAccess("u:r:untrusted_app:s0",
                "u:object_r:dex2oat_exec:s0", "file", "execute_no_trans");
                
        if (hasAccess) {
            if (compatibility == DEX2OAT_OK) doMount(false);
            compatibility = DEX2OAT_SEPOLICY_INCORRECT;
        } else if (compatibility != DEX2OAT_OK) {
            checkAndRemount();
        }
    }

    private void checkAndRemount() {
        doMount(true);
        if (notMounted()) {
            doMount(false);
            compatibility = DEX2OAT_MOUNT_FAILED;
            selinuxObserver.stopWatching();
        } else {
            compatibility = DEX2OAT_OK;
        }
    }

    private boolean notMounted() {
        boolean anyMounted = false;
        for (int i = 0; i < dex2oatArray.length; i++) {
            var bin = dex2oatArray[i];
            if (bin == null) continue;
            
            if (!checkMount(bin)) {
                return true;
            }
            anyMounted = true;
        }
        
        return !anyMounted;
    }

    private boolean checkMount(String binPath) {
        try {
            var apex = Os.stat(binPath);
            var wrapper = Os.stat(WRAPPER);
            
            if (apex.st_dev != wrapper.st_dev || apex.st_ino != wrapper.st_ino) {
                Log.w(TAG, "Mount check failed: " + binPath + " is not mounted to " + WRAPPER);
                return false;
            }
            Log.i(TAG, "Mount check passed: " + binPath + " is mounted to " + WRAPPER);
            return true;
        } catch (ErrnoException e) {
            Log.w(TAG, "Mount check failed for " + binPath + ": " + e.getMessage());
            return false;
        }
    }

    private void doMount(boolean enabled) {
        doMountNative(enabled, dex2oatArray[0], dex2oatArray[1], dex2oatArray[2], dex2oatArray[3]);
    }

    public void start() {
        boolean hasValidBinaries = false;
        for (String path : dex2oatArray) {
            if (path != null) {
                hasValidBinaries = true;
                break;
            }
        }
        
        if (!hasValidBinaries) {
            Log.e(TAG, "No valid dex2oat binaries found");
            compatibility = DEX2OAT_MOUNT_FAILED;
            return;
        }

        if (!checkWrapperFile()) {
            Log.e(TAG, "Wrapper file not found: " + WRAPPER);
            compatibility = DEX2OAT_MOUNT_FAILED;
            return;
        }

        if (notMounted()) {
            doMount(true);
            if (notMounted()) {
                doMount(false);
                compatibility = DEX2OAT_MOUNT_FAILED;
                return;
            }
        }

        serviceThread = new Thread(this, "dex2oat-wrapper");
        serviceThread.setDaemon(true);
        serviceThread.start();
        
        selinuxObserver.startWatching();
        checkSelinuxStatus();
    }

    private boolean checkWrapperFile() {
        try {
            Os.stat(WRAPPER);
            return true;
        } catch (ErrnoException e) {
            Log.e(TAG, "Wrapper file not found: " + WRAPPER);
            return false;
        }
    }

    @Override
    public void run() {
        var sockPath = getSockPath();
        
        setSelinuxContexts();
        
        try (var server = new LocalServerSocket(sockPath)) {
            setSockCreateContext(null);
            serveClients(server);
        } catch (IOException e) {
            handleServiceCrash(e);
        }
    }

    private void setSelinuxContexts() {
        var magisk_file = "u:object_r:magisk_file:s0";
        var dex2oat_exec = "u:object_r:dex2oat_exec:s0";
        
        if (SELinux.checkSELinuxAccess("u:r:dex2oat:s0", dex2oat_exec,
                "file", "execute_no_trans")) {
            SELinux.setFileContext(WRAPPER, dex2oat_exec);
            setSockCreateContext("u:r:dex2oat:s0");
        } else {
            SELinux.setFileContext(WRAPPER, magisk_file);
            setSockCreateContext("u:r:installd:s0");
        }
    }

    private void serveClients(LocalServerSocket server) {
        while (!Thread.currentThread().isInterrupted()) {
            try (var client = server.accept();
                 var is = client.getInputStream();
                 var os = client.getOutputStream()) {
                handleClient(client, is, os);
            } catch (IOException e) {
                if (!Thread.currentThread().isInterrupted()) {
                    Log.e(TAG, "Client handling error", e);
                }
                break;
            }
        }
    }

    private void handleClient(LocalSocket client, InputStream is, OutputStream os) throws IOException {
        var id = is.read();
        if (id < 0 || id >= fdArray.length || fdArray[id] == null) {
            if (id == 1 || id == 3) {
                int fallbackId = id - 1;
                if (fallbackId >= 0 && fallbackId < fdArray.length && fdArray[fallbackId] != null) {
                    id = fallbackId;
                } else {
                    return;
                }
            } else {
                return;
            }
        }
        
        var fd = new FileDescriptor[]{fdArray[id]};
        client.setFileDescriptorsForSend(fd);
        os.write(1);
    }

    private void handleServiceCrash(IOException e) {
        Log.e(TAG, "Dex2oat wrapper daemon crashed", e);
        if (compatibility == DEX2OAT_OK) {
            doMount(false);
            compatibility = DEX2OAT_CRASHED;
        }
    }

    public int getCompatibility() {
        return compatibility;
    }

    @Override
    public void close() {
        if (serviceThread != null && serviceThread.isAlive()) {
            serviceThread.interrupt();
            try {
                serviceThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        if (selinuxObserver != null) {
            selinuxObserver.stopWatching();
        }
        
        for (int i = 0; i < fdArray.length; i++) {
            if (fdArray[i] != null && fdArray[i].valid()) {
                try {
                    Os.close(fdArray[i]);
                    fdArray[i] = null;
                } catch (ErrnoException e) {
                    Log.e(TAG, "Failed to close FD at index " + i, e);
                }
            }
        }
    }

    private native void doMountNative(boolean enabled,
                                      String r32, String d32, String r64, String d64);

    private static native boolean setSockCreateContext(String context);

    private native String getSockPath();
}
