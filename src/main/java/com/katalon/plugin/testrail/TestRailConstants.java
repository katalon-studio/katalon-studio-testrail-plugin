package com.katalon.plugin.testrail;

public interface TestRailConstants {
    String PLUGIN_ID = "com.katalon.katalon-studio-testrail-plugin";
    
    String PREF_PAGE_ID = "com.katalon.plugin.testrail.TestRailPluginPreferencePage";

    String PREF_TESTRAIL_ENABLED = "testRail.isTestRailEnabled";

    String PREF_TESTRAIL_USERNAME = "testRail.username";

    String PREF_TESTRAIL_PASSWORD = "testRail.password";

    String PREF_TESTRAIL_URL = "testRail.url";

    String PREF_TESTRAIL_PROJECT = "testRail.project";

    String INTEGRATION_ID = "com.katalon.plugin.testrail.TestRailIntegration";

    String INTEGRATION_TESTCASE_ID = "testRail.testcase.id";

    String IS_ENCRYPTION_MIGRATED = "testRail.isEncryptionMigrated";

    // TestRailPreferencePage
    String LBL_WARNING_PASSWORD = "* If you open this project in Katalon Studio version before 8.5.5, you might need to re-enter your TestRail password.";
}
