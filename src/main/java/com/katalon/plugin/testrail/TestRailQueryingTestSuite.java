package com.katalon.plugin.testrail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.katalon.platform.api.controller.FolderController;
import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.InvalidDataTypeFormatException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.extension.DynamicQueryingTestSuiteDescription;
import com.katalon.platform.api.model.FolderEntity;
import com.katalon.platform.api.model.Integration;
import com.katalon.platform.api.model.ProjectEntity;
import com.katalon.platform.api.model.TestCaseEntity;
import com.katalon.platform.api.model.TestSuiteEntity;
import com.katalon.platform.api.preference.PluginPreference;
import com.katalon.platform.api.service.ApplicationManager;

public class TestRailQueryingTestSuite implements DynamicQueryingTestSuiteDescription, TestRailComponent {
    private FolderController folderController = ApplicationManager.getInstance()
            .getControllerManager()
            .getController(FolderController.class);

    @Override
    public String getQueryingType() {
        return "TestRail";
    }

    @Override
    public List<TestCaseEntity> query(ProjectEntity project, TestSuiteEntity testSuiteEntity, String s) throws ResourceException {
        FolderEntity testCaseRoot = folderController.getFolder(project, "Test Cases");
        List<TestCaseEntity> allTestCases = getAllTestCases(project, testCaseRoot);

        String testRunId = TestRailHelper.parseId(s, "^R(\\d+)");
        PluginPreference preferences = getPluginStore();
        TestRailConnector connector = null;
        try {
            TestRailHelper.doEncryptionMigrated(preferences);
        } catch (CryptoException |ResourceException e) {
            e.printStackTrace();
        }
        try {
            connector = new TestRailConnector(preferences.getString(TestRailConstants.PREF_TESTRAIL_URL, ""),
                    preferences.getString(TestRailConstants.PREF_TESTRAIL_USERNAME, ""),
                    preferences.getString(TestRailConstants.PREF_TESTRAIL_PASSWORD, "", true));
        } catch (InvalidDataTypeFormatException | CryptoException e) {
            e.printStackTrace(System.out);
        }
        List<TestCaseEntity> resultTestCases = new ArrayList<>();
        if (testRunId.equals("")) return resultTestCases;

        try {
            List<Long> testCaseIdInRun = connector.getTestCaseIdInRun(testRunId);
            allTestCases.forEach(testCaseEntity -> {
                Integration integration = testCaseEntity.getIntegration(TestRailConstants.INTEGRATION_ID);
                if (integration == null) {
                    return;
                }
                Map<String, String> props = integration.getProperties();
                if (props.containsKey(TestRailConstants.INTEGRATION_TESTCASE_ID)) {
                    String testCaseId = props.get(TestRailConstants.INTEGRATION_TESTCASE_ID);
                    String filteredTestCaseId = testCaseId != null ? testCaseId.replaceAll("\\D", "") : null;
                    if (testCaseIdInRun.contains(Long.parseLong(filteredTestCaseId))) {
                        System.out.println("Found testCaseId " + filteredTestCaseId);
                        resultTestCases.add(testCaseEntity);
                    }
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        return resultTestCases;
    }

    private List<TestCaseEntity> getAllTestCases(ProjectEntity project, FolderEntity parentFolder)
            throws ResourceException {
        List<TestCaseEntity> childTestCases = folderController.getChildTestCases(project, parentFolder);

        for (FolderEntity childFolder : folderController.getChildFolders(project, parentFolder)) {
            childTestCases.addAll(getAllTestCases(project, childFolder));
        }
        return childTestCases;
    }
}
