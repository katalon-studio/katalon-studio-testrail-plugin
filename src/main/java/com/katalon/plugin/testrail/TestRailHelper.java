package com.katalon.plugin.testrail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.preference.PluginPreference;

public class TestRailHelper {

    public static String parseId(String text, String patternString) {
        String[] splitText = text.split("/");
        String name = splitText[splitText.length - 1];

        Pattern pattern = Pattern.compile(patternString);
        Matcher matcher = pattern.matcher(name);
        if (matcher.find()) {
            return matcher.group(1);
        } else {
            System.out.println("Not found ID in " + text);
            return "";
        }
    }

    public static void doEncrytionMigrated(PluginPreference preferences) throws CryptoException, ResourceException {
        // Detect that the password in the previous version is encrypted based on the property
        // "IS_ENCRYPTION_MIGRATED". Do encrypt password and reset value of "IS_ENCRYPTION_MIGRATED" if not encrypted.
        boolean isEncryptionMigrated = preferences.getBoolean(TestRailConstants.IS_ENCRYPTION_MIGRATED, false);
        if (!isEncryptionMigrated) {
            String rawPass = preferences.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "");
            preferences.setString(TestRailConstants.PREF_TESTRAIL_PASSWORD, rawPass, true);
            preferences.setBoolean(TestRailConstants.IS_ENCRYPTION_MIGRATED, true);
            preferences.save();
        }
    }

}
