//
// This file is part of the Fuel Java SDK.
//
// Copyright (c) 2013, 2014, 2015, ExactTarget, Inc.
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
// * Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
//
// * Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
//
// * Neither the name of ExactTarget, Inc. nor the names of its
// contributors may be used to endorse or promote products derived
// from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
// LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
// DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
// THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
// OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//

package com.exacttarget.fuelsdk;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.security.AuthProvider;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.apache.log4j.Logger;

/**
 * <code>ETClient</code> is the central object in the Java
 * client library.
 */

public class ETClient {
    private static Logger logger = Logger.getLogger(ETClient.class);

    private static final String DEFAULT_PROPERTIES_FILE_NAME =
            "fuelsdk.properties";
    private static final String DEFAULT_ENDPOINT =
            "https://www.exacttargetapis.com";
    private static final String DEFAULT_AUTH_ENDPOINT =
            "https://auth.exacttargetapis.com";
    private static final String PATH_REQUESTTOKEN =
            "/v1/requestToken";
    private static final String PATH_REQUESTTOKEN_LEGACY =
            "/v1/requestToken?legacy=1";
    private AuthApiVersion authApiVersion = AuthApiVersion.v1;

    private static final String PATH_ENDPOINTS_SOAP =
            "/platform/v1/endpoints/soap";

    private ETConfiguration configuration = null;

    private String clientId = null;
    private String clientSecret = null;

    private String username = null;
    private String password = null;

    private String endpoint = null;
    private String authEndpoint = null;
    private String soapEndpoint = null;


    private Boolean autoHydrateObjects = true;

    private Gson gson = null;

    private ETRestConnection authConnection = null;
    private ETRestConnection restConnection = null;
    private ETSoapConnection soapConnection = null;

    private String accessToken = null;
    private String accessType = null;
    private int expiresIn = 0;
    private String legacyToken = null;
    private String refreshToken = null;

    private long tokenExpirationTime = 0;

    /** 
    * Class constructor, Initializes a new instance of the class.
    */
    public ETClient()
        throws ETSdkException
    {
        this(DEFAULT_PROPERTIES_FILE_NAME);
    }

    /** 
    * Class constructor, Initializes a new instance of the class.
    * @param   file  The configuration file which contains the clientId and clientSecret
    */
    public ETClient(String file)
        throws ETSdkException
    {
        this(new ETConfiguration(file));
    }

    /** 
    * Class constructor, Initializes a new instance of the class.
    * @param configuration      The ETConfiguration object which contains the clientId and clientSecret
    */
    public ETClient(ETConfiguration configuration)
        throws ETSdkException
    {
        this.configuration = configuration;

        clientId = configuration.get("clientId");
        clientSecret = configuration.get("clientSecret");

        ConfigureAuthApi();

        username = configuration.get("username");
        password = configuration.get("password");

        endpoint = configuration.get("endpoint");
        if (endpoint == null) {
            endpoint = DEFAULT_ENDPOINT;
        }

        soapEndpoint = configuration.get("soapEndpoint");


        GsonBuilder gsonBuilder = new GsonBuilder()
            .excludeFieldsWithoutExposeAnnotation()
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        if (logger.isDebugEnabled()) {
            gson = gsonBuilder.setPrettyPrinting().create();
        } else {
            gson = gsonBuilder.create();
        }

        if (clientId != null && clientSecret != null) {
            authConnection = new ETRestConnection(this, authEndpoint, true);
            requestToken();
            restConnection = new ETRestConnection(this, endpoint);
            if (soapEndpoint == null) {
                //
                // If a SOAP endpoint isn't specified automatically determine it:
                //

                ETRestConnection.Response response = restConnection.get(PATH_ENDPOINTS_SOAP);
                String responsePayload = response.getResponsePayload();
                JsonParser jsonParser = new JsonParser();
                JsonObject jsonObject = jsonParser.parse(responsePayload).getAsJsonObject();
                soapEndpoint = jsonObject.get("url").getAsString();
            }
            soapConnection = new ETSoapConnection(this, soapEndpoint, accessToken);
        } else {
            if (username == null || password == null) {
                throw new ETSdkException("must specify either " +
                        "clientId/clientSecret or username/password");
            }
            if (soapEndpoint == null) {
                throw new ETSdkException("must specify soapEndpoint " +
                        "when authenticating with username/password");
            }
            soapConnection = new ETSoapConnection(this, soapEndpoint,
                                                  username,
                                                  password);
        }

        if (configuration.isFalse("autoHydrateObjects")) {
            autoHydrateObjects = false;
        }

        if (logger.isTraceEnabled()) {
            logger.trace("ETClient initialized:");
            logger.trace("  clientId = " + clientId);
            logger.trace("  clientSecret = *");
            logger.trace("  username = " + username);
            logger.trace("  password = *");
            logger.trace("  endpoint = " + endpoint);
            logger.trace("  authEndpoint = " + authEndpoint);
            logger.trace("  soapEndpoint = " + soapEndpoint);
            logger.trace("  autoHydrateObjects = " + autoHydrateObjects);
        }
    }

    private void ConfigureAuthApi() {

        accessType = configuration.get("accessType");
        if (accessType == null){
            accessType = "online";
        }

        authEndpoint = configuration.get("authEndpoint");
        if (authEndpoint == null) {
            authEndpoint = DEFAULT_AUTH_ENDPOINT;
        }


        AuthApiVersion effectiveApiVersion = AuthApiVersion.v1;

        String authVersion = configuration.get("authApiVersion");

        if (authVersion != null){
            try {
                effectiveApiVersion = AuthApiVersion.valueOf(authVersion.trim().toLowerCase());
            }catch(IllegalArgumentException iae){
                effectiveApiVersion = AuthApiVersion.v1;
            }
        }

        authApiVersion = effectiveApiVersion;
    }

    /**
     * 
     * @return The client ID
     */
    public String getClientId() {
        return clientId;
    }

    /**
     * 
     * @return The access token 
     */
    public String getAccessToken() {
        return accessToken;
    }

    /**
     * 
     * @return The LegacyToken
     */
    public String getLegacyToken() {
        return accessToken; //this doesn't look right...
    }

    /**
     * 
     * @return true if auto hydrate objects, false otherwise
     */
    public Boolean autoHydrateObjects() {
        return autoHydrateObjects;
    }

    /**
     * 
     * @return      The ETConfiguration
     */
    public ETConfiguration getConfiguration() {
        return configuration;
    }

    /**
     * 
     * @return      The Gson
     */
    public Gson getGson() {
        return gson;
    }

    /**
     * 
     * @return      The ETRestConnection
     */
    public ETRestConnection getRestConnection() {
        return restConnection;
    }

    /**
     * 
     * @return      The ETSoapConnection
     */
    public ETSoapConnection getSoapConnection() {
        return soapConnection;
    }

    /**
     * @deprecated
     * Use getRestConnection().
     * @return      The ETRestConnection
     */
    @Deprecated
    public ETRestConnection getRESTConnection() {
        return getRestConnection();
    }

    /**
     * @deprecated
     * Use getSoapConnection().
     * @return      The ETSoapConnection
     */
    @Deprecated
    public ETSoapConnection getSOAPConnection() {
        return getSoapConnection();
    }

    /**
     * @return                      The request token
     */
    public synchronized String requestToken()
        throws ETSdkException
    {
        return requestToken(null);
    }

    /**
     * 
     * @param refreshToken          The refresh token
     * @return                      The request token
     */
    public synchronized String requestToken(String refreshToken)
        throws ETSdkException
    {
        if (clientId == null || clientSecret == null) {
            // no-op
            return null;
        }

        logger.debug("requesting access token...");

        //
        // Construct the JSON payload. Set accessType to offline so
        // we get a refresh token. Pass in current refresh token if
        // we have one:
        //

        JsonObject jsonObject = authApiVersion.BuildPayload(this);
        String requestTokenPath = authApiVersion.BuildPath(this);
        String requestPayload = gson.toJson(jsonObject);

        ETRestConnection.Response response =  authConnection.post(requestTokenPath, requestPayload);

        if (response.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new ETSdkException("error obtaining access token "
                                     + "("
                                     + response.getResponseCode()
                                     + " "
                                     + response.getResponseMessage()
                                     + ")");
        }

        //
        // Parse the JSON response into the appropriate instance
        // variables:
        //

        String responsePayload = response.getResponsePayload();

        JsonParser jsonParser = new JsonParser();
        jsonObject = jsonParser.parse(responsePayload).getAsJsonObject();
        logger.debug("received token:");

        authApiVersion.HandleResponse(jsonObject, this);

        //
        // Calculate the token expiration time. As before,
        // System.currentTimeMills() is in milliseconds so
        // we multiply expiresIn by 1000:
        //

        tokenExpirationTime = System.currentTimeMillis() + (expiresIn * 1000);

        logger.debug("access token expires at " + new Date(tokenExpirationTime));

        return accessToken;
    }

    /**
     * 
     * @return      The refresh token
     */
    public synchronized String refreshToken()
        throws ETSdkException
    {
        if (tokenExpirationTime > 0) {
            logger.debug("access token expires at " + new Date(tokenExpirationTime));

            //
            // If the current token expires more than five
            // minutes from now, we don't need to refresh
            // (tokenExpirationTime and System.currentTimeMills()
            // are in milliseconds so we multiply by 1000):
            //

            if (tokenExpirationTime - System.currentTimeMillis() > 5*60*1000) {
                logger.debug("not refreshing access token");
                return accessToken;
            }

            logger.debug("refreshing access token...");

            if (refreshToken == null) {
                throw new ETSdkException("refreshToken == null");
            }
        }

        requestToken(refreshToken);

        soapConnection.setAccessToken(accessToken);

        return accessToken;
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to instantiate
     * @return              The type T which extends from ETApiObject
     */
    public <T extends ETApiObject> T instantiate(Class <T> type)
        throws ETSdkException
    {
        T object = null;
        try {
            object = type.newInstance();
        } catch (Exception ex) {
            throw new ETSdkException("could not instantiate "
                    + type.getName(), ex);
        }
        object.setClient(this);
        return object;
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The ETFilter object to be used to retrieve objects
     * @return              The ETResponse of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          ETFilter filter)
        throws ETSdkException
    {
        return retrieve(type, null, null, filter);
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The filter to be used to retrieve as variable arguments of String
     * @return              The ETResponse of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          String... filter)
        throws ETSdkException
    {
        return retrieve(type, null, null, ETFilter.parse(filter));
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param page          The page number
     * @param pageSize      The page size
     * @param filter        The ETFilter object to be used to retrieve objects
     * @return              The ETResponse of type T which extends from ETApiObject
     */
    @SuppressWarnings("unchecked")
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          Integer page,
                                                          Integer pageSize,
                                                          ETFilter filter)
        throws ETSdkException
    {
        //
        // Find the retrieve method:
        //

        Method retrieve = null;
        for (Class<T> t = type; t != null; t = (Class<T>) t.getSuperclass()) {
            try {
                retrieve = t.getDeclaredMethod("retrieve",
                                               ETClient.class,  // client
                                               Class.class,     // type
                                               Integer.class,   // page
                                               Integer.class,   // pageSize
                                               ETFilter.class); // filter
                if (retrieve != null) {
                    break;
                }
            } catch (Exception ex) {
                // ignore
            }
        }

        if (retrieve == null) {
            throw new ETSdkException("could not find retrieve method for type " + type);
        }

        ETResponse<T> response = null;
        try {
            // first argument of null means method is static
            response = (ETResponse<T>) retrieve.invoke(null,
                                                       this,
                                                       type,
                                                       page,
                                                       pageSize,
                                                       filter);
        } catch (Exception ex) {
            throw new ETSdkException("error invoking retrieve method for type " + type, ex);
        }

        return response;
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param page          The page number
     * @param pageSize      The page size
     * @param filter        The filter to be used to retrieve as variable arguments of String
     * @return              The ETResponse of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          Integer page,
                                                          Integer pageSize,
                                                          String... filter)
        throws ETSdkException
    {
        return retrieve(type, page, pageSize, ETFilter.parse(filter));
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          ETFilter filter,
                                                          String... properties)
        throws ETSdkException
    {
        // make a copy so we're not modifying argument itself
        ETFilter f = new ETFilter();
        f.setExpression(filter.getExpression());
        for (String property : properties) {
            f.addProperty(property);
        }
        return retrieve(type, null, null, f);
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          ETFilter filter,
                                                          Integer page,
                                                          Integer pageSize,
                                                          String... properties)
        throws ETSdkException
    {
        // make a copy so we're not modifying argument itself
        ETFilter f = new ETFilter();
        f.setExpression(filter.getExpression());
        for (String property : properties) {
            f.addProperty(property);
        }
        return retrieve(type, page, pageSize, f);
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> ETResponse<T> retrieve(Class<T> type,
                                                          String filter,
                                                          Integer page,
                                                          Integer pageSize,
                                                          String... properties)
        throws ETSdkException
    {
        // make a copy so we're not modifying argument itself
        ETFilter f = new ETFilter();
        f.setExpression(ETExpression.parse(filter));
        for (String property : properties) {
            f.addProperty(property);
        }
        return retrieve(type, page, pageSize, f);
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The ETFilter object to be used to retrieve objects
     * @return              The type T which extends from ETApiObject
     */
    public <T extends ETApiObject> T retrieveObject(Class<T> type,
                                                    ETFilter filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter);
        if (response == null) {
            return null;
        }
        return response.getObject();
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The filter to be used to retrieve objects as variable arguments of String
     * @return              The type T which extends from ETApiObject
     */
    public <T extends ETApiObject> T retrieveObject(Class<T> type,
                                                    String... filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter);
        if (response == null) {
            return null;
        }
        return response.getObject();
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> T retrieveObject(Class<T> type,
                                                    ETFilter filter,
                                                    String... properties)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter, properties);
        if (response == null) {
            return null;
        }
        return response.getObject();
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The ETFilter object to be used to retrieve objects
     * @return              The List of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           ETFilter filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param filter        The filter to be used to retrieve objects as variable arguments of String
     * @return              The List of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           String... filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param page          The page number
     * @param pageSize      The page size
     * @param filter        The ETFilter object to be used to retrieve objects
     * @return              The List of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           Integer page,
                                                           Integer pageSize,
                                                           ETFilter filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, page, pageSize, filter);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * 
     * @param <T>           The type which extends from ETApiObject
     * @param type          The class type to retrieve
     * @param page          The page number
     * @param pageSize      The page size
     * @param filter        The filter to be used to retrieve objects as variable arguments of String
     * @return              The List of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           Integer page,
                                                           Integer pageSize,
                                                           String... filter)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, page, pageSize, filter);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           ETFilter filter,
                                                           String... properties)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter, properties);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           ETFilter filter,
                                                           Integer page,
                                                           Integer pageSize,
                                                           String... properties)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter, page, pageSize, properties);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * @deprecated
     * Pass properties in <code>filter</code> argument.
     */
    @Deprecated
    public <T extends ETApiObject> List<T> retrieveObjects(Class<T> type,
                                                           String filter,
                                                           Integer page,
                                                           Integer pageSize,
                                                           String... properties)
        throws ETSdkException
    {
        ETResponse<T> response = retrieve(type, filter, page, pageSize, properties);
        if (response == null) {
            return null;
        }
        return response.getObjects();
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The objects to be created as variable arguments of type T
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> create(T... objects)
        throws ETSdkException
    {
        return createUpdateDelete("create", Arrays.asList(objects));
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The List of objects of type T to be created
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> create(List<T> objects)
        throws ETSdkException
    {
        return createUpdateDelete("create", objects);
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The objects to be updated as variable arguments of type T
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> update(T... objects)
        throws ETSdkException
    {
        return createUpdateDelete("update", Arrays.asList(objects));
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The List of objects of type T to be updated
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> update(List<T> objects)
        throws ETSdkException
    {
        return createUpdateDelete("update", objects);
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The objects to be deleted as variable arguments of type T
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> delete(T... objects)
        throws ETSdkException
    {
        return createUpdateDelete("delete", Arrays.asList(objects));
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param objects               The List of objects of type T to be deleted
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> delete(List<T> objects)
        throws ETSdkException
    {
        return createUpdateDelete("delete", objects);
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param type                  The class type to delete
     * @param filter                The filter to be used to do filter
     * @param values                The values as variable arguments of String which is used to do the update 
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
     public <T extends ETApiObject> ETResponse<T> update(Class<T> type,
                                                        String filter,
                                                        String... values)
        throws ETSdkException
    {
        // XXX optimize

        ETResponse<T> response = retrieve(type, filter);

        List<T> objects = response.getObjects();
        for (T object : objects) {
            for (String value : values) {
                ETExpression expression = ETExpression.parse(value);
                Field field = object.getField(expression.getProperty());
                try {
                    // briefly change accessibility so we can set value
                    boolean isAccessible = field.isAccessible();
                    if (!isAccessible) {
                        field.setAccessible(true);
                    }
                    field.set(object, expression.getValue());
                    field.setAccessible(isAccessible);
                } catch (Exception ex) {
                    throw new ETSdkException("could not update property \""
                            + expression.getProperty()
                            + "\" to value \""
                            + value
                            + "\" on object "
                            + object,
                            ex);
                }
            }
        }

        return update(objects);
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param type                  The class type to delete
     * @param filter                The ETFilter object to be used for filter
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> delete(Class<T> type,
                                                        ETFilter filter)
        throws ETSdkException
    {
        ETExpression expression = filter.getExpression();
        if (expression.getOperator() == ETExpression.Operator.EQUALS &&
            expression.getProperty().equals("id"))
        {
            //
            // Optimization: If we're simply deleting a single
            // object given its primary key just instantiate a
            // new object and set the primary key accordingly:
            //

            T object = null;
            try {
                object = type.newInstance();
            } catch (Exception ex) {
                throw new ETSdkException("could not instantiate "
                        + type.getName(), ex);
            }
            object.setId(expression.getValue());
            List<T> objects = new ArrayList<T>();
            objects.add(object);
            return delete(objects);
        }
        ETResponse<T> response = retrieve(type, filter);
        return delete(response.getObjects());
    }

    /**
     * @param <T>                   The type which extends from ETApiObject
     * @param type                  The class type to delete
     * @param filter                The filter to be used to do filter
     * @return                      The ETResponse object of type T which extends from ETApiObject
     */
    public <T extends ETApiObject> ETResponse<T> delete(Class<T> type,
                                                        String filter)
        throws ETSdkException
    {
        return delete(type, ETFilter.parse(filter));
    }

    @SuppressWarnings("unchecked")
    private <T extends ETApiObject> ETResponse<T> createUpdateDelete(String method,
                                                                     List<T> objects)
        throws ETSdkException
    {
        Class<T> type = (Class<T>) objects.get(0).getClass();

        //
        // Find the appropriate method (create, update, or delete):
        //

        Method m = null;
        for (Class<T> t = type; t != null; t = (Class<T>) t.getSuperclass()) {
            try {
                m = t.getDeclaredMethod(method, ETClient.class, List.class);
                if (m != null) {
                    break;
                }
            } catch (Exception ex) {
                // ignore
            }
        }

        if (m == null) {
            throw new ETSdkException("could not find " + method + " method for type " + type);
        }

        ETResponse<T> response = null;
        try {
            // first argument of null means method is static
            response = (ETResponse<T>) m.invoke(null, this, objects);
        } catch (Exception ex) {
            throw new ETSdkException("error invoking " + method + " method for type " + type, ex);
        }

        return response;
    }

    private enum AuthApiVersion{
        v1 {
            @Override
            String BuildPath(ETClient client) {
                if (client.getConfiguration().isTrue("requestLegacyToken")){
                    return PATH_REQUESTTOKEN_LEGACY;
                }
                return PATH_REQUESTTOKEN;
            }
            @Override
            JsonObject BuildPayload(ETClient client){
                JsonObject jsonObject = new JsonObject();

                jsonObject.addProperty("clientId", client.clientId);
                jsonObject.addProperty("clientSecret", client.clientSecret);
                jsonObject.addProperty("accessType", client.accessType);
                if (client.accessType.equals("offline") && client.refreshToken != null){
                    jsonObject.addProperty("refreshToken", client.refreshToken);
                }
                return jsonObject;
            }

            void HandleResponse(JsonObject jsonObject, ETClient client) {
                client.accessToken = jsonObject.get("accessToken").getAsString();
                logger.debug("  accessToken: " + client.accessToken);
                client.expiresIn = jsonObject.get("expiresIn").getAsInt();
                logger.debug("  expiresIn: " + client.expiresIn);
                JsonElement jsonElement = jsonObject.get("legacyToken");
                if (jsonElement != null) {
                    client.legacyToken = jsonElement.getAsString();
                }
                logger.debug("  legacyToken: " + client.legacyToken);
                if (jsonObject.get("refreshToken") != null) {
                    client.refreshToken = jsonObject.get("refreshToken").getAsString();
                }
            }
        },
        v2 {
            @Override
            String BuildPath(ETClient client) {
                StringBuilder path = new StringBuilder("/v2/requestToken");
                Properties properties = new Properties();

                String accountId = client.getConfiguration().get("account_id");
                if (accountId != null) {
                    properties.setProperty("account_id", accountId);
                }

                if (client.getConfiguration().isTrue("requestLegacyToken")) {
                    properties.setProperty("legacy", "1");
                }

                String delimiter = "?";
                Set<Map.Entry<Object, Object>> entries = properties.entrySet();
                for (Map.Entry<Object, Object> property : entries) {

                    path.append(delimiter);
                    path.append(String.format("%s=%s", property.getKey(), property.getValue()));
                    delimiter = "&";
                }

                return path.toString();
            }
            @Override
            JsonObject BuildPayload(ETClient client){
                JsonObject jsonObject = new JsonObject();

                jsonObject.addProperty("client_id", client.clientId);
                jsonObject.addProperty("client_secret", client.clientSecret);

                String grantType = client.getConfiguration().get("grant_type");
                if (grantType == null){
                    grantType = "client_credentials";
                }

                jsonObject.addProperty("grant_type", grantType);

                jsonObject.addProperty("access_type", client.accessType);
                if (client.accessType.equals("offline") && client.refreshToken != null){
                    jsonObject.addProperty("refresh_token", client.refreshToken);
                }

                return jsonObject;
            }

            @Override
            void HandleResponse(JsonObject jsonObject, ETClient client) {

                client.accessToken = jsonObject.get("access_token").getAsString();
                logger.debug("  access_token: " + client.accessToken);

                client.expiresIn = jsonObject.get("expires_in").getAsInt();
                logger.debug("  expires_in: " + client.expiresIn);

                JsonElement jsonElement = jsonObject.get("legacy_token");
                if (jsonElement != null) {
                    client.legacyToken = jsonElement.getAsString();
                }
                logger.debug("  legacy_token: " + client.legacyToken);

                if (jsonObject.get("refresh_token") != null) {
                    client.refreshToken = jsonObject.get("refresh_token").getAsString();
                }

                client.endpoint = jsonObject.get("rest_instance_url").getAsString();

                if (jsonObject.get("auth_instance_url") != null){
                    client.authEndpoint = jsonObject.get("auth_instance_url").getAsString();
                }

                if (jsonObject.get("soap_instance_url") != null){
                    client.soapEndpoint = jsonObject.get("soap_instance_url").getAsString();
                }
            }
        };

        abstract String BuildPath(ETClient client);

        abstract JsonObject BuildPayload(ETClient client);

        abstract void HandleResponse(JsonObject jsonObject, ETClient client);
    }
}
