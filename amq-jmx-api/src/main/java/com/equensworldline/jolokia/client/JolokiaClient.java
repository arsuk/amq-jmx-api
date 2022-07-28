package com.equensworldline.jolokia.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONObject;
import com.equensworldline.jolokia.client.gui.DisplayData;

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

import static com.equensworldline.jolokia.client.DataType.STATUS;
import static com.equensworldline.jolokia.client.DataType.TEST;

public class JolokiaClient {
    private static final Logger logger = LoggerFactory.getLogger(JolokiaClient.class);

    private URL endpoint;
    private String basicAuth;
    private JolokiaRequestBuilder requestBuilder;
    private String trace;
    private String brokerName;
    private long responseTimeMillis = 0;

    public JolokiaClient(URL endpoint, JolokiaRequestBuilder requestBuilder, String trace) {
        this.endpoint = endpoint;
        this.requestBuilder = requestBuilder;
        this.trace = trace;
        ByteBuffer credentials = ByteBuffer.allocate(1024)
            .put(MyArgs.env("ACTIVEMQ_USER", DisplayData.userDefault).getBytes(StandardCharsets.UTF_8))
            .put(":".getBytes(StandardCharsets.UTF_8))
            .put(MyArgs.env("ACTIVEMQ_PASSWORD", DisplayData.passwordDefault).getBytes(StandardCharsets.UTF_8));
        this.basicAuth = Base64.getEncoder().encodeToString(Arrays.copyOf(credentials.array(), credentials.position()));
        logger.debug("Setting crededtials to {}", basicAuth);
        determineBroker();
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
                logger.error("Unavailable broker in endpointlist: " + endpoint + " HTTP status was " + result);
            }
            MyJSONObject tvalue = (MyJSONObject) json.get("value");
            if (tvalue!=null&&tvalue.hasNext()) {
                String key = (String) tvalue.next();
                MyJSONObject value = (MyJSONObject) tvalue.get(key);
                this.brokerName = value.getString("BrokerName");
                this.requestBuilder.broker(brokerName);
            }
        } catch (Exception e) {
            logger.error("Query error - unexpected response", e);
            logger.error("Response was: " + jsonString);
        }
        if (trace != null) {
            logger.info("Broker name determination succesful, query response time " + responseTimeMillis + " ms");
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
        } catch (Exception e) {
            logger.error("Query error - unexpected response", e);
            logger.error("Response was: " + jsonString);
            System.exit(1);
        }

        if (trace != null || requestBuilder.getDataType() == TEST) {
            logger.info("Input: " + input);
            logger.info("Resulted in:");
            logger.info(json.toString(4)); // Print it with specified indentation
            logger.info("Query time " + responseTimeMillis + " ms");
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
            conn.setRequestProperty("Authorization", "Basic " + basicAuth);
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
                    + "    \"status\": " + conn.getResponseCode() + "\r\n" + "}";
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

            logger.error("Connection error "+e+" to {} :", endpoint.toString());
            // Return json error format - application will continue trying in
            // case connection comes up
            return "{\r\n"
                + "    \"error_type\": \"" + e.getClass().getName() + "\",\r\n"
                + "    \"error\": \"" + e + "\",\r\n"
                + "    \"status\": -1\r\n" + "}";
        } catch (IOException e) {
            logger.error("Error communicating with A-MQ Jolokia", e);
            System.exit(1);
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
            } catch (Exception e) {
                // Ignore exceptions in resource closure attempts
            }
            try {
                if (br != null) {
                    br.close();
                }
            } catch (Exception e) {
                // Ignore exceptions in resource closure attempts
            }
            try {
                if (conn != null) {
                    conn.disconnect();
                }
            } catch (Exception e) {
                // Ignore exceptions in resource closure attempts
            }
        }
        return jsonString;
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
