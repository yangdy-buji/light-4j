/*
 * Copyright (c) 2016 Network New Technologies Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.networknt.body;

import com.fasterxml.jackson.core.type.TypeReference;
import com.networknt.config.Config;
import com.networknt.handler.Handler;
import com.networknt.handler.MiddlewareHandler;
import com.networknt.httpstring.AttachmentConstants;
import com.networknt.utility.ModuleRegistry;
import com.networknt.utility.StringUtils;
import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * This is a handler that parses the body into a Map or List if the input content type is "application/json"
 * or "multipart/form-data" or "application/x-www-form-urlencoded". For other content type, don't parse it.
 * In order to trigger this middleware, the content type must be set in header for post, put and patch.
 * <p>
 * The request body string can be cached into exchange attachment with the attachment key "REQUEST_BODY_STRING"
 * when the content type is "application/json".
 * <p>
 * Currently, it is only used in light-rest-4j framework as subsequent handler will use the parsed
 * body for further processing. Other frameworks like light-graphql-4j or light-hybrid-4j won't
 * need this middleware handler.
 * <p>
 * Created by steve on 29/09/16.
 */

public class BodyHandler implements MiddlewareHandler {
    static final Logger logger = LoggerFactory.getLogger(BodyHandler.class);
    static final String CONTENT_TYPE_MISMATCH = "ERR10015";

    // request body will be parsed during validation and it is attached to the exchange, in JSON,
    // it could be a map or list. So treat it as Object in the attachment.
    public static final AttachmentKey<Object> REQUEST_BODY = AttachmentConstants.REQUEST_BODY;
    public static final AttachmentKey<String> REQUEST_BODY_STRING = AttachmentConstants.REQUEST_BODY_STRING;

    public static final String CONFIG_NAME = "body";

    public static  BodyConfig config;

    private volatile HttpHandler next;

    public BodyHandler() {
        if (logger.isInfoEnabled()) logger.info("BodyHandler is loaded.");
        config = BodyConfig.load();
    }

    /**
     * Please don't use this constructor as it is designed for testing only.
     * @param configName String
     * @deprecated
     */
    public BodyHandler(String configName) {
        if (logger.isInfoEnabled()) logger.info("BodyHandler is loaded.");
        config = BodyConfig.load(configName);
    }

    /**
     * Check the header starts with application/json and parse it into map or list
     * based on the first character "{" or "[". Otherwise, check the header starts
     * with application/x-www-form-urlencoded or multipart/form-data and parse it
     * into formdata
     *
     * @param exchange HttpServerExchange
     * @throws Exception Exception
     */
    @Override
    public void handleRequest(final HttpServerExchange exchange) throws Exception {
        if(logger.isDebugEnabled()) logger.debug("BodyHandler.handleRequest starts.");
        // parse the body to map or list if content type is application/json
        String contentType = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
        if (contentType != null) {
            if (exchange.isInIoThread()) {
                exchange.dispatch(this);
                return;
            }
            exchange.startBlocking();
            try {
                if (contentType.startsWith("application/json")) {
                    InputStream inputStream = exchange.getInputStream();
                    String unparsedRequestBody = StringUtils.inputStreamToString(inputStream, StandardCharsets.UTF_8);
                    // attach the unparsed request body into exchange if the cacheRequestBody is enabled in body.yml
                    if (config.isCacheRequestBody()) {
                        exchange.putAttachment(AttachmentConstants.REQUEST_BODY_STRING, unparsedRequestBody);
                    }
                    // attach the parsed request body into exchange if the body parser is enabled
                    boolean res = attachJsonBody(exchange, unparsedRequestBody);
                    // this will ensure that the next handler won't be called.
                    if (!res) {
                        if(logger.isDebugEnabled()) logger.debug("BodyHandler.handleRequest ends with an error.");
                        return;
                    }
                } else if (contentType.startsWith("text/plain")) {
                    InputStream inputStream = exchange.getInputStream();
                    String unparsedRequestBody = StringUtils.inputStreamToString(inputStream, StandardCharsets.UTF_8);
                    exchange.putAttachment(AttachmentConstants.REQUEST_BODY, unparsedRequestBody);
                } else if (contentType.startsWith("multipart/form-data") || contentType.startsWith("application/x-www-form-urlencoded")) {
                    // attach the parsed request body into exchange if the body parser is enabled
                    attachFormDataBody(exchange);
                } else {
                    InputStream inputStream = exchange.getInputStream();
                    exchange.putAttachment(AttachmentConstants.REQUEST_BODY, inputStream);
                }
            } catch (IOException e) {
                logger.error("IOException: ", e);
                setExchangeStatus(exchange, CONTENT_TYPE_MISMATCH, contentType);
                if(logger.isDebugEnabled()) logger.debug("BodyHandler.handleRequest ends with an error.");
                return;
            }
        }
        if(logger.isDebugEnabled()) logger.debug("BodyHandler.handleRequest ends.");
        Handler.next(exchange, next);
    }

    /**
     * Method used to parse the body into FormData and attach it into exchange
     *
     * @param exchange exchange to be attached
     * @throws IOException
     */
    private void attachFormDataBody(final HttpServerExchange exchange) throws IOException {
        Object data;
        FormParserFactory formParserFactory = FormParserFactory.builder().build();
        FormDataParser parser = formParserFactory.createParser(exchange);
        if (parser != null) {
            FormData formData = parser.parseBlocking();
            data = BodyConverter.convert(formData);
            exchange.putAttachment(AttachmentConstants.REQUEST_BODY, data);
        } else {
            InputStream inputStream = exchange.getInputStream();
            exchange.putAttachment(AttachmentConstants.REQUEST_BODY, inputStream);
        }
    }

    /**
     * Method used to parse the body into a Map or a List and attach it into exchange
     *
     * @param exchange exchange to be attached
     * @param string   unparsed request body
     * @throws IOException IO Exception
     * @return boolean
     */
    private boolean attachJsonBody(final HttpServerExchange exchange, String string) throws IOException {
        Object body;
        if (string != null) {
            string = string.trim();
            if (string.startsWith("{")) {
                body = Config.getInstance().getMapper().readValue(string, new TypeReference<Map<String, Object>>() {
                });
            } else if (string.startsWith("[")) {
                body = Config.getInstance().getMapper().readValue(string, new TypeReference<List<Object>>() {
                });
            } else {
                // error here. The content type in head doesn't match the body.
                setExchangeStatus(exchange, CONTENT_TYPE_MISMATCH, "application/json");
                return false;
            }
            exchange.putAttachment(AttachmentConstants.REQUEST_BODY, body);
        }
        // if this is the get or delete request, the body wil be null, but we still need to go to the next handler.
        return true;
    }

    @Override
    public HttpHandler getNext() {
        return next;
    }

    @Override
    public MiddlewareHandler setNext(final HttpHandler next) {
        Handlers.handlerNotNull(next);
        this.next = next;
        return this;
    }

    @Override
    public boolean isEnabled() {
        return config.isEnabled();
    }

    @Override
    public void register() {
        ModuleRegistry.registerModule(BodyHandler.class.getName(), Config.getInstance().getJsonMapConfigNoCache(CONFIG_NAME), null);
    }

    @Override
    public void reload() {
        config = (BodyConfig) Config.getInstance().getJsonObjectConfig(CONFIG_NAME, BodyConfig.class);
    }
}
