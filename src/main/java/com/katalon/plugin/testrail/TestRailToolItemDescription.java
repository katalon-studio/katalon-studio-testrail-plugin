package com.katalon.plugin.testrail;

import com.katalon.platform.api.extension.ToolItemDescription;
import com.katalon.platform.api.service.ApplicationManager;
import com.katalon.platform.api.ui.DialogActionService;

public class TestRailToolItemDescription implements ToolItemDescription {

    @Override
    public String name() {
        return "TestRail";
    }

    @Override
    public String toolItemId() {
        return TestRailConstants.PLUGIN_ID + ".testRailToolItem";
    }

    @Override
    public String iconUrl() {
        return "platform:/plugin/" + TestRailConstants.PLUGIN_ID + "/icons-v2/testrail.svg";
    }

    @Override
    public void handleEvent() {
        ApplicationManager.getInstance().getUIServiceManager().getService(DialogActionService.class).openPluginPreferencePage(
                TestRailConstants.PREF_PAGE_ID);
    }

    @Override
    public boolean isItemEnabled() {
        return true;
    }
}
