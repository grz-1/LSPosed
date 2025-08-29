/*
 * <!--This file is part of LSPosed.
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
 * Copyright (C) 2021 LSPosed Contributors-->
 */

package org.lsposed.manager.ui.activity;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.lsposed.manager.App;
import org.lsposed.manager.ConfigManager;
import org.lsposed.manager.R;
import org.lsposed.manager.databinding.ActivityMainBinding;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.ui.activity.base.BaseActivity;
import org.lsposed.manager.util.ModuleUtil;
import org.lsposed.manager.util.UpdateUtil;

import java.util.HashSet;
import java.util.Objects;

public class MainActivity extends BaseActivity
        implements RepoLoader.RepoListener, ModuleUtil.ModuleListener {

    private static final String KEY_PREFIX
            = MainActivity.class.getName() + '.';
    private static final String EXTRA_SAVED_INSTANCE_STATE
            = KEY_PREFIX + "SAVED_INSTANCE_STATE";

    private static final RepoLoader repoLoader = RepoLoader.getInstance();
    private static final ModuleUtil moduleUtil = ModuleUtil.getInstance();

    private boolean restarting;
    private ActivityMainBinding binding;

    @NonNull
    public static Intent newIntent(@NonNull Context context) {
        return new Intent(context, MainActivity.class);
    }

    @NonNull
    private static Intent newIntent(
            @NonNull Bundle savedState,
            @NonNull Context context) {
        return newIntent(context)
                .putExtra(EXTRA_SAVED_INSTANCE_STATE, savedState);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            savedInstanceState = getIntent()
                    .getBundleExtra(EXTRA_SAVED_INSTANCE_STATE);
        }
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repoLoader.addListener(this);
        moduleUtil.addListener(this);
        onModulesReloaded();

        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            BottomNavigationView nav = binding.nav;
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(nav, navController);
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;

        NavHostFragment navHostFragment =
                (NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment == null) return;

        BottomNavigationView nav = binding.nav;
        NavController navController = navHostFragment.getNavController();

        if ("android.intent.action.APPLICATION_PREFERENCES"
                .equals(intent.getAction())) {
            nav.setSelectedItemId(R.id.settings_fragment);
            return;
        }

        if (!ConfigManager.isBinderAlive()
                || TextUtils.isEmpty(intent.getDataString())) {
            return;
        }

        String dataString = intent.getDataString();
        switch (dataString) {
            case "modules":
                nav.setSelectedItemId(R.id.modules_nav);
                return;
            case "logs":
                nav.setSelectedItemId(R.id.logs_fragment);
                return;
            case "repo":
                if (ConfigManager.isMagiskInstalled()) {
                    nav.setSelectedItemId(R.id.repo_nav);
                }
                return;
            case "settings":
                nav.setSelectedItemId(R.id.settings_fragment);
                return;
            default:
                Uri data = intent.getData();
                if (data != null && "module".equals(data.getScheme())) {
                    Uri target = new Uri.Builder()
                            .scheme("lsposed")
                            .authority("module")
                            .appendQueryParameter(
                                    "modulePackageName",
                                    data.getHost())
                            .appendQueryParameter(
                                    "moduleUserId",
                                    String.valueOf(data.getPort()))
                            .build();
                    NavOptions opts = new NavOptions.Builder()
                            .setEnterAnim(R.anim.fragment_enter)
                            .setExitAnim(R.anim.fragment_exit)
                            .setPopEnterAnim(R.anim.fragment_enter_pop)
                            .setPopExitAnim(R.anim.fragment_exit_pop)
                            .setLaunchSingleTop(true)
                            .setPopUpTo(
                                navController.getGraph()
                                             .getStartDestinationId(),
                                false, true)
                            .build();
                    navController.navigate(target, opts);
                }
        }
    }

    public void restart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                || App.isParasitic) {
            recreate();
            return;
        }
        try {
            Bundle state = new Bundle();
            onSaveInstanceState(state);
            finish();
            startActivity(newIntent(state, this));
            overridePendingTransition(
                    android.R.anim.fade_in,
                    android.R.anim.fade_out);
            restarting = true;
        } catch (Throwable e) {
            recreate();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return restarting || super.dispatchKeyEvent(event);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return restarting || super.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return restarting || super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent ev) {
        return restarting || super.dispatchTrackballEvent(ev);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent ev) {
        return restarting || super.dispatchGenericMotionEvent(ev);
    }

    @Override
    public void onResume() {
        super.onResume();

        int enabledCount = ConfigManager.isBinderAlive()
                ? moduleUtil.getEnabledModulesCount() : 0;
        setModulesBadge(enabledCount);

        BottomNavigationView nav = binding.nav;
        if (UpdateUtil.needUpdate()) {
            BadgeDrawable mainBadge = nav.getOrCreateBadge(R.id.main_fragment);
            mainBadge.setVisible(true);
        }

        if (!ConfigManager.isBinderAlive()) {
            nav.getMenu().removeItem(R.id.logs_fragment);
            nav.getMenu().removeItem(R.id.modules_nav);
            if (!ConfigManager.isMagiskInstalled()) {
                nav.getMenu().removeItem(R.id.repo_nav);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onRepoLoaded() {
        final int[] count = {0};
        var modules = moduleUtil.getModules();
        if (modules != null) {
            HashSet<String> seen = new HashSet<>();
            modules.forEach((key, info) -> {
                if (!seen.contains(key.first)) {
                    var latest = repoLoader.getModuleLatestVersion(key.first);
                    if (latest != null &&
                        latest.upgradable(info.versionCode, info.versionName)) {
                        count[0]++;
                    }
                    seen.add(key.first);
                }
            });
        }

        runOnUiThread(() -> {
            BottomNavigationView nav = binding.nav;
            BadgeDrawable badge = nav.getOrCreateBadge(R.id.repo_nav);
            if (count[0] > 0) {
                badge.setVisible(true);
                badge.setNumber(count[0]);
            } else {
                badge.setVisible(false);
            }
        });
    }

    @Override
    public void onThrowable(Throwable t) {
        runOnUiThread(() -> {
            BottomNavigationView nav = binding.nav;
            BadgeDrawable badge = nav.getOrCreateBadge(R.id.repo_nav);
            badge.setVisible(false);
        });
    }

    @Override
    public void onModulesReloaded() {
        onRepoLoaded();
        setModulesBadge(moduleUtil.getEnabledModulesCount());
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment);
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        repoLoader.removeListener(this);
        moduleUtil.removeListener(this);
    }

    private void setModulesBadge(int count) {
        BottomNavigationView nav = binding.nav;
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.modules_nav);
        if (count > 0) {
            badge.setVisible(true);
            badge.setNumber(count);
        } else {
            badge.setVisible(false);
        }
    }
}