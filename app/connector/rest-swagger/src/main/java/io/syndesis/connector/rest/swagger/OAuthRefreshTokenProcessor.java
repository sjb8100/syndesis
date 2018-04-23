/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.connector.rest.swagger;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.http.common.HttpOperationFailedException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class OAuthRefreshTokenProcessor implements Processor {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Logger LOG = LoggerFactory.getLogger(OAuthRefreshTokenProcessor.class);

    final AtomicReference<String> lastRefreshTokenTried = new AtomicReference<>(null);

    private final SwaggerConnectorComponent component;

    public OAuthRefreshTokenProcessor(final SwaggerConnectorComponent component) {
        this.component = component;
    }

    @Override
    public void process(final Exchange exchange) throws Exception {
        final HttpOperationFailedException httpFailure = (HttpOperationFailedException) exchange.getProperty(Exchange.EXCEPTION_CAUGHT);
        LOG.warn("Failed invoking the remote API, status: {} {}, response body: {}", httpFailure.getStatusCode(),
            httpFailure.getStatusText(), httpFailure.getResponseBody());

        final int statusCode = httpFailure.getStatusCode();
        final Set<Integer> statusesToRefreshFor = component.getRefreshTokenRetryStatusesSet();
        if (!statusesToRefreshFor.contains(statusCode)) {
            throw httpFailure;
        }

        final String currentRefreshToken = component.getRefreshToken();
        lastRefreshTokenTried.getAndUpdate(last -> {
            if (last != null && last.equals(currentRefreshToken)) {
                return last;
            }

            return null;
        });

        if (!lastRefreshTokenTried.compareAndSet(null, currentRefreshToken)) {
            throw httpFailure;
        }

        tryToRefreshAccessToken();

        lastRefreshTokenTried.set(currentRefreshToken);

        // we need to throw the failure so that the exchange fails, otherwise it
        // might be considered successful and we do not perform any
        // retry, and that would lead to data inconsistencies
        throw httpFailure;
    }

    CloseableHttpClient createHttpClient() {
        return HttpClientBuilder.create().build();
    }

    HttpUriRequest createHttpRequest() {
        final String tokenEndpoint = component.getTokenEndpoint();
        final RequestBuilder builder = RequestBuilder.post(tokenEndpoint);

        if (component.isAuthorizeUsingParameters()) {
            builder.addParameter("client_id", component.getClientId())//
                .addParameter("client_secret", component.getClientSecret());
        }

        builder.addParameter("refresh_token", component.getRefreshToken())//
            .addParameter("grant_type", "refresh_token");

        return builder.build();
    }

    void tryToRefreshAccessToken() {
        LOG.info("Trying to refresh the OAuth2 access token");

        try (final CloseableHttpClient client = createHttpClient()) {
            final HttpUriRequest request = createHttpRequest();

            try (final CloseableHttpResponse response = client.execute(request)) {
                final StatusLine statusLine = response.getStatusLine();
                if (statusLine.getStatusCode() != HttpStatus.SC_OK) {
                    final HttpEntity entity = response.getEntity();
                    LOG.error("Unable to refresh the access token, received status: `{}`, response: `{}`", statusLine,
                        EntityUtils.toString(entity));
                    return;
                }

                final HttpEntity entity = response.getEntity();
                final JsonNode body = JSON.readTree(entity.getContent());

                final JsonNode accessToken = body.get("access_token");
                if (accessToken != null && !accessToken.isNull() && !accessToken.asText().isEmpty()) {
                    component.setAccessToken(accessToken.asText());

                    final JsonNode refreshToken = body.get("refresh_token");
                    if (refreshToken != null && !refreshToken.isNull() && !refreshToken.asText().isEmpty()) {
                        component.setRefreshToken(refreshToken.asText());
                    }
                }
            }
        } catch (final IOException e) {
            LOG.error("Unable to refresh the access token", e);
        }
    }

}