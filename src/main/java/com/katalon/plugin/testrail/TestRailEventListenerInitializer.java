package com.katalon.plugin.testrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.json.simple.JSONObject;
import org.osgi.service.event.Event;

import com.katalon.platform.api.controller.TestCaseController;
import com.katalon.platform.api.event.EventListener;
import com.katalon.platform.api.event.ExecutionEvent;
import com.katalon.platform.api.execution.TestSuiteExecutionContext;
import com.katalon.platform.api.extension.EventListenerInitializer;
import com.katalon.platform.api.model.Integration;
import com.katalon.platform.api.model.ProjectEntity;
import com.katalon.platform.api.model.TestCaseEntity;
import com.katalon.platform.api.preference.PluginPreference;
import com.katalon.platform.api.service.ApplicationManager;

public class TestRailEventListenerInitializer implements EventListenerInitializer, TestRailComponent {
    private Pattern updatePattern = Pattern.compile("^R(\\d+)");
    private Pattern createPattern = Pattern.compile("^S(\\d+)");

    /*
     * Return id of Test Run in TestRail
     *
     * @param id: Test Suite id in Katalon Studio
     * @param connector
     * @return
     */
    private String getTestRun(String id, String projectId, TestRailConnector connector, List<Long> testCaseIds) {
        String[] splitText = id.split("/");
        String name = splitText[splitText.length - 1];

        Matcher updateMatcher = updatePattern.matcher(name);
        Matcher createMatcher = createPattern.matcher(name);

        if (updateMatcher.lookingAt()) {
            return updateMatcher.group(1);
        } else if (createMatcher.lookingAt()) {
            String suiteId = createMatcher.group(1);
            try {
                System.out.println("Create new test run " + name);
                JSONObject jsonObject = connector.addRun(projectId, suiteId, name, testCaseIds);
                return ((Long) jsonObject.get("id")).toString();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return "";
    }

    private String mapToTestRailStatus(String ksStatus) {
        String status;
        switch (ksStatus) {
            case "PASSED":
                status = "1"; //PASSED
                break;
            case "FAILED":
                status = "5"; //FAILED
                break;
            case "ERROR":
                status = "5"; //FAILED
                break;
            default:
                status = "0";
        }
        return status;
    }

    @Override
    public void registerListener(EventListener listener) {
        listener.on(Event.class, event -> {
            try {
                PluginPreference preferences = getPluginStore();
                boolean isIntegrationEnabled = preferences.getBoolean(TestRailConstants.PREF_TESTRAIL_ENABLED, false);
                if (!isIntegrationEnabled) {
                    return;
                }
                String authToken = preferences.getString(TestRailConstants.PREF_TESTRAIL_USERNAME, "");

                if (ExecutionEvent.TEST_SUITE_FINISHED_EVENT.equals(event.getTopic())) {
                    ExecutionEvent eventObject = (ExecutionEvent) event.getProperty("org.eclipse.e4.data");

                    TestSuiteExecutionContext testSuiteContext = (TestSuiteExecutionContext) eventObject
                            .getExecutionContext();
                    TestSuiteStatusSummary testSuiteSummary = TestSuiteStatusSummary.of(testSuiteContext);
                    System.out.println("TestRail: Start sending summary message to channel:");
                    System.out.println(
                            "Summary execution result of test suite: " + testSuiteContext.getSourceId()
                                    + "\nTotal test cases: " + Integer.toString(testSuiteSummary.getTotalTestCases())
                                    + "\nTotal passes: " + Integer.toString(testSuiteSummary.getTotalPasses())
                                    + "\nTotal failures: " + Integer.toString(testSuiteSummary.getTotalFailures())
                                    + "\nTotal errors: " + Integer.toString(testSuiteSummary.getTotalErrors())
                                    + "\nTotal skipped: " + Integer.toString(testSuiteSummary.getTotalSkipped()));
                    System.out.println("TestRail: Summary message has been successfully sent");
                    TestRailHelper.doEncrytionMigrated(preferences);
                    TestRailConnector connector = new TestRailConnector(
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_URL, ""),
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_USERNAME, ""),
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "", true));
                    String projectId = preferences.getString(TestRailConstants.PREF_TESTRAIL_PROJECT, "");

                    ProjectEntity project = ApplicationManager.getInstance().getProjectManager().getCurrentProject();
                    TestCaseController controller = ApplicationManager.getInstance().getControllerManager().getController(TestCaseController.class);

                    List<Long> updateIds = new ArrayList<>();
                    List<Map<String, String>> data = testSuiteContext.getTestCaseContexts().stream().map(testCaseExecutionContext -> {
                        String status = mapToTestRailStatus(testCaseExecutionContext.getTestCaseStatus());
                        try {
                            TestCaseEntity testCaseEntity = controller.getTestCase(project, testCaseExecutionContext.getId());
                            Integration integration = testCaseEntity.getIntegration(TestRailConstants.INTEGRATION_ID);
                            if (integration == null) {
                                return null;
                            }
                            String testRailTCId = integration.getProperties().get(TestRailConstants.INTEGRATION_TESTCASE_ID);
                            String filteredTestCaseId = testRailTCId != null ? testRailTCId.replaceAll("\\D", "") : null;
                            updateIds.add(Long.parseLong(filteredTestCaseId));
                            Map<String, String> resultMap = new HashMap<>();
                            resultMap.put("case_id", filteredTestCaseId);
                            resultMap.put("status_id", status);
                            return resultMap;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        return null;
                    }).filter(map -> map != null).collect(Collectors.toList());
                    //Check if test case is in test run
                    //If not, add it to test run
                    String testRunId = getTestRun(testSuiteContext.getSourceId(), projectId, connector, updateIds);
                    if (testRunId.equals("")) {
                        return;
                    }
                    List<Long> testCaseIdInRun = connector.getTestCaseIdInRun(testRunId);
                    if (!testCaseIdInRun.containsAll(updateIds)) {
                        testCaseIdInRun.addAll(updateIds);
                        Map<String, Object> body = new HashMap<>();
                        body.put("include_all", false);
                        body.put("case_ids", testCaseIdInRun);
                        connector.updateRun(testRunId, body);
                    }
                    Map<String, Object> requestBody = new HashMap<>();
                    requestBody.put("results", data);
                    connector.addMultipleResultForCases(testRunId, requestBody);
                }
            } catch (Exception e) {
                e.printStackTrace(System.out);
            }
        });
    }
}
