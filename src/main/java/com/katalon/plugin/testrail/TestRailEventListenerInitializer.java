package com.katalon.plugin.testrail;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
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
    private static final String TESTRAIL_TESTCASE_DELIMITER = ",";
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
                e.printStackTrace(System.out);
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
                status = "2"; // BLOCKED
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
                    TestRailHelper.doEncryptionMigrated(preferences);
                    TestRailConnector connector = new TestRailConnector(
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_URL, ""),
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_USERNAME, ""),
                            preferences.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "", true));
                    String projectId = preferences.getString(TestRailConstants.PREF_TESTRAIL_PROJECT, "");

                    ProjectEntity project = ApplicationManager.getInstance().getProjectManager().getCurrentProject();
                    TestCaseController controller = ApplicationManager.getInstance().getControllerManager().getController(TestCaseController.class);

                    List<Long> updateIds = new ArrayList<>();
                    Map<Long, String> caseIdToPathMap = new HashMap<>();

                    // Load custom field mappings
                    PluginPreference pluginPreference = ApplicationManager.getInstance()
                        .getPreferenceManager()
                        .getPluginPreference(project.getId(), TestRailConstants.PLUGIN_ID);
                    
                    if (pluginPreference == null) {
                        System.out.println("TestRail: Failed to get plugin preference. Cannot continue.");
                        return;
                    }

                    JSONParser parser = new JSONParser();
                    Map<String, Map<String, Object>> propertyMap = new HashMap<>();
                    try {
                        String propertyMapString = pluginPreference.getString(TestRailConstants.PREF_TESTRAIL_CUSTOM_FIELD_MAPPINGS, "{}");
                        propertyMap = (Map<String, Map<String, Object>>)parser.parse(propertyMapString);
                    } catch (Exception e) {
                        System.out.println("TestRail: Failed to parse custom fields mapping: " + e.getMessage());
                    }                    
                    final Map<String, Map<String, Object>> finalPropertyMap = propertyMap;

                    List<Map<String, Object>> data = testSuiteContext.getTestCaseContexts().stream().flatMap(testCaseExecutionContext -> {
                        String status = mapToTestRailStatus(testCaseExecutionContext.getTestCaseStatus());
                        
                        List<Map<String, Object>> resultMaps = new ArrayList<>();

                        try {
                            TestCaseEntity testCaseEntity = controller.getTestCase(project, testCaseExecutionContext.getId());
                            Integration integration = testCaseEntity.getIntegration(TestRailConstants.INTEGRATION_ID);
                            if (integration == null) {
                                return resultMaps.stream();
                            }
                            
                            String testRailTestCaseId = integration.getProperties().get(TestRailConstants.INTEGRATION_TESTCASE_ID);

                            if (StringUtils.isBlank(testRailTestCaseId)) {
                                return resultMaps.stream();
                            }
                            
                            String[] testRailTestCaseIds = testRailTestCaseId.split(TESTRAIL_TESTCASE_DELIMITER);

                            for (String id : testRailTestCaseIds) {
                                Long filteredTestCaseId = Long.parseLong(id.trim().replaceAll("\\D", ""));

                                updateIds.add(filteredTestCaseId);
                                caseIdToPathMap.put(filteredTestCaseId, testCaseExecutionContext.getId());

                                Map<String, Object> resultMap = new HashMap<>();
                                resultMap.put("case_id", filteredTestCaseId);
                                resultMap.put("status_id", status);

                                // Add custom fields to resultMap
                                for (Map.Entry<String, Map<String, Object>> mapping : finalPropertyMap.entrySet()) {
                                    String value = mapping.getValue().get("value").toString();
                                    String type = mapping.getValue().get("type").toString();
                                    Object finalValue = resolveFinalValue(value, type, testSuiteContext, testSuiteSummary);
                                    resultMap.put("custom_result_" + mapping.getKey(), finalValue);
                                }

                                resultMaps.add(resultMap);
                            }
                        } catch (Exception e) {
                            e.printStackTrace(System.out);
                        }
                        return resultMaps.stream();
                    }).collect(Collectors.toList());

                    if (data.isEmpty()) {
                        System.out.println("TestRail: No test cases found to update in TestRail.");
                        return;
                    }

                    // Validate case IDs before sending to TestRail
                    String testSuiteName = testSuiteContext.getSourceId();
                    String[] splitText = testSuiteName.split("/");
                    String name = splitText[splitText.length - 1];

                    Matcher createMatcher = createPattern.matcher(name);
                    Matcher updateMatcher = updatePattern.matcher(name);

                    String suiteId = null;
                    if (createMatcher.lookingAt()) {
                        suiteId = createMatcher.group(1);
                    } else if (updateMatcher.lookingAt()) {
                        // For update pattern, get suite ID from the test run
                        System.out.println("TestRail: Skipping case ID validation for existing test run.");
                    }

                    if (suiteId != null) {
                        try {
                            List<Long> validCaseIds = connector.getCasesInSuite(projectId, suiteId);
                            int totalCasesBeforeValidation = updateIds.size();

                            List<Long> invalidIds = updateIds.stream()
                                .filter(id -> !validCaseIds.contains(id))
                                .collect(Collectors.toList());

                            updateIds.removeAll(invalidIds);
                            
                            // Remove invalid IDs from data (results)
                            data.removeIf(resultMap -> {
                                Long caseId = (Long) resultMap.get("case_id");
                                return invalidIds.contains(caseId);
                            });

                            // Log validation results
                            int validCases = updateIds.size();
                            logValidationResults(totalCasesBeforeValidation, validCases, invalidIds, caseIdToPathMap);

                            if (updateIds.isEmpty()) {
                                return;
                            }
                        } catch (Exception e) {
                            ILog log = Platform.getLog(Platform.getBundle(TestRailConstants.PLUGIN_ID));
                            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID,
                                "TestRail: Failed to validate case IDs: " + e.getMessage() + ". Continuing without validation (this may cause errors if case IDs are invalid)", e));
                        }
                    }

                    //Check if test case is in test run
                    //If not, add it to test run
                    String testRunId = getTestRun(testSuiteContext.getSourceId(), projectId, connector, updateIds);
                    if (testRunId.equals("")) {
                        System.out.println("TestRail: Failed to get testRunId from testSuite name: " + testSuiteContext.getSourceId() + ". Please ensure testSuite name follow the correct convention (S<id> or R<id>)");
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

    private static Object resolveFinalValue(String templateText, String type, TestSuiteExecutionContext testSuiteContext, TestSuiteStatusSummary testSuiteSummary) {
        if (templateText == null || templateText.isEmpty()) {
            return templateText;
        }

        // Pattern to match ${variableName} in text
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(templateText);
        StringBuffer replacedText = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            String replacement = replaceVariable(variableName, testSuiteContext, testSuiteSummary);
            // Escape any special characters in the replacement string
            matcher.appendReplacement(replacedText, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(replacedText);
        String finalText = replacedText.toString();

        // Cast to the "type" defined via TestRail's admin page.
        // Warning: user may input wrong type to KS, or the type defined in TestRail is
        // not aligned with KS's field type.
        if (type.equalsIgnoreCase("integer")) {
            try {
                return Integer.parseInt(finalText);
            } catch (NumberFormatException e) {
                System.out.println(
                        "TestRail: Failed to parse templateText '" + templateText + "' to integer: " + finalText);
                return 0;
            }
        }

        if (type.equalsIgnoreCase("boolean")) {
            return Boolean.parseBoolean(finalText);
        }

        return finalText;
    }

    private static String replaceVariable(String variableName, TestSuiteExecutionContext testSuiteContext,
            TestSuiteStatusSummary testSuiteSummary) {
        switch (variableName) {
            // From TestSuiteExecutionContext
            case "hostName":
                return testSuiteContext.getHostName();
            case "os":
                return testSuiteContext.getOs();
            case "browser":
                return testSuiteContext.getBrowser();
            case "deviceId":
                return testSuiteContext.getDeviceId();
            case "deviceName":
                return testSuiteContext.getDeviceName();
            case "suiteName":
                return testSuiteContext.getSuiteName();
            case "executionProfile":
                return testSuiteContext.getExecutionProfile();

            // From TestSuiteStatusSummary
            case "totalTestCases":
                return String.valueOf(testSuiteSummary.getTotalTestCases());
            case "totalPasses":
            case "totalPassed":
                return String.valueOf(testSuiteSummary.getTotalPasses());
            case "totalFailures":
            case "totalFailed":
                return String.valueOf(testSuiteSummary.getTotalFailures());
            case "totalErrors":
            case "totalError":
                return String.valueOf(testSuiteSummary.getTotalErrors());
            case "totalIncomplete":
                return String.valueOf(testSuiteSummary.getTotalIncomplete());
            case "totalSkipped":
                return String.valueOf(testSuiteSummary.getTotalSkipped());

            default:
                return "${" + variableName + "}"; // Return the variable name itself if not found
        }
    }

    private void logValidationResults(int totalCases, int validCases, List<Long> invalidIds,
            Map<Long, String> caseIdToPathMap) {

        ILog log = Platform.getLog(Platform.getBundle(TestRailConstants.PLUGIN_ID));

        if (invalidIds.isEmpty()) {
            // All valid - log success
            String message = "\u2713 TestRail Integration: " + validCases + " of " + totalCases + " results uploaded successfully";
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, message));
        } else if (validCases == 0) {
            // Edge case: All invalid
            StringBuilder message = new StringBuilder();
            message.append("\u26a0 TestRail Integration: No results uploaded\n");
            message.append("All test cases have invalid TestRail case ID mappings (").append(totalCases).append(" cases):\n");

            for (Long invalidId : invalidIds) {
                String testCasePath = caseIdToPathMap.getOrDefault(invalidId, "Unknown");
                message.append("  \u2022 ").append(testCasePath).append(" \u2192 C").append(invalidId).append("\n");
            }

            message.append("\u2192 Action needed: Verify case IDs match your TestRail test suite");
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, message.toString()));
        } else {
            // Partial success
            String successMessage = "\u2713 TestRail Integration: " + validCases + " of " + totalCases + " results uploaded successfully";
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, successMessage));

            StringBuilder warning = new StringBuilder();
            warning.append("\u26a0 Skipped test cases with invalid TestRail mappings:\n");

            for (Long invalidId : invalidIds) {
                String testCasePath = caseIdToPathMap.getOrDefault(invalidId, "Unknown");
                warning.append("  \u2022 ").append(testCasePath).append(" \u2192 TestRail Case ID: C").append(invalidId).append("\n");
            }

            warning.append("\u2192 Action needed: Verify these case IDs exist in the configured TestRail test suite");
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, warning.toString()));
        }
    }
}
