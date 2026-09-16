package com.katalon.plugin.testrail;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

import com.katalon.platform.api.extension.ToolItemDescription;
import com.katalon.platform.api.service.ApplicationManager;
import com.katalon.platform.api.ui.DialogActionService;

public class TestRailToolItemDescription implements ToolItemDescription {

    private static final Bundle BUNDLE = FrameworkUtil.getBundle(TestRailToolItemDescription.class);

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
        String iconPath = IconResolver.resolve(BUNDLE, "icons/icon.png", "icons-v2/testrail.svg");
        return "platform:/plugin/" + TestRailConstants.PLUGIN_ID + "/" + iconPath;
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
