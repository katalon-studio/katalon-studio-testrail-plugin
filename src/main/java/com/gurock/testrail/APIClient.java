/**
 * TestRail API binding for Java (API v2, available since TestRail 3.0)
 *
 * Learn more:
 *
 * http://docs.gurock.com/testrail-api2/start
 * http://docs.gurock.com/testrail-api2/accessing
 *
 * Copyright Gurock Software GmbH. See license.md for details.
 */

package com.gurock.testrail;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.text.MessageFormat;
import java.util.Base64;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.util.EntityUtils;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import com.katalon.platform.api.controller.RequestController;
import com.katalon.platform.api.service.ApplicationManager;

public class APIClient {
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    private static final int DEFAULT_RETRY_AFTER_SECONDS = 5;

    private static final int MAX_RETRY_AFTER_SECONDS = 60;

    private String m_user;

    private String m_password;

    private String m_url;

    public APIClient(String base_url) {
        if (!base_url.endsWith("/")) {
            base_url += "/";
        }

        this.m_url = base_url + "index.php?/api/v2/";
    }

    /**
     * Get/Set User
     *
     * Returns/sets the user used for authenticating the API requests.
     */
    public String getUser() {
        return this.m_user;
    }

    public void setUser(String user) {
        this.m_user = user;
    }

    /**
     * Get/Set Password
     *
     * Returns/sets the password used for authenticating the API requests.
     */
    public String getPassword() {
        return this.m_password;
    }

    public void setPassword(String password) {
        this.m_password = password;
    }

    /**
     * Send Get
     *
     * Issues a GET request (read) against the API and returns the result
     * (as Object, see below).
     *
     * Arguments:
     *
     * uri The API method to call including parameters
     * (e.g. get_case/1)
     *
     * Returns the parsed JSON response as standard object which can
     * either be an instance of JSONObject or JSONArray (depending on the
     * API method). In most cases, this returns a JSONObject instance which
     * is basically the same as java.util.Map.
     * 
     * @throws APIException
     * @throws URISyntaxException
     * @throws IOException
     */
    public Object sendGet(String uri) throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return this.sendRequest("GET", uri, null);
    }

    /**
     * Send POST
     *
     * Issues a POST request (write) against the API and returns the result
     * (as Object, see below).
     *
     * Arguments:
     *
     * uri The API method to call including parameters
     * (e.g. add_case/1)
     * data The data to submit as part of the request (e.g.,
     * a map)
     *
     * Returns the parsed JSON response as standard object which can
     * either be an instance of JSONObject or JSONArray (depending on the
     * API method). In most cases, this returns a JSONObject instance which
     * is basically the same as java.util.Map.
     * 
     * @throws APIException
     * @throws URISyntaxException
     * @throws IOException
     */
    public Object sendPost(String uri, Object data)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        return this.sendRequest("POST", uri, data);
    }

    private Object sendRequest(String method, String uri, Object data)
            throws IOException, URISyntaxException, GeneralSecurityException, APIException {
        String absoluteUri = this.m_url + uri;

        RequestBuilder requestBuilder = RequestBuilder.create(method).setUri(absoluteUri);
        boolean isMethodHasBody = method == "POST" || method == "PUT" || method == "PATCH";

        if (isMethodHasBody && data != null) {
            byte[] requestBody = JSONValue.toJSONString(data).getBytes("UTF-8");
            ByteArrayOutputStream outstream = new ByteArrayOutputStream();
            outstream.write(requestBody);

            byte[] bytes = outstream.toByteArray();
            ByteArrayEntity entity = new ByteArrayEntity(bytes);
            entity.setChunked(false);

            requestBuilder.setEntity(entity);
        }

        RequestController requestController = ApplicationManager.getInstance()
                .getControllerManager()
                .getController(RequestController.class);

        HttpResponse response = requestController.sendWithProxy(buildRequest(requestBuilder));
        // TestRail Cloud rate-limits API calls, so wait as told by Retry-After instead of failing the whole upload
        for (int retry = 0; retry < MAX_RATE_LIMIT_RETRIES
                && response.getStatusLine().getStatusCode() == HTTP_TOO_MANY_REQUESTS; retry++) {
            EntityUtils.consumeQuietly(response.getEntity());
            if (!waitForRetry(response)) {
                break;
            }
            response = requestController.sendWithProxy(buildRequest(requestBuilder));
        }

        StatusLine statusLine = response.getStatusLine();
        int statusCode = statusLine.getStatusCode();

        InputStream istream = response.getEntity().getContent();

        String textContent = "";
        if (istream != null) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(istream, "UTF-8"));

            String line;
            String lineSeparator = System.getProperty("line.separator");
            while ((line = reader.readLine()) != null) {
                textContent += line + lineSeparator;
            }

            reader.close();
        }

        if (statusCode != HttpStatus.SC_OK) {
            String reason = statusLine.getReasonPhrase();
            throw new APIException(MessageFormat.format("TestRail API return HTTP code {0} ({1}). Error details: {2}", statusCode, reason, textContent), statusCode);
        }

        Object result;
        if (StringUtils.isNotBlank(textContent)) {
            result = JSONValue.parse(textContent);
        } else {
            result = new JSONObject();
        }

        // Check for any occurred errors and add additional details to
        // the exception message, if any (e.g. the error message returned
        // by TestRail).
        if (statusCode != HttpStatus.SC_OK) {
            String error = "No additional error message received";
            if (result != null && result instanceof JSONObject) {
                JSONObject obj = (JSONObject) result;
                if (obj.containsKey("error")) {
                    error = MessageFormat.format("\"{0}\"", obj.get("error"));
                }
            }

            throw new APIException(
                    MessageFormat.format("TestRail API returned HTTP code {0} ({1})", statusCode, error));
        }

        return result;
    }

    private HttpUriRequest buildRequest(RequestBuilder requestBuilder) {
        HttpUriRequest request = requestBuilder.build();

        request.setHeader("Content-Type", "application/json");

        String basicAuth = getAuthorization(this.m_user, this.m_password);
        request.setHeader("Authorization", "Basic " + basicAuth);
        return request;
    }

    private static boolean waitForRetry(HttpResponse response) {
        int seconds = DEFAULT_RETRY_AFTER_SECONDS;
        Header retryAfter = response.getFirstHeader("Retry-After");
        if (retryAfter != null) {
            try {
                seconds = Math.max(1, Math.min(MAX_RETRY_AFTER_SECONDS, Integer.parseInt(retryAfter.getValue().trim())));
            } catch (NumberFormatException e) {
                // keep the default wait
            }
        }
        System.out.println("TestRail: Rate limited by TestRail, retrying in " + seconds + "s");
        try {
            Thread.sleep(seconds * 1000L);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String getAuthorization(String user, String password) {
        try {
            return Base64.getEncoder().encodeToString((user + ":" + password).getBytes("UTF-8"));
        } catch (UnsupportedEncodingException e) {
            // Not thrown
        }

        return "";
    }
}
