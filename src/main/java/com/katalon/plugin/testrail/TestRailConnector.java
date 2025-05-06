package com.katalon.plugin.testrail;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import com.gurock.testrail.APIClient;
import com.gurock.testrail.APIException;

public class TestRailConnector {
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
		System.out.println("Receive: " + response.toString());
		return response;
	}

	public JSONObject getProject(String projectId)
			throws IOException, URISyntaxException, GeneralSecurityException, APIException {
		return (JSONObject) sendGet("get_project/" + projectId);
	}

	@SuppressWarnings("unchecked")
	public List<Long> getTestCaseIdInRun(String id)
			throws IOException, URISyntaxException, GeneralSecurityException, APIException {
		String paginationNextURL = null;
		String requestURL = "";
		JSONArray jsonArray = new JSONArray();
		do {
			if (paginationNextURL != null) {
				requestURL = shortenURL(paginationNextURL);
			} else {
				requestURL = "get_tests/" + id;
			}
			JSONObject response = (JSONObject) sendGet(requestURL);

			JSONObject paginationLinks = (JSONObject) response.get("_links");
			paginationNextURL = (String) paginationLinks.get("next");
			jsonArray.addAll((JSONArray) response.get("tests"));
		} while (paginationNextURL != null);

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

	/*
	 * Author: Mohit Kumar This function assists Katalon users in uploading a test
	 * report into a TestRail test run.
	 */

	public void addReportToTestRun(String testRunId) throws IOException {
		String path = TestRailEventListenerInitializer.getReportIdVar;
		
		// String lastSegment = path.substring(path.lastIndexOf("/") + 1);
		// File file = new File(path + "/" + lastSegment + ".html");

		File file = new File(TestRailHelper.zipFolderPath(path));
		String url = this.url + "index.php?/api/v2/add_attachment_to_run/" + testRunId;

		String auth = this.username + ":" + this.password;
		byte[] encodedAuth = Base64.getEncoder().encode(auth.getBytes());
		String authHeader = "Basic " + new String(encodedAuth);

		try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
			HttpPost uploadFile = new HttpPost(url);
			uploadFile.setHeader(HttpHeaders.AUTHORIZATION, authHeader);
			MultipartEntityBuilder builder = MultipartEntityBuilder.create();
			builder.addPart("attachment", new FileBody(file, ContentType.MULTIPART_FORM_DATA));
			uploadFile.setEntity(builder.build());
			try (CloseableHttpResponse response = httpClient.execute(uploadFile)) {
				System.out.println("Response Code: " + response.getStatusLine().getStatusCode());
			}

		} catch (Exception e) {
			e.printStackTrace();
		}

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

	private String shortenURL(String url) {
		final String prefix = "/api/v2/";
		if (url.startsWith(prefix)) {
			return url.substring(prefix.length());
		}
		return url;
	}
}
