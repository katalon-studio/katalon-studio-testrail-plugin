package com.katalon.plugin.testrail;

import com.katalon.platform.api.model.Integration;

import java.util.HashMap;
import java.util.Map;

public class TestRailTestCaseIntegration implements Integration {
    private String testCaseId;

    public void setTestCaseId(String testCaseId) {
        this.testCaseId = testCaseId;
    }

    public String getTestCaseId() {
        return testCaseId != null ? testCaseId.replaceAll("\\D", "") : null;
    }

    @Override
    public String getName() {
        return TestRailConstants.INTEGRATION_ID;
    }

    @Override
    public Map<String, String> getProperties() {
        HashMap<String, String> props = new HashMap<>();
        props.put(TestRailConstants.INTEGRATION_TESTCASE_ID, getTestCaseId());
        return props;
    }
}
