package com.katalon.plugin.testrail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import com.katalon.platform.api.exception.CryptoException;
import com.katalon.platform.api.exception.ResourceException;
import com.katalon.platform.api.preference.PluginPreference;
import com.katalon.platform.api.execution.TestSuiteExecutionContext;

public class TestRailHelper {

    public static final String TYPE_STRING = "String";
    public static final String TYPE_NUMBER = "Number";
    public static final String TYPE_BOOLEAN = "Boolean";

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

    public static void doEncryptionMigrated(PluginPreference preferences) throws CryptoException, ResourceException {
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

    public static String convertCustomFieldMappingsToString(Map<String, Object> customFieldMappings) {
        if (customFieldMappings == null || customFieldMappings.isEmpty()) {
            return "{}";
        }
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(customFieldMappings);
    }

    public static Map<String, Object> convertStringToCustomFieldMappings(String str) {
        if (str == null || str.isEmpty() || str.equals("{}")) {
            return new HashMap<>();
        }
        try {
            Gson gson = new Gson();
            return gson.fromJson(str, new TypeToken<Map<String, Object>>(){}.getType());
        } catch (Exception e) {
            System.err.println("Error parsing custom field mappings JSON: " + e.getMessage());
            return new HashMap<>();
        }
    }

    // Helper method to convert table items to Map
    public static Map<String, Object> tableItemsToMap(TableItem[] items) {
        Map<String, Object> mappings = new HashMap<>();
        for (TableItem item : items) {
            String name = item.getText(0);
            String type = item.getText(1);
            String value = item.getText(2);
            
            if (!name.isEmpty()) {
                // Convert value based on type
                Object typedValue = convertValueByType(value, type);
                mappings.put(name, typedValue);
            }
        }
        return mappings;
    }

    // Helper method to convert Map to table items
    public static void mapToTableItems(Table table, Map<String, Object> mappings) {
        table.removeAll();
        for (Map.Entry<String, Object> entry : mappings.entrySet()) {
            TableItem item = new TableItem(table, SWT.NONE);
            String name = entry.getKey();
            Object mappingObj = entry.getValue();
            
            if (mappingObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mapping = (Map<String, Object>) mappingObj;
                String type = (String) mapping.get("type");
                Object value = mapping.get("value");
                String stringValue = value != null ? value.toString() : "";
                
                item.setText(new String[] { name, type, stringValue });
            } else {
                // Handle legacy format or direct value
                String type = getTypeFromValue(mappingObj);
                String stringValue = mappingObj != null ? mappingObj.toString() : "";
                item.setText(new String[] { name, type, stringValue });
            }
        }
    }

    public static Object convertValueByType(String value, String type) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        
        try {
            switch (type) {
                case TYPE_NUMBER:
                    return Double.parseDouble(value);
                case TYPE_BOOLEAN:
                    return Boolean.parseBoolean(value);
                case TYPE_STRING:
                default:
                    return value;
            }
        } catch (NumberFormatException e) {
            // If conversion fails, return the original string
            return value;
        }
    }

    private static String getTypeFromValue(Object value) {
        if (value == null) {
            return TYPE_STRING;
        }
        if (value instanceof Number) {
            return TYPE_NUMBER;
        }
        if (value instanceof Boolean) {
            return TYPE_BOOLEAN;
        }
        return TYPE_STRING;
    }

    public static boolean isValidType(String type) {
        return type != null && (
            type.equals(TYPE_STRING) ||
            type.equals(TYPE_NUMBER) ||
            type.equals(TYPE_BOOLEAN)
        );
    }

    public static Map<String, Object> createFieldMapping(String type, String value) {
        Map<String, Object> mapping = new java.util.LinkedHashMap<>();
        mapping.put("type", type);
        mapping.put("value", convertValueByType(value, type));
        return mapping;
    }

    public static String substituteVariables(String value, TestSuiteExecutionContext testSuiteContext, TestSuiteStatusSummary testSuiteSummary) {
        if (value == null) {
            return value;
        }

        // Pattern to match ${variableName} in text
        Pattern pattern = Pattern.compile("\\$\\{([^}]+)\\}");
        Matcher matcher = pattern.matcher(value);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            String replacement = getVariableValue(variableName, testSuiteContext, testSuiteSummary);
            // Escape any special characters in the replacement string
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private static String getVariableValue(String variableName, TestSuiteExecutionContext testSuiteContext, TestSuiteStatusSummary testSuiteSummary) {
        // Fixed mapping of variable names to their corresponding values
        switch (variableName) {
            // Environment variables
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
                
            // Test suite status variables
            case "totalTestCases":
                return String.valueOf(testSuiteSummary.getTotalTestCases());
            case "totalPasses":
                return String.valueOf(testSuiteSummary.getTotalPasses());
            case "totalFailures":
                return String.valueOf(testSuiteSummary.getTotalFailures());
            case "totalErrors":
                return String.valueOf(testSuiteSummary.getTotalErrors());
            case "totalSkipped":
                return String.valueOf(testSuiteSummary.getTotalSkipped());
                
            default:
                // Return the original variable placeholder if variable name is not in our fixed list
                return "${" + variableName + "}";
        }
    }
}
