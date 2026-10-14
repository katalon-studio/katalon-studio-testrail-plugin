package com.katalon.plugin.testrail;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.gurock.testrail.APIClient;
import com.gurock.testrail.APIException;

public class TestRailConnector {
    // get_cases and get_tests pages can be megabytes, and printing them whole slows the upload down
    private static final int MAX_LOGGED_RESPONSE_LENGTH = 2000;

    private String url;
    private String username;
    private String password;

    private APIClient apiClient;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public TestRailConnector(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;

        this.apiClient = new APIClient(this.url);
        apiClient.setUser(this.username);
        apiClient.setPassword(this.password);
    }

    private Object sendPost(String url, Map<String, Object> data)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        System.out.println("Send post url: " + url + " data: " + data);
        Object response = this.apiClient.sendPost(url, data);
        System.out.println("Receive: " + response.toString());
        return response;
    }

    private Object sendGet(String url) throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        System.out.println("Send get url " + url);
        Object response = this.apiClient.sendGet(url);
        System.out.println("Receive: " + StringUtils.abbreviate(response.toString(), MAX_LOGGED_RESPONSE_LENGTH));
        return response;
    }

    public JSONObject getProject(String projectId)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return (JSONObject) sendGet("get_project/" + projectId);
    }

    public JSONObject getRun(String runId)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return (JSONObject) sendGet("get_run/" + runId);
    }

    public JSONArray getResultFields()
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return (JSONArray) sendGet("get_result_fields");
    }

    @SuppressWarnings("unchecked")
    public List<Long> getTestCaseIdInRun(String id)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String initialUrl = "get_tests/" + id;
        JSONArray jsonArray = getArrayData(initialUrl, "tests");

        List<Long> listId = new ArrayList<>();
        jsonArray.forEach((o) -> {
            JSONObject jsonObject = (JSONObject) o;
            listId.add((Long) jsonObject.get("case_id"));
        });

        return listId;
    }

    public JSONObject addResultForTestCase(String testRunId, String testCaseId, int status)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("status_id", status);
        List<Long> listId = getTestCaseIdInRun(testRunId);
        Long tcId = Long.parseLong(testCaseId);

        if (!listId.contains(tcId)) {
            // add test case to test run before sending result
            listId.add(tcId);
            String update_run_url = "update_run/" + testRunId;
            Map<String, Object> body = new HashMap<String, Object>();
            body.put("include_all", false);
            body.put("case_ids", listId);
            System.out.println(body);
            sendPost(update_run_url, body);
        }

        String add_result_url = String.format("add_result_for_case/%s/%s", testRunId, testCaseId);
        return (JSONObject) sendPost(add_result_url, data);
    }

    public JSONObject updateRun(String testRunId, Map<String, Object> body)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String update_run_url = "update_run/" + testRunId;
        return (JSONObject) sendPost(update_run_url, body);
    }

    public JSONArray addMultipleResultForCases(String testRunId, Map<String, Object> body)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String add_result_url = String.format("add_results_for_cases/%s", testRunId);
        return (JSONArray) sendPost(add_result_url, body);
    }

    public JSONObject addRun(String projectId, String suiteId, String name, List<Long> testCaseIds)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        Map<String, Object> data = new HashMap<String, Object>();
        data.put("suite_id", Long.parseLong(suiteId));
        data.put("name", name);
        data.put("include_all", false);
        data.put("case_ids", testCaseIds);
        String requestURL = String.format("add_run/%s", projectId);
        return (JSONObject) sendPost(requestURL, data);
    }

    public JSONObject getCase(Long caseId)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return (JSONObject) sendGet("get_case/" + caseId);
    }

    /**
     * Returns a paginated JSONObject on TestRail 6.7+, or a plain JSONArray of all cases on older servers.
     */
    public Object getCasesInSuiteFirstPage(String projectId, String suiteId)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return sendGet(String.format("get_cases/%s&suite_id=%s", projectId, suiteId));
    }

    @SuppressWarnings("unchecked")
    public List<Long> getCasesInSuite(String projectId, String suiteId)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String initialUrl = String.format("get_cases/%s&suite_id=%s", projectId, suiteId);
        JSONArray jsonArray = getArrayData(initialUrl, "cases");

        List<Long> caseIds = new ArrayList<>();
        jsonArray.forEach((o) -> {
            JSONObject jsonObject = (JSONObject) o;
            caseIds.add((Long) jsonObject.get("id"));
        });

        return caseIds;
    }

    @SuppressWarnings("unchecked")
    private JSONArray getArrayData(String initialUrl, String responseKey)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String paginationNextURL = null;
        String requestURL = "";
        JSONArray jsonArray = new JSONArray();

        do {
            if (paginationNextURL != null) {
                requestURL = shortenURL(paginationNextURL);
            } else {
                requestURL = initialUrl;
            }

            Object rawResponse = sendGet(requestURL);
            if (rawResponse instanceof JSONArray) {
                // TestRail before 6.7 returns the whole list without pagination
                jsonArray.addAll((JSONArray) rawResponse);
                break;
            }
            JSONObject response = (JSONObject) rawResponse;

            JSONObject paginationLinks = (JSONObject) response.get("_links");
            paginationNextURL = paginationLinks != null ? (String) paginationLinks.get("next") : null;
            jsonArray.addAll((JSONArray) response.get(responseKey));
        } while (paginationNextURL != null);

        return jsonArray;
    }

    private String shortenURL(String url) {
        final String prefix = "/api/v2/";
        if (url.startsWith(prefix)) {
            return url.substring(prefix.length());
        }
        return url;
    }
}
