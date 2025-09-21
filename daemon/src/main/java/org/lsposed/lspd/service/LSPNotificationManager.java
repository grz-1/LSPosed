package org.lsposed.lspd.service;

import static org.lsposed.lspd.service.ServiceManager.TAG;

import android.annotation.SuppressLint;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import org.lsposed.daemon.R;
import org.lsposed.lspd.util.FakeContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.service.IXposedScopeCallback;

public class LSPNotificationManager {
    static final String UPDATED_CHANNEL_ID = "lsposed_module_updated";
    static final String SCOPE_CHANNEL_ID = "lsposed_module_scope";
    private static final String STATUS_CHANNEL_ID = "lsposed_status";
    private static final int STATUS_NOTIFICATION_ID = 2000;
    private static final String ANDROID_PACKAGE = "android";
    private static final int SYSTEM_UID = 1000;
    
    private static final String opPkg = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ?
            ANDROID_PACKAGE : "com.android.settings";

    private static final Map<String, Integer> notificationIds = new ConcurrentHashMap<>();
    private static int previousNotificationId = STATUS_NOTIFICATION_ID;

    static final String openManagerAction = UUID.randomUUID().toString();
    static final String moduleScope = UUID.randomUUID().toString();

    private static INotificationManager notificationManager = null;
    private static IBinder binder = null;

    static {
        initializeNotificationSystem();
    }
    
    private static void initializeNotificationSystem() {
        try {
            @SuppressLint("PrivateApi")
            Class<?> flagsClass = Class.forName("android.app.Flags", false, null);
            Field featureFlagsField = flagsClass.getDeclaredField("FEATURE_FLAGS");
            featureFlagsField.setAccessible(true);
            Object featureFlags = featureFlagsField.get(null);
            Field cacheField;
            if (featureFlags != null) {
                cacheField = featureFlags.getClass().getDeclaredField("systemui_is_cached");
                cacheField.setAccessible(true);
                cacheField.set(featureFlags, Boolean.TRUE);
                Log.d(TAG, "Set systemui_is_cached flag");
            }
        } catch (Throwable ignored) {
        }
        
        try {
            Class<?> ConfigClass = Class.forName("com.mediatek.res.AsyncDrawableCache", false, null);
            Field featureConfigField = ConfigClass.getDeclaredField("sFeatureConfig");
            featureConfigField.setAccessible(true);
            featureConfigField.set(null, "0");
            Log.d(TAG, "Set sFeatureConfig for AsyncDrawableCache");
        } catch (Throwable ignored) {
        }
        
        try {
            new Notification.Builder(new FakeContext(), "LSPosed").build();
            Log.d(TAG, "Notification builder test successful");
        } catch (AbstractMethodError unused) {
            FakeContext.ContentResolver = !FakeContext.ContentResolver;
            Log.d(TAG, "Flipped ContentResolver flag due to AbstractMethodError");
        } catch (Throwable th) {
            Log.e(TAG, "Failed to initialize notification system", th);
        }
    }

    private static final IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "Notification manager service died");
            if (binder != null) {
                binder.unlinkToDeath(this, 0);
            }
            binder = null;
            notificationManager = null;
        }
    };

    private static synchronized INotificationManager getNotificationManager() throws RemoteException {
        if (binder == null || notificationManager == null) {
            Log.d(TAG, "Acquiring notification manager service");
            binder = android.os.ServiceManager.getService(Context.NOTIFICATION_SERVICE);
            if (binder != null) {
                binder.linkToDeath(recipient, 0);
                notificationManager = INotificationManager.Stub.asInterface(binder);
                Log.d(TAG, "Notification manager service acquired successfully");
            } else {
                Log.e(TAG, "Failed to get notification service");
                throw new RemoteException("Failed to get notification service");
            }
        }
        return notificationManager;
    }

    private static Bitmap getBitmap(int id) {
        var r = ConfigFileManager.getResources();
        if (r == null) {
            Log.e(TAG, "Resources are null");
            return null;
        }
        
        try {
            var res = r.getDrawable(id, r.newTheme());
            if (res instanceof BitmapDrawable) {
                Log.d(TAG, "Got bitmap drawable for resource: " + id);
                return ((BitmapDrawable) res).getBitmap();
            } else {
                if (res instanceof AdaptiveIconDrawable) {
                    Log.d(TAG, "Converting AdaptiveIconDrawable to LayerDrawable");
                    var layers = new Drawable[]{((AdaptiveIconDrawable) res).getBackground(),
                            ((AdaptiveIconDrawable) res).getForeground()};
                    res = new LayerDrawable(layers);
                }
                
                int width = Math.max(res.getIntrinsicWidth(), 1);
                int height = Math.max(res.getIntrinsicHeight(), 1);
                
                Log.d(TAG, "Creating bitmap from drawable: " + width + "x" + height);
                var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                var canvas = new Canvas(bitmap);
                res.setBounds(0, 0, width, height);
                res.draw(canvas);
                return bitmap;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get bitmap for resource: " + id, e);
            return null;
        }
    }

    private static Icon getNotificationIcon() {
        Bitmap bitmap = getBitmap(R.drawable.ic_notification);
        if (bitmap != null) {
            Log.d(TAG, "Created notification icon from bitmap");
            return Icon.createWithBitmap(bitmap);
        } else {
            Log.e(TAG, "Failed to create notification icon from bitmap, using resource fallback");
            return Icon.createWithResource(new FakeContext(), R.drawable.ic_notification);
        }
    }

    private static boolean hasNotificationChannelForSystem(
            INotificationManager nm, String channelId) throws RemoteException {
        NotificationChannel channel;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            channel = nm.getNotificationChannelForPackage(ANDROID_PACKAGE, SYSTEM_UID, channelId, null, false);
        } else {
            channel = nm.getNotificationChannelForPackage(ANDROID_PACKAGE, SYSTEM_UID, channelId, false);
        }
        
        if (channel != null) {
            Log.d(TAG, "Found existing notification channel: " + channelId);
        } else {
            Log.d(TAG, "Notification channel not found: " + channelId);
        }
        return channel != null;
    }

    private static void createNotificationChannel(INotificationManager nm) throws RemoteException {
        Log.d(TAG, "Creating/updating notification channels");
        var context = new FakeContext();
        var list = new ArrayList<NotificationChannel>();

        addOrUpdateChannel(nm, list, UPDATED_CHANNEL_ID, 
                context.getString(R.string.module_updated_channel_name), 
                NotificationManager.IMPORTANCE_HIGH);
                
        addOrUpdateChannel(nm, list, STATUS_CHANNEL_ID, 
                context.getString(R.string.status_channel_name), 
                NotificationManager.IMPORTANCE_MIN);
                
        addOrUpdateChannel(nm, list, SCOPE_CHANNEL_ID, 
                context.getString(R.string.scope_channel_name), 
                NotificationManager.IMPORTANCE_HIGH);

        if (!list.isEmpty()) {
            Log.d(TAG, "Creating " + list.size() + " notification channels");
            nm.createNotificationChannelsForPackage(ANDROID_PACKAGE, SYSTEM_UID, new ParceledListSlice<>(list));
        } else {
            Log.d(TAG, "All notification channels already exist, no need to create");
        }
    }
    
    private static void addOrUpdateChannel(INotificationManager nm, ArrayList<NotificationChannel> list,
                                          String channelId, String name, int importance) throws RemoteException {
        var channel = new NotificationChannel(channelId, name, importance);
        channel.setShowBadge(false);
        
        if (hasNotificationChannelForSystem(nm, channelId)) {
            Log.d(TAG, "Updating notification channel: " + channelId);
            nm.updateNotificationChannelForPackage(ANDROID_PACKAGE, SYSTEM_UID, channel);
        } else {
            Log.d(TAG, "Adding notification channel to creation list: " + channelId);
            list.add(channel);
        }
    }

    static void notifyStatusNotification() {
        Log.d(TAG, "Showing status notification");
        var context = new FakeContext();
        var notification = buildStatusNotification(context);
        
        if (notification == null) {
            Log.e(TAG, "Failed to build status notification");
            return;
        }
        
        try {
            var nm = getNotificationManager();
            createNotificationChannel(nm);
            nm.enqueueNotificationWithTag(ANDROID_PACKAGE, opPkg, null,
                    STATUS_NOTIFICATION_ID, notification, 0);
            Log.d(TAG, "Status notification enqueued successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to show status notification", e);
        }
    }
    
    private static Notification buildStatusNotification(Context context) {
        try {
            var intent = new Intent(openManagerAction);
            intent.setPackage(ANDROID_PACKAGE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            
            Icon icon = getNotificationIcon();
            if (icon == null) {
                Log.e(TAG, "Failed to get notification icon for status notification");
                return null;
            }
            
            var builder = new Notification.Builder(context, STATUS_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.lsposed_running_notification_title))
                    .setContentText(context.getString(R.string.lsposed_running_notification_content))
                    .setSmallIcon(icon)
                    .setContentIntent(PendingIntent.getBroadcast(context, 1, intent, flags))
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setColor(0xFFF48FB1)
                    .setOngoing(true)
                    .setAutoCancel(false);
            
            Notification notification = builder.build();
            notification.extras.putString("android.substName", "LSPosed");
            Log.d(TAG, "Status notification built successfully");
            return notification;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build status notification", e);
            return null;
        }
    }

    static void notifyStatusNotificationSafeMode() {
        Log.d(TAG, "Showing status notification(safemode)");
        var context = new FakeContext();
        var notification = buildStatusNotificationSafeMode(context);

        if (notification == null) {
            Log.e(TAG, "Failed to build status notification(safemode)");
            return;
        }

        try {
            var nm = getNotificationManager();
            createNotificationChannel(nm);
            nm.enqueueNotificationWithTag(ANDROID_PACKAGE, opPkg, null,
                    STATUS_NOTIFICATION_ID, notification, 0);
            Log.d(TAG, "Status notification enqueued successfully(safemode)");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to show status notification(safemode)", e);
        }
    }

    private static Notification buildStatusNotificationSafeMode(Context context) {
        try {
            var intent = new Intent(openManagerAction);
            intent.setPackage(ANDROID_PACKAGE);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

            Icon icon = getNotificationIcon();
            if (icon == null) {
                Log.e(TAG, "Failed to get notification icon for status notification");
                return null;
            }

            var builder = new Notification.Builder(context, STATUS_CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.lsposed_safemode_notification_title))
                    .setContentText(context.getString(R.string.lsposed_safemode_notification_content))
                    .setSmallIcon(icon)
                    .setContentIntent(PendingIntent.getBroadcast(context, 1, intent, flags))
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setColor(0xFFF48FB1)
                    .setOngoing(true)
                    .setAutoCancel(false);

            Notification notification = builder.build();
            notification.extras.putString("android.substName", "LSPosed");
            Log.d(TAG, "Status notification built successfully");
            return notification;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build status notification", e);
            return null;
        }
    }

    static void cancelStatusNotification() {
        Log.d(TAG, "Cancelling status notification");
        try {
            var nm = getNotificationManager();
            createNotificationChannel(nm);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nm.cancelNotificationWithTag(ANDROID_PACKAGE, ANDROID_PACKAGE, null, STATUS_NOTIFICATION_ID, 0);
            } else {
                nm.cancelNotificationWithTag(ANDROID_PACKAGE, null, STATUS_NOTIFICATION_ID, 0);
            }
            Log.d(TAG, "Status notification cancelled successfully");
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to cancel status notification", e);
        }
    }

    private static PendingIntent getModuleIntent(String modulePackageName, int moduleUserId) {
        try {
            var intent = new Intent(openManagerAction);
            intent.setPackage(ANDROID_PACKAGE);
            intent.setData(new Uri.Builder().scheme("module").encodedAuthority(modulePackageName + ":" + moduleUserId).build());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Log.d(TAG, "Creating module intent for: " + modulePackageName + ":" + moduleUserId);
            return PendingIntent.getBroadcast(new FakeContext(), 3, intent, flags);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create module intent for: " + modulePackageName + ":" + moduleUserId, e);
            return null;
        }
    }

    private static PendingIntent getModuleScopeIntent(String modulePackageName, int moduleUserId, 
                                                     String scopePackageName, String action, 
                                                     IXposedScopeCallback callback) {
        try {
            var intent = new Intent(moduleScope);
            intent.setPackage(ANDROID_PACKAGE);
            intent.setData(new Uri.Builder().scheme("module")
                    .encodedAuthority(modulePackageName + ":" + moduleUserId)
                    .encodedPath(scopePackageName)
                    .appendQueryParameter("action", action).build());
                    
            var extras = new Bundle();
            extras.putBinder("callback", callback.asBinder());
            intent.putExtras(extras);
            
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            Log.d(TAG, "Creating module scope intent for: " + modulePackageName + ":" + moduleUserId + 
                  ", scope: " + scopePackageName + ", action: " + action);
            return PendingIntent.getBroadcast(new FakeContext(), 4, intent, flags);
        } catch (Exception e) {
            Log.e(TAG, "Failed to create module scope intent for: " + modulePackageName + ":" + moduleUserId + 
                  ", scope: " + scopePackageName, e);
            return null;
        }
    }

    private static String getNotificationIdKey(String channel, String modulePackageName, int moduleUserId) {
        return channel + "/" + modulePackageName + ":" + moduleUserId;
    }

    private static synchronized int pushAndGetNotificationId(String channel, String modulePackageName, int moduleUserId) {
        var idKey = getNotificationIdKey(channel, modulePackageName, moduleUserId);
        int newId = notificationIds.computeIfAbsent(idKey, key -> {
            Log.d(TAG, "Assigning new notification ID: " + previousNotificationId + " for key: " + idKey);
            return previousNotificationId++;
        });
        Log.d(TAG, "Using notification ID: " + newId + " for key: " + idKey);
        return newId;
    }

    static void notifyModuleUpdated(String modulePackageName,
                                    int moduleUserId,
                                    boolean enabled,
                                    boolean systemModule) {
        Log.d(TAG, "Notifying module updated: " + modulePackageName + ":" + moduleUserId + 
              ", enabled: " + enabled + ", systemModule: " + systemModule);
        var context = new FakeContext();
        var notification = buildModuleUpdatedNotification(context, modulePackageName, moduleUserId, enabled, systemModule);
        
        if (notification == null) {
            Log.e(TAG, "Failed to build module updated notification");
            return;
        }
        
        try {
            var nm = getNotificationManager();
            int notificationId = pushAndGetNotificationId(UPDATED_CHANNEL_ID, modulePackageName, moduleUserId);
            nm.enqueueNotificationWithTag(ANDROID_PACKAGE, opPkg, modulePackageName, notificationId, notification, 0);
            Log.d(TAG, "Module updated notification enqueued successfully, ID: " + notificationId);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to notify module updated", e);
        }
    }
    
    private static Notification buildModuleUpdatedNotification(Context context, String modulePackageName,
                                                              int moduleUserId, boolean enabled, boolean systemModule) {
        try {
            var userName = UserService.getUserName(moduleUserId);
            String title = context.getString(enabled ? systemModule ?
                    R.string.xposed_module_updated_notification_title_system :
                    R.string.xposed_module_updated_notification_title :
                    R.string.module_is_not_activated_yet);
                    
            String content = context.getString(enabled ? systemModule ?
                    R.string.xposed_module_updated_notification_content_system :
                    R.string.xposed_module_updated_notification_content :
                    (moduleUserId == 0 ?
                            R.string.module_is_not_activated_yet_main_user_detailed :
                            R.string.module_is_not_activated_yet_multi_user_detailed), modulePackageName, userName);

            var style = new Notification.BigTextStyle();
            style.bigText(content);
            
            PendingIntent intent = getModuleIntent(modulePackageName, moduleUserId);
            if (intent == null) {
                Log.e(TAG, "Failed to get module intent for notification");
                return null;
            }
            
            Icon icon = getNotificationIcon();
            if (icon == null) {
                Log.e(TAG, "Failed to get notification icon for module updated notification");
                return null;
            }

            var builder = new Notification.Builder(context, UPDATED_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(icon)
                    .setContentIntent(intent)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setColor(0xFFF48FB1)
                    .setAutoCancel(true)
                    .setStyle(style);
                    
            Notification notification = builder.build();
            notification.extras.putString("android.substName", "LSPosed");
            Log.d(TAG, "Module updated notification built successfully");
            return notification;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build module updated notification", e);
            return null;
        }
    }

    static void requestModuleScope(String modulePackageName, int moduleUserId, 
                                  String scopePackageName, IXposedScopeCallback callback) {
        Log.d(TAG, "Requesting module scope: " + modulePackageName + ":" + moduleUserId + 
              ", scope: " + scopePackageName);
        var context = new FakeContext();
        var notification = buildScopeRequestNotification(context, modulePackageName, moduleUserId, scopePackageName, callback);
        
        if (notification == null) {
            Log.e(TAG, "Failed to build scope request notification");
            try {
                callback.onScopeRequestFailed(scopePackageName, "Failed to build notification");
            } catch (RemoteException ignored) {
            }
            return;
        }
        
        try {
            var nm = getNotificationManager();
            int notificationId = pushAndGetNotificationId(SCOPE_CHANNEL_ID, modulePackageName, moduleUserId);
            nm.enqueueNotificationWithTag(ANDROID_PACKAGE, opPkg, modulePackageName, notificationId, notification, 0);
            Log.d(TAG, "Scope request notification enqueued successfully, ID: " + notificationId);
        } catch (RemoteException e) {
            try {
                callback.onScopeRequestFailed(scopePackageName, e.getMessage());
            } catch (RemoteException ignored) {
            }
            Log.e(TAG, "Failed to request module scope", e);
        }
    }
    
    private static Notification buildScopeRequestNotification(Context context, String modulePackageName,
                                                            int moduleUserId, String scopePackageName, 
                                                            IXposedScopeCallback callback) {
        try {
            var userName = UserService.getUserName(moduleUserId);
            String title = context.getString(R.string.xposed_module_request_scope_title);
            String content = context.getString(R.string.xposed_module_request_scope_content, 
                    modulePackageName, userName, scopePackageName);

            var style = new Notification.BigTextStyle();
            style.bigText(content);
            
            PendingIntent deleteIntent = getModuleScopeIntent(modulePackageName, moduleUserId, 
                    scopePackageName, "delete", callback);
            PendingIntent approveIntent = getModuleScopeIntent(modulePackageName, moduleUserId, 
                    scopePackageName, "approve", callback);
            PendingIntent denyIntent = getModuleScopeIntent(modulePackageName, moduleUserId, 
                    scopePackageName, "deny", callback);
            PendingIntent blockIntent = getModuleScopeIntent(modulePackageName, moduleUserId, 
                    scopePackageName, "block", callback);
                    
            if (deleteIntent == null || approveIntent == null || denyIntent == null || blockIntent == null) {
                Log.e(TAG, "Failed to create one or more scope intent actions");
                return null;
            }
            
            Icon icon = getNotificationIcon();
            if (icon == null) {
                Log.e(TAG, "Failed to get notification icon for scope request notification");
                return null;
            }

            var builder = new Notification.Builder(context, SCOPE_CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setSmallIcon(icon)
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setColor(0xFFF48FB1)
                    .setAutoCancel(true)
                    .setTimeoutAfter(1000 * 60 * 60)
                    .setStyle(style)
                    .setDeleteIntent(deleteIntent)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_baseline_check_24),
                            context.getString(R.string.scope_approve),
                            approveIntent).build())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_baseline_close_24),
                            context.getString(R.string.scope_deny),
                            denyIntent).build())
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(context, R.drawable.ic_baseline_block_24),
                            context.getString(R.string.nerver_ask_again),
                            blockIntent).build());
                    
            Notification notification = builder.build();
            notification.extras.putString("android.substName", "LSPosed");
            Log.d(TAG, "Scope request notification built successfully");
            return notification;
        } catch (Exception e) {
            Log.e(TAG, "Failed to build scope request notification", e);
            return null;
        }
    }

    static void cancelNotification(String channel, String modulePackageName, int moduleUserId) {
        Log.d(TAG, "Cancelling notification for channel: " + channel + 
              ", module: " + modulePackageName + ":" + moduleUserId);
        try {
            var idKey = getNotificationIdKey(channel, modulePackageName, moduleUserId);
            Integer idValue;
            
            synchronized (LSPNotificationManager.class) {
                idValue = notificationIds.get(idKey);
                if (idValue == null) {
                    Log.d(TAG, "No notification ID found for key: " + idKey);
                    return;
                }
            }
            
            var nm = getNotificationManager();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                nm.cancelNotificationWithTag(ANDROID_PACKAGE, ANDROID_PACKAGE, modulePackageName, idValue, 0);
            } else {
                nm.cancelNotificationWithTag(ANDROID_PACKAGE, modulePackageName, idValue, 0);
            }
            
            synchronized (LSPNotificationManager.class) {
                notificationIds.remove(idKey);
            }
            Log.d(TAG, "Notification cancelled successfully, ID: " + idValue);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to cancel notification", e);
        }
    }
}
