package com.tomilabs.lorasignalmap;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.MenuItem;
import android.widget.Toast;

import com.tomilabs.lorasignalmap.log.LogRecords;
import com.tomilabs.lorasignalmap.log.SessionStore;

/** Settings screen built from res/xml/preferences.xml (framework preferences, no extra libraries). */
@SuppressWarnings("deprecation")
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings);
        }
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                    .replace(android.R.id.content, new SettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static final class SettingsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private static final String[] TEXT_KEYS = {
                Prefs.SIGNAL_BEST, Prefs.SIGNAL_WORST, Prefs.SNR_BEST, Prefs.SNR_WORST,
                Prefs.WIFI_SSID, Prefs.REPEATER_LAT, Prefs.REPEATER_LON};
        private static final String[] LIST_KEYS = {Prefs.MAP_STYLE, Prefs.COLOR_METRIC, Prefs.COLOR_DIRECTION};

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            getPreferenceManager().setSharedPreferencesName(getActivity().getPackageName() + "_preferences");
            addPreferencesFromResource(R.xml.preferences);
            Preference clear = findPreference("repeater_clear");
            if (clear != null) {
                clear.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        SharedPreferences.Editor e = getPreferenceManager().getSharedPreferences().edit();
                        e.remove(Prefs.REPEATER_LAT).remove(Prefs.REPEATER_LON).apply();
                        resetText(Prefs.REPEATER_LAT);
                        resetText(Prefs.REPEATER_LON);
                        updateSummaries();
                        return true;
                    }
                });
            }
            setSummary("about_version", BuildInfo.VERSION_NAME + " (" + BuildInfo.VERSION_CODE + ")");
            setSummary("about_folder", SessionStore.dir(getActivity()).getAbsolutePath());
            setSummary("about_format", LogRecords.FORMAT + " v" + LogRecords.VERSION + " (JSON Lines)");
            updateSummaries();
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
            updateSummaries();
        }

        @Override
        public void onPause() {
            getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
            super.onPause();
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            updateSummaries();
            if (Prefs.WIFI_SSID.equals(key) || Prefs.WIFI_PASS.equals(key)) {
                String ssid = sp.getString(Prefs.WIFI_SSID, "");
                String pass = sp.getString(Prefs.WIFI_PASS, "");
                if ((ssid != null && ssid.trim().length() > 32)
                        || (pass != null && !pass.isEmpty() && (pass.length() < 8 || pass.length() > 63))) {
                    Toast.makeText(getActivity(), R.string.pref_wifi_invalid, Toast.LENGTH_LONG).show();
                }
            }
        }

        private void resetText(String key) {
            Preference p = findPreference(key);
            if (p instanceof EditTextPreference) {
                ((EditTextPreference) p).setText(null);
            }
        }

        private void setSummary(String key, String summary) {
            Preference p = findPreference(key);
            if (p != null) {
                p.setSummary(summary);
            }
        }

        private void updateSummaries() {
            SharedPreferences sp = getPreferenceManager().getSharedPreferences();
            for (String key : TEXT_KEYS) {
                String v = sp.getString(key, null);
                setSummary(key, v == null || v.trim().isEmpty() ? getString(R.string.not_set) : v);
            }
            for (String key : LIST_KEYS) {
                Preference p = findPreference(key);
                if (p instanceof ListPreference) {
                    CharSequence entry = ((ListPreference) p).getEntry();
                    p.setSummary(entry != null ? entry : "");
                }
            }
            String pass = sp.getString(Prefs.WIFI_PASS, "");
            setSummary(Prefs.WIFI_PASS, pass == null || pass.isEmpty() ? getString(R.string.not_set) : "••••••••");
        }
    }
}
