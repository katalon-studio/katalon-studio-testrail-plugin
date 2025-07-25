package com.katalon.plugin.testrail;

import com.katalon.platform.api.model.Integration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

public class TestRailTestCaseIntegration implements Integration {
    private static final String TESTRAIL_TESTCASE_DELIMITER = ",";
    private String testCaseId;

    public void setTestCaseId(String testCaseId) {
        this.testCaseId = testCaseId;
    }

    public String getTestCaseId() {
        if (StringUtils.isBlank(testCaseId)) {
            return StringUtils.EMPTY;
        }

        ArrayList<Long> results = new ArrayList<>();
        String[] testRailTestCaseIds = testCaseId.split(TESTRAIL_TESTCASE_DELIMITER);
        
        for (String id : testRailTestCaseIds) {
            String filtered = id.trim().replaceAll("\\D", "");
            if (!filtered.isEmpty()) {
                results.add(Long.parseLong(filtered));
            }
        }

        return results.stream().map(String::valueOf).collect(Collectors.joining(TESTRAIL_TESTCASE_DELIMITER));
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
