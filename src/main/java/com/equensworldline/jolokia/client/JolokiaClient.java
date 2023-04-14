package com.equensworldline.jolokia.client;

import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONObject;
import com.equensworldline.jolokia.client.gui.DisplayData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static com.equensworldline.jolokia.client.DataType.STATUS;
import static com.equensworldline.jolokia.client.DataType.TEST;

public class JolokiaClient {
    private static final Logger logger = LoggerFactory.getLogger(JolokiaClient.class);

    private URL endpoint;
    private String authorizationHeader;
    private JolokiaRequestBuilder requestBuilder;
    private String trace;
    private String brokerName;
    private long responseTimeMillis = 0;

    public JolokiaClient(URL endpoint, JolokiaRequestBuilder requestBuilder, String trace) {
        this.endpoint = endpoint;
        this.requestBuilder = requestBuilder;
        this.trace = trace;
        ByteBuffer credentials = ByteBuffer.allocate(1024)
            .put(MyArgs.env("ACTIVEMQ_USER", DisplayData.USER_DEFAULT).getBytes(StandardCharsets.UTF_8))
            .put(":".getBytes(StandardCharsets.UTF_8))
            .put(MyArgs.env("ACTIVEMQ_PASSWORD", DisplayData.PASSWORD_DEFAULT).getBytes(StandardCharsets.UTF_8));
        if (endpoint.getPath().contains("namespaces") && endpoint.getPath().contains("pods")) {
            logger.debug("Detected that you try to access Openshift hosted A-MQ; switching to token-based authentication with the help of Opencshift CLI (oc)");
            if ("https".equals(endpoint.getProtocol())) {
                logger.info("Detected a TLS-connection is used to Openshift-hosted A-MQ; if the connection fails on untrusted certificate ensure that the proper OpenshiftSigner CA certificate is in your truststore as that is an untrusted self-signed certificate");
            }
            String token = determineOpenshiftAccessToken();
            logger.trace("Token retrieved was '{}'", token);
            this.authorizationHeader = "Bearer " + token;
        } else {
            String basicAuth = Base64.getEncoder().encodeToString(Arrays.copyOf(credentials.array(), credentials.position()));
            this.authorizationHeader = "Basic " + basicAuth;
            logger.debug("Setting credentials to {}", this.authorizationHeader);
        }
        determineBroker();
    }

    private String determineOpenshiftAccessToken() {
        String token = null;
        try {
            Process p = Runtime.getRuntime().exec("oc whoami -t");
            try (BufferedReader processConsole = new BufferedReader(new InputStreamReader(p.getInputStream()));
            ) {
                p.waitFor(5, TimeUnit.SECONDS);
                token = processConsole.readLine();
            } finally {
                if (token == null) {
                    if (logger.isErrorEnabled()) {
                        logStderrAsError(p);
                    }
                    System.exit(1);
                }
            }
        } catch (InterruptedException | IOException e) {
            logger.error("Error resolving OpenShift CLI token (are you properly logged in?)", e);
            Thread.currentThread().interrupt();
            System.exit(1);
        }
        return token;
    }

    private void logStderrAsError(Process p) throws IOException {
        StringBuilder msg = new StringBuilder("Error retrieving token, error message: '");
        try (BufferedReader processError = new BufferedReader((new InputStreamReader(p.getErrorStream())))) {
            try {
                String line = processError.readLine();
                while (line != null) {
                    msg.append(line).append("\\n");
                    line = processError.readLine();
                }
            } catch (IOException ioe) {
                logger.debug("Ignoring IOException",ioe);
                // ignore - we only try to do our best to get a clear error msg
            }
            String errorMessage = msg.append("'").toString();
            logger.error(errorMessage);
        }
    }

    public long getResponseTimeMillis() {
        return responseTimeMillis;
    }

    public final void determineBroker() {
        JolokiaRequestBuilder brokerNameBuilder = requestBuilder.copy().dataType(STATUS);
        String input = brokerNameBuilder.buildQueryRequest();
        long timeNanos = System.nanoTime();
        String jsonString = httpRequest(input);
        responseTimeMillis = (System.nanoTime() - timeNanos) / 1000000;
        MyJSONObject json = null;
        try {
            json = new MyJSONObject(jsonString); // Convert text to object
            int result = json.getInteger("status").intValue();
            if (result != 200) {
                logger.error("Unavailable broker in endpointlist: {} HTTP status was {}", endpoint, result);
            }
            MyJSONObject tvalue = (MyJSONObject) json.get("value");
            if (tvalue != null && tvalue.hasNext()) {
                String key = tvalue.next();
                MyJSONObject value = (MyJSONObject) tvalue.get(key);
                this.brokerName = value.getString("BrokerName");
                this.requestBuilder.broker(brokerName);
            }
        } catch (RuntimeException e) {
            logger.error("Query error - unexpected response", e);
            logger.error("Response was: {}", jsonString);
        }
        if (trace != null) {
            logger.info("Broker name determination succesful, query response time {} ms", responseTimeMillis);
        }

    }

    public MyJSONObject query() {
        String input = requestBuilder.buildQueryRequest();

        long timeNanos = System.nanoTime();
        String jsonString = httpRequest(input);
        responseTimeMillis = (System.nanoTime() - timeNanos) / 1000000;

        MyJSONObject json = null;
        try {
            json = new MyJSONObject(jsonString); // Convert text to object
        } catch (RuntimeException e) {
            logger.error("Query error - unexpected response", e);
            logger.error("Response was: {}", jsonString);
            System.exit(1);
        }

        if (trace != null || requestBuilder.getDataType() == TEST) {
            logger.info("Input: {}", input);
            logger.info("Resulted in:");
            if (logger.isInfoEnabled()) {
                logger.info(json.toString(4)); // Print it with specified indentation
            }
            logger.info("Query time {} ms", responseTimeMillis);
            if (requestBuilder.getDataType() == TEST) {
                logger.info("Test exit");
                System.exit(1);    // Stop because we have already shown the JSON data returned from the test query
            }
        }

        return json;
    }

    /**
     * @param destinationNames The destination names to purge
     * @throws IllegalStateException If destinationNames contains one or more entries that are an empty string or a string containing wildcards as purges should be done on individual queues/topics
     */
    public void purge(String... destinationNames) {
        JolokiaRequestBuilder purgeBuilder = requestBuilder.copy();
        long timeStartNanos = System.nanoTime();
        for (String destination : destinationNames) {
            String input = purgeBuilder.filter(destination).buildPurgeRequest();

            long timeNanos = System.nanoTime();
            httpRequest(input);
            long responseTimeLocal = (System.nanoTime() - timeNanos) / 1000000;
            logger.debug("Purge request for {} processed in {}ms", destination, responseTimeLocal);
        }
        responseTimeMillis = (System.nanoTime() - timeStartNanos) / 1000000;
    }

    public void resetStatistics(String... destinationNames) {
        JolokiaRequestBuilder resetBuilder = requestBuilder.copy();
        long timeStartNanos = System.nanoTime();
        for (String destinationName : destinationNames) {
            String input = resetBuilder.filter(destinationName).buildResetItemStatisticsRequest();

            long timeNanos = System.nanoTime();
            httpRequest(input);
            long responseTimeLocal = (System.nanoTime() - timeNanos) / 1000000;
            logger.debug("Stat reset request for {} processed in {}ms", destinationName, responseTimeLocal);
        }
        responseTimeMillis = (System.nanoTime() - timeStartNanos) / 1000000;
    }

    public void resetBrokerStatistics() {
        JolokiaRequestBuilder resetBuilder = requestBuilder.copy();
        String input = resetBuilder.buildResetBrokerStatisticsRequest();
        long timeStartNanos = System.nanoTime();
        httpRequest(input);
        responseTimeMillis = (System.nanoTime() - timeStartNanos) / 1000000;
        logger.debug("Stat reset request for broker processed in {}ms", responseTimeMillis);
    }

    // TODO: use Apache HTTP-Client for true persistent pooled/cached connections
    private String httpRequest(String input) {
        HttpURLConnection conn = null;
        OutputStream os = null;
        BufferedReader br = null;

        String jsonString = null;
        try {
            conn = (HttpURLConnection) endpoint.openConnection();
            conn.setReadTimeout(60000);    // Sometimes Hawtio does not respond and we do not see it for a long time
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", authorizationHeader);
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept-Charset", "UTF-8");
            logger.trace("Sending request for <<{}>>", input);
            os = conn.getOutputStream();
            os.write(input.getBytes());
            os.flush();

            if (conn.getResponseCode() != 200) {
                // Return json error format - application will continue trying in
                // case connection comes up
                return "{\r\n"
                    + "    \"error_type\": \"communication\",\r\n"
                    + "    \"error\": \"Failed : HTTP error code :" + conn.getResponseCode() + "\",\r\n"
                    + "    \"status\": " + conn.getResponseCode() + "\r\n"
                    + "}";
            }

            br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));


            StringBuilder jsonStringBuilder = new StringBuilder();
            String output = br.readLine();
            while (output != null) {
                jsonStringBuilder.append(output);
                output = br.readLine();
            }
            jsonString = jsonStringBuilder.toString();
        } catch (java.net.ConnectException | BindException | SocketTimeoutException | java.net.UnknownHostException e) {
            logger.error("Connection error {} to {}", e, endpoint, e );
            // Return json error format - application will continue trying in
            // case connection comes up
            return "{\r\n"
                + "    \"error_type\": \"" + e.getClass().getName() + "\",\r\n"
                + "    \"error\": \"" + jsonEscape(e.toString()) + "\",\r\n"
                + "    \"status\": -1\r\n" + "}";
        } catch (IOException e) {
            if (e.getMessage().contains("Unable to tunnel through proxy")) {
                return "{\r\n"
                    + "    \"error_type\": \"" + e.getClass().getName() + "\",\r\n"
                    + "    \"error\": \"" + jsonEscape(e.toString()) + "\",\r\n"
                    + "    \"status\": -1\r\n" + "}";
            }
            //else
            logger.error("Error communicating with A-MQ Jolokia", e);
            System.exit(1);
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (RuntimeException | IOException e) {
                logger.warn("Resource closure exception for os", e);
                // Ignore exceptions in resource closure attempts
            }
            try {
                if (br != null) {
                    br.close();
                }
            } catch (RuntimeException | IOException e) {
                logger.warn("Resource closure exception for br", e);
                // Ignore exceptions in resource closure attempts
            }
            try {
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (RuntimeException e) {
                logger.warn("Resource closure exception for conn", e);
                // Ignore exceptions in resource closure attempts
            }
        }
        return jsonString;
    }

    private String jsonEscape(String raw) {
        return raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    public String getBrokerName() {
        return this.brokerName;
    }

    public void resetConsumerStatistics(String destinationName, String consumerId, String clientId) {
        JolokiaRequestBuilder resetBuilder = requestBuilder.copy();
        String input = resetBuilder.filter(destinationName).consumer(consumerId).clientId(clientId).buildResetConsumerStatisticsRequest();
        long timeStartNanos = System.nanoTime();
        httpRequest(input);
        responseTimeMillis = (System.nanoTime() - timeStartNanos) / 1000000;
    }
}
