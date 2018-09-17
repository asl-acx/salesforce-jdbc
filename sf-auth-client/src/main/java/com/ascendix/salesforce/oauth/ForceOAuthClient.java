package com.ascendix.salesforce.oauth;

import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpIOExceptionHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.HttpStatusCodes;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.ExponentialBackOff;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Slf4j
public class ForceOAuthClient {

    private static final String LOGIN_URL = "https://login.salesforce.com/services/oauth2/userinfo";
    private static final String TEST_LOGIN_URL = "https://test.salesforce.com/services/oauth2/userinfo";
    private static final String API_VERSION = "43";

    private static final String BAD_TOKEN_SF_ERROR_CODE = "Bad_OAuth_Token";
    private static final String MISSING_TOKEN_SF_ERROR_CODE = "Missing_OAuth_Token";
    private static final String WRONG_ORG_SF_ERROR_CODE = "Wrong_Org";
    private static final String BAD_ID_SF_ERROR_CODE = "Bad_Id";
    private static final String INTERNAL_SERVER_ERROR_SF_ERROR_CODE = "Internal Error";

    private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();

    private final long connectTimeout;
    private final long readTimeout;
    private String loginUrl;

    private String responseContent = StringUtils.EMPTY;

    public ForceOAuthClient(long connectTimeout, long readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    private void initLoginUrl(boolean sandbox) {
        this.loginUrl = sandbox ? TEST_LOGIN_URL : LOGIN_URL;
    }

    public ForceUserInfo getUserInfo(String accessToken, boolean sandbox) {
        initLoginUrl(sandbox);
        HttpRequestFactory requestFactory = buildHttpRequestFactory(accessToken);
        try {
            HttpResponse result = requestFactory.buildGetRequest(new GenericUrl(this.loginUrl)).execute();
            ForceUserInfo forceUserInfo = result.parseAs(ForceUserInfo.class);
            extractPartnerUrl(forceUserInfo);
            extractInstance(forceUserInfo);

            return forceUserInfo;
        } catch (HttpResponseException e) {
            if (isBadTokenError(e)) {
                throw new BadOAuthTokenException("Bad OAuth Token: " + accessToken);
            }
            throw new ForceClientException("Response error: " + e.getStatusCode() + " " + e.getContent());
        } catch (IOException e) {
            throw new ForceClientException("IO error: " + e.getMessage(), e);
        } finally {
            responseContent = StringUtils.EMPTY;
        }
    }

    private HttpRequestFactory buildHttpRequestFactory(String accessToken) {
        Credential credential = new Credential(BearerToken.authorizationHeaderAccessMethod())
                .setAccessToken(accessToken);

        return HTTP_TRANSPORT.createRequestFactory(
                request -> {
                    request.setConnectTimeout(Math.toIntExact(connectTimeout));
                    request.setReadTimeout(Math.toIntExact(readTimeout));
                    request.setParser(JSON_FACTORY.createJsonObjectParser());
                    request.setInterceptor(credential);
                    // Sets the HTTP unsuccessful (non-2XX) response handler or null or none.
                    request.setUnsuccessfulResponseHandler(buildUnsuccessfulResponseHandler());
                    // Sets the HTTP I/O exception handler or null for none.
                    request.setIOExceptionHandler(buildIOExceptionHandler());
                    request.setNumberOfRetries(10);
                });
    }


    private static void extractPartnerUrl(ForceUserInfo userInfo) {
        if (userInfo.getUrls() == null || !userInfo.getUrls().containsKey("partner")) {
            throw new IllegalStateException("User info doesn't contain partner URL: " + userInfo.getUrls());
        }
        userInfo.setPartnerUrl(userInfo.getUrls().get("partner").replace("{version}", API_VERSION));
    }

    private boolean isBadTokenError(HttpResponseException e) {
        return ((e.getStatusCode() == HttpStatusCodes.STATUS_CODE_FORBIDDEN)
                &&
                StringUtils.equalsAnyIgnoreCase(responseContent, BAD_TOKEN_SF_ERROR_CODE) ||
                StringUtils.equalsAnyIgnoreCase(responseContent, MISSING_TOKEN_SF_ERROR_CODE) ||
                StringUtils.equalsAnyIgnoreCase(responseContent, WRONG_ORG_SF_ERROR_CODE))
                ||
                (e.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND &&
                        StringUtils.equalsAnyIgnoreCase(responseContent, BAD_ID_SF_ERROR_CODE));
    }

    private boolean isInternalError(HttpResponse response) {
        try (InputStream is = response.getContent()) {
            responseContent = IOUtils.toString(response.getContent(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        return response.getStatusCode() / 100 == 5 ||
                (response.getStatusCode() == HttpStatusCodes.STATUS_CODE_NOT_FOUND
                        && StringUtils.containsIgnoreCase(responseContent, INTERNAL_SERVER_ERROR_SF_ERROR_CODE));

    }

    private HttpBackOffUnsuccessfulResponseHandler buildUnsuccessfulResponseHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff.Builder()
                .setInitialIntervalMillis(500)
                .setMaxElapsedTimeMillis(30000)
                .setMaxIntervalMillis(10000)
                .setMultiplier(1.5)
                .setRandomizationFactor(0.5)
                .build();
        HttpBackOffUnsuccessfulResponseHandler.BackOffRequired required = response -> isInternalError(response);
        return new HttpBackOffUnsuccessfulResponseHandler(backOff).setBackOffRequired(required);
    }

    private HttpIOExceptionHandler buildIOExceptionHandler() {
        return new HttpIOExceptionHandler() {
            @Override
            public boolean handleIOException(HttpRequest httpRequest, boolean supportsRetry) throws IOException {
                if (!supportsRetry) {
                    return false;
                }
                return true;
            }
        };
    }

    private static void extractInstance(ForceUserInfo userInfo) {
        String profileUrl = userInfo.getPartnerUrl();
        if (StringUtils.isBlank(profileUrl)) {
            return;
        }
        profileUrl = profileUrl.replace("https://", "");
        try {
            String instance = StringUtils.split(profileUrl, '.')[0];
            userInfo.setInstance(instance);
        } catch (Exception e) {
            log.error("Failed to parse instance name from profile: {}", profileUrl, e);
        }
    }

}
