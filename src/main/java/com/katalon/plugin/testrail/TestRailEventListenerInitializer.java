package com.katalon.plugin.testrail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.osgi.service.event.Event;

import com.gurock.testrail.APIException;
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
    private static final int PER_CASE_VALIDATION_LIMIT = 50;
    private static final int BULK_VALIDATION_LIMIT = 500;
    private Pattern updatePattern = Pattern.compile("^R(\\d+)");
    private Pattern createPattern = Pattern.compile("^S(\\d+)");

    /*
     * Return id of Test Run in TestRail
     *
     * @param id: Test Suite id in Katalon Studio
     * @param connector
     * @return
     * @throws APIException when TestRail rejects the new run, so the caller can drop invalid case IDs and retry
     */
    private String getTestRun(String id, String projectId, TestRailConnector connector, List<Long> testCaseIds)
            throws Exception {
        String[] splitText = id.split("/");
        String name = splitText[splitText.length - 1];

        Matcher updateMatcher = updatePattern.matcher(name);
        Matcher createMatcher = createPattern.matcher(name);

        if (updateMatcher.lookingAt()) {
            return updateMatcher.group(1);
        } else if (createMatcher.lookingAt()) {
            String suiteId = createMatcher.group(1);
            System.out.println("Create new test run " + name);
            JSONObject jsonObject = connector.addRun(projectId, suiteId, name, testCaseIds);
            return ((Long) jsonObject.get("id")).toString();
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

                    // Pre-collect format-invalid case IDs in a separate pass
                    Map<String, String> invalidFormatIds = new HashMap<>();
                    testSuiteContext.getTestCaseContexts().forEach(ctx -> {
                        try {
                            TestCaseEntity entity = controller.getTestCase(project, ctx.getId());
                            Integration intg = entity.getIntegration(TestRailConstants.INTEGRATION_ID);
                            if (intg == null) return;
                            String ids = intg.getProperties().get(TestRailConstants.INTEGRATION_TESTCASE_ID);
                            if (StringUtils.isBlank(ids)) return;
                            for (String id : ids.split(TESTRAIL_TESTCASE_DELIMITER)) {
                                String trimmed = id.trim();
                                if (!isValidTestRailCaseId(trimmed)) {
                                    invalidFormatIds.put(trimmed, ctx.getId());
                                }
                            }
                        } catch (Exception e) {
                            // skip
                        }
                    });

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
                    final Map<String, String> customFieldKeys = resolveCustomFieldKeys(connector,
                            finalPropertyMap.keySet());

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
                                String trimmedId = id.trim();
                                if (!isValidTestRailCaseId(trimmedId)) {
                                    continue;
                                }
                                Long filteredTestCaseId = Long.parseLong(trimmedId.replaceAll("\\D", ""));

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
                                    resultMap.put(customFieldKeys.get(mapping.getKey()), finalValue);
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
                        if (!invalidFormatIds.isEmpty()) {
                            logValidationResults(0, 0, new ArrayList<>(), caseIdToPathMap, invalidFormatIds);
                        }
                        return;
                    }

                    // Validate case IDs before sending to TestRail
                    String testSuiteName = testSuiteContext.getSourceId();
                    String[] splitText = testSuiteName.split("/");
                    String name = splitText[splitText.length - 1];

                    Matcher createMatcher = createPattern.matcher(name);
                    Matcher updateMatcher = updatePattern.matcher(name);

                    String suiteId = null;
                    Boolean runIncludesAllCases = null;
                    if (createMatcher.lookingAt()) {
                        suiteId = createMatcher.group(1);
                    } else if (updateMatcher.lookingAt()) {
                        String runId = updateMatcher.group(1);
                        try {
                            JSONObject run = connector.getRun(runId);
                            Object sid = run.get("suite_id");
                            Object pid = run.get("project_id");
                            Object includeAll = run.get("include_all");
                            if (includeAll instanceof Boolean) {
                                runIncludesAllCases = (Boolean) includeAll;
                            }
                            if (sid != null) {
                                suiteId = sid.toString();
                            }
                            if (pid != null && !pid.toString().equals(projectId)) {
                                logError("TestRail Integration: Aborting upload. Configured TestRail Project ID ("
                                        + projectId + ") does not match run R" + runId + "'s project ("
                                        + pid + "). Update the Project ID in Project Settings → TestRail "
                                        + "to match the run's project, or use a run that belongs to project "
                                        + projectId + ".", null);
                                return;
                            }
                        } catch (Exception e) {
                            logError("TestRail: Failed to fetch run " + runId + " for case ID validation: "
                                    + e.getMessage() + ". Continuing without validation.", e);
                        }
                    }

                    int totalCasesBeforeValidation = updateIds.size();
                    List<Long> invalidIds = new ArrayList<>();
                    // Case IDs not confirmed valid, re-checked only if TestRail rejects the upload
                    Set<Long> unverifiedIds = new LinkedHashSet<>(updateIds);
                    if (suiteId != null) {
                        try {
                            CaseValidation validation = validateCaseIds(connector, projectId, suiteId, updateIds);
                            invalidIds.addAll(validation.invalidIds);
                            unverifiedIds.retainAll(validation.unverifiedIds);
                        } catch (Exception e) {
                            logError("TestRail: Failed to validate case IDs: " + e.getMessage()
                                    + ". Uploading anyway, and invalid case IDs will be removed if TestRail rejects the upload.", e);
                        }
                        removeCaseIds(invalidIds, updateIds, data);
                    }

                    if (updateIds.isEmpty()) {
                        logValidationResults(totalCasesBeforeValidation, 0, invalidIds, caseIdToPathMap, invalidFormatIds);
                        return;
                    }

                    String testRunId = "";
                    boolean retriedAfterRejection = false;
                    while (true) {
                        try {
                            //Check if test case is in test run
                            //If not, add it to test run
                            if (testRunId.isEmpty()) {
                                testRunId = getTestRun(testSuiteContext.getSourceId(), projectId, connector, updateIds);
                                if (testRunId.isEmpty()) {
                                    logError("TestRail: Failed to get testRunId from testSuite name: " + testSuiteContext.getSourceId() + ". Please ensure testSuite name follow the correct convention (S<id> or R<id>)", null);
                                    return;
                                }
                            }

                            if (!Boolean.TRUE.equals(runIncludesAllCases)) {
                                List<Long> testCaseIdInRun = connector.getTestCaseIdInRun(testRunId);
                                if (!testCaseIdInRun.containsAll(updateIds)) {
                                    testCaseIdInRun.addAll(updateIds);
                                    Map<String, Object> body = new HashMap<>();
                                    body.put("include_all", false);
                                    body.put("case_ids", testCaseIdInRun);
                                    connector.updateRun(testRunId, body);
                                }
                            }
                            Map<String, Object> requestBody = new HashMap<>();
                            requestBody.put("results", data);

                            connector.addMultipleResultForCases(testRunId, requestBody);
                            break;
                        } catch (APIException e) {
                            // TestRail rejects the whole request when one case ID is invalid, without saying which one
                            if (retriedAfterRejection || e.getStatusCode() != 400 || unverifiedIds.isEmpty()) {
                                throw e;
                            }
                            retriedAfterRejection = true;
                            System.out.println("TestRail: Upload rejected, re-checking " + unverifiedIds.size()
                                    + " unvalidated case ID(s). " + e.getMessage());
                            CaseValidation recheck = validateCasesIndividually(connector, unverifiedIds, suiteId, true);
                            if (recheck.invalidIds.isEmpty()) {
                                throw e;
                            }
                            invalidIds.addAll(recheck.invalidIds);
                            unverifiedIds.clear();
                            removeCaseIds(recheck.invalidIds, updateIds, data);
                            if (updateIds.isEmpty()) {
                                logValidationResults(totalCasesBeforeValidation, 0, invalidIds, caseIdToPathMap, invalidFormatIds);
                                return;
                            }
                        }
                    }
                    logValidationResults(totalCasesBeforeValidation, updateIds.size(), invalidIds, caseIdToPathMap, invalidFormatIds);
                }
            } catch (Exception e) {
                e.printStackTrace(System.out);
                if (ExecutionEvent.TEST_SUITE_FINISHED_EVENT.equals(event.getTopic())) {
                    logError("TestRail Integration: Failed to upload results to TestRail. " + e.getMessage(), e);
                }
            }
        });
    }

    private static final class CaseValidation {
        private final List<Long> invalidIds = new ArrayList<>();

        private final List<Long> unverifiedIds = new ArrayList<>();
    }

    /*
     * Checks small batches with one get_case call per ID, so a large suite is never downloaded just to
     * validate a few IDs. Falls back to the suite's case list only when that is cheaper.
     */
    private CaseValidation validateCaseIds(TestRailConnector connector, String projectId, String suiteId,
            List<Long> caseIds) throws Exception {
        Set<Long> uniqueIds = new LinkedHashSet<>(caseIds);
        boolean validatePerCase = uniqueIds.size() <= PER_CASE_VALIDATION_LIMIT;
        Set<Long> suiteCaseIds = null;
        if (!validatePerCase) {
            Object firstPage = connector.getCasesInSuiteFirstPage(projectId, suiteId);
            JSONArray firstPageCases = firstPage instanceof JSONArray ? (JSONArray) firstPage
                    : (JSONArray) ((JSONObject) firstPage).get("cases");
            JSONObject paginationLinks = firstPage instanceof JSONObject
                    ? (JSONObject) ((JSONObject) firstPage).get("_links") : null;
            boolean suiteFitsInOnePage = paginationLinks == null || paginationLinks.get("next") == null;
            if (suiteFitsInOnePage) {
                suiteCaseIds = new HashSet<>();
                for (Object testRailCase : firstPageCases) {
                    suiteCaseIds.add((Long) ((JSONObject) testRailCase).get("id"));
                }
            } else if (uniqueIds.size() <= BULK_VALIDATION_LIMIT) {
                validatePerCase = true;
            } else {
                suiteCaseIds = new HashSet<>(connector.getCasesInSuite(projectId, suiteId));
            }
        }

        if (validatePerCase) {
            return validateCasesIndividually(connector, uniqueIds, suiteId, false);
        }
        CaseValidation validation = new CaseValidation();
        for (Long caseId : uniqueIds) {
            if (!suiteCaseIds.contains(caseId)) {
                validation.invalidIds.add(caseId);
            }
        }
        return validation;
    }

    /*
     * @param dropUnreadable true once TestRail has rejected the upload. An ID that still cannot be read
     * (e.g. HTTP 403 for a case in another project) is then treated as invalid instead of kept.
     */
    private CaseValidation validateCasesIndividually(TestRailConnector connector, Collection<Long> caseIds,
            String suiteId, boolean dropUnreadable) {
        CaseValidation validation = new CaseValidation();
        String firstError = null;
        for (Long caseId : caseIds) {
            try {
                JSONObject testRailCase = connector.getCase(caseId);
                Object caseSuiteId = testRailCase.get("suite_id");
                boolean inSuite = suiteId == null || (caseSuiteId != null && caseSuiteId.toString().equals(suiteId));
                if (!inSuite || isDeleted(testRailCase.get("is_deleted"))) {
                    validation.invalidIds.add(caseId);
                }
            } catch (Exception e) {
                int statusCode = e instanceof APIException ? ((APIException) e).getStatusCode() : 0;
                // TestRail answers 400 for a case ID that does not exist
                boolean notFound = statusCode == 400 || statusCode == 404;
                if (notFound || (dropUnreadable && statusCode > 0)) {
                    validation.invalidIds.add(caseId);
                } else {
                    validation.unverifiedIds.add(caseId);
                    if (firstError == null) {
                        firstError = e.getMessage();
                    }
                }
            }
        }
        if (!validation.unverifiedIds.isEmpty()) {
            logError("TestRail: Could not verify " + validation.unverifiedIds.size() + " case ID(s) "
                    + validation.unverifiedIds + ". " + firstError
                    + " They will be uploaded and removed only if TestRail rejects them.", null);
        }
        return validation;
    }

    private static boolean isDeleted(Object isDeleted) {
        if (isDeleted == null) {
            return false;
        }
        String value = isDeleted.toString();
        return !value.equals("0") && !value.equalsIgnoreCase("false");
    }

    private static void removeCaseIds(List<Long> idsToRemove, List<Long> updateIds, List<Map<String, Object>> data) {
        Set<Long> removed = new HashSet<>(idsToRemove);
        updateIds.removeIf(removed::contains);
        data.removeIf(resultMap -> removed.contains((Long) resultMap.get("case_id")));
    }

    private static void logError(String message, Throwable e) {
        ILog log = Platform.getLog(Platform.getBundle(TestRailConstants.PLUGIN_ID));
        log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, message, e));
    }

    /*
     * Result fields created by older TestRail versions have "custom_<name>" as system name instead of
     * "custom_result_<name>", so each mapping key is resolved against the system names TestRail reports.
     */
    private static Map<String, String> resolveCustomFieldKeys(TestRailConnector connector, Set<String> mappingKeys) {
        Map<String, String> customFieldKeys = new HashMap<>();
        if (mappingKeys.isEmpty()) {
            return customFieldKeys;
        }

        Map<String, String> systemNameByName = new HashMap<>();
        Set<String> systemNames = new HashSet<>();
        try {
            for (Object field : connector.getResultFields()) {
                JSONObject fieldObject = (JSONObject) field;
                String systemName = (String) fieldObject.get("system_name");
                if (StringUtils.isBlank(systemName)) {
                    continue;
                }
                systemNames.add(systemName);
                String name = (String) fieldObject.get("name");
                if (StringUtils.isNotBlank(name)) {
                    systemNameByName.put(name, systemName);
                }
            }
        } catch (Exception e) {
            System.out.println("TestRail: Failed to get result fields, falling back to the custom_result_ prefix: "
                    + e.getMessage());
        }

        for (String mappingKey : mappingKeys) {
            customFieldKeys.put(mappingKey, resolveCustomFieldKey(mappingKey.trim(), systemNameByName, systemNames));
        }
        return customFieldKeys;
    }

    private static String resolveCustomFieldKey(String key, Map<String, String> systemNameByName,
            Set<String> systemNames) {
        if (systemNames.contains(key)) {
            return key;
        }
        if (systemNameByName.containsKey(key)) {
            return systemNameByName.get(key);
        }
        if (systemNames.contains("custom_result_" + key)) {
            return "custom_result_" + key;
        }
        if (systemNames.contains("custom_" + key)) {
            return "custom_" + key;
        }
        return key.startsWith("custom_") ? key : "custom_result_" + key;
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

    private boolean isValidTestRailCaseId(String id) {
        return id != null && id.matches("[Cc]?\\d+");
    }

    private void logValidationResults(int totalCases, int validCases, List<Long> invalidIds,
            Map<Long, String> caseIdToPathMap, Map<String, String> invalidFormatIds) {

        ILog log = Platform.getLog(Platform.getBundle(TestRailConstants.PLUGIN_ID));
        int totalWithFormatInvalid = totalCases + invalidFormatIds.size();
        boolean hasInvalidIds = !invalidIds.isEmpty() || !invalidFormatIds.isEmpty();

        if (!hasInvalidIds) {
            // All valid - log success
            String message = "\u2713 TestRail Integration: " + validCases + " of " + totalWithFormatInvalid + " results uploaded successfully";
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, message));
        } else if (validCases == 0) {
            // Edge case: All invalid
            StringBuilder message = new StringBuilder();
            message.append("\u26a0 TestRail Integration: No results uploaded\n");
            message.append("All test cases have invalid TestRail case ID mappings (").append(totalWithFormatInvalid).append(" cases):\n");

            for (Long invalidId : invalidIds) {
                String testCasePath = caseIdToPathMap.getOrDefault(invalidId, "Unknown");
                message.append("  \u2022 ").append(testCasePath).append(" \u2192 C").append(invalidId).append("\n");
            }
            for (Map.Entry<String, String> entry : invalidFormatIds.entrySet()) {
                message.append("  \u2022 ").append(entry.getValue()).append(" \u2192 invalid case ID: ").append(entry.getKey()).append("\n");
            }

            message.append("\u2192 Action needed: Verify case IDs match your TestRail test suite");
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, message.toString()));
        } else {
            // Partial success
            String successMessage = "\u2713 TestRail Integration: " + validCases + " of " + totalWithFormatInvalid + " results uploaded successfully";
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, successMessage));

            StringBuilder warning = new StringBuilder();
            warning.append("\u26a0 Skipped test cases with invalid TestRail mappings:\n");

            for (Long invalidId : invalidIds) {
                String testCasePath = caseIdToPathMap.getOrDefault(invalidId, "Unknown");
                warning.append("  \u2022 ").append(testCasePath).append(" \u2192 TestRail Case ID: C").append(invalidId).append("\n");
            }
            for (Map.Entry<String, String> entry : invalidFormatIds.entrySet()) {
                warning.append("  \u2022 ").append(entry.getValue()).append(" \u2192 invalid case ID: ").append(entry.getKey()).append("\n");
            }

            warning.append("\u2192 Action needed: Verify these case IDs exist in the configured TestRail test suite");
            log.log(new Status(Status.ERROR, TestRailConstants.PLUGIN_ID, warning.toString()));
        }
    }
}
