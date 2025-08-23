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

package org.lsposed.manager.ui.fragment;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.lsposed.manager.R;

public class NewFake extends PreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.prefs, rootKey);
        Preference dumpInfoPreference = findPreference("fakedumphookdebuginfo");

        if (dumpInfoPreference != null) {
            dumpInfoPreference.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(@NonNull Preference preference) {
                    dumpDebugInfo();
                    return true;
                }
            });
        }
    }

    private void dumpDebugInfo() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
        String timestamp = now.format(formatter);

        String fileName = "/data/adb/lspd/log/dump_hook_debug_" + timestamp + ".log";

        Fragment parent = getParentFragment();
        if (parent instanceof BaseFragment) {
            ((BaseFragment) parent).showHint(getString(R.string.settings_fake_dumped, fileName), true);
        }
    }
}
