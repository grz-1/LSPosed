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

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.NavigationUI;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import org.lsposed.manager.R;
import org.lsposed.manager.databinding.ActivityMainBinding;
import org.lsposed.manager.repo.RepoLoader;
import org.lsposed.manager.util.ModuleUtil;

public class MainActivity extends BaseActivity
        implements RepoLoader.Listener, ModuleUtil.Listener {

    private ActivityMainBinding binding;
    private RepoLoader repoLoader;
    private ModuleUtil moduleUtil;
    private NavHostFragment navHostFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        repoLoader = RepoLoader.getInstance();
        moduleUtil = ModuleUtil.getInstance();
        repoLoader.addListener(this);
        moduleUtil.addListener(this);
        onModulesReloaded();

        BottomNavigationView nav = findViewById(R.id.nav);

        navHostFragment = (NavHostFragment)
                getSupportFragmentManager().findFragmentById(R.id.nav_host_fragment);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(nav, navController);
            handleIntent(getIntent());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        BottomNavigationView nav = findViewById(R.id.nav);
        if (navHostFragment != null) {
            NavController navController = navHostFragment.getNavController();
            NavigationUI.setupWithNavController(nav, navController);
        }
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(@NonNull Intent intent) {
        if (navHostFragment == null) return;

        BottomNavigationView nav = findViewById(R.id.nav);
        NavController navController = navHostFragment.getNavController();

        String action = intent.getAction();
        if ("org.lsposed.OPEN_REPO".equals(action)) {
            navController.navigate(R.id.repo_nav);
        } else if ("org.lsposed.OPEN_MODULES".equals(action)) {
            navController.navigate(R.id.modules_nav);
        }
    }

    @Override
    public void onRepoLoaded() {
        BottomNavigationView nav = findViewById(R.id.nav);
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.repo_nav);
        badge.setVisible(true);
        badge.setNumber(repoLoader.getRepoCount());
    }

    @Override
    public void onThrowable(@NonNull Throwable t) {
        BottomNavigationView nav = findViewById(R.id.nav);
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.repo_nav);
        badge.setVisible(true);
        badge.setNumber(0);
    }

    @Override
    public void onModulesReloaded() {
        setModulesBadge(moduleUtil.getEnabledModulesCount());
    }

    private void setModulesBadge(int count) {
        BottomNavigationView nav = findViewById(R.id.nav);
        BadgeDrawable badge = nav.getOrCreateBadge(R.id.modules_nav);

        if (count > 0) {
            badge.setVisible(true);
            badge.setNumber(count);
        } else {
            badge.setVisible(false);
        }
    }
}