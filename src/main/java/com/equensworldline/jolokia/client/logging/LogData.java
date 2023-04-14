package com.equensworldline.jolokia.client.logging;

import com.equensworldline.amq.utils.JMXApiException;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;
import com.equensworldline.jolokia.client.DataType;
import com.equensworldline.jolokia.client.JolokiaClient;
import com.equensworldline.jolokia.client.JolokiaRequestBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class LogData implements Runnable {

    private static final String JSON_HTTP_RESULT_CODE = "status";
    private static final Logger logger = LoggerFactory.getLogger(LogData.class);
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static boolean multiThreaded = false;
    private static int sleepSecs;
    private static DataType query;
    private static String queueName;
    private static String topicName;
    private static String queryFilter;
    private static String trace;
    private static String delChar;
    private final JolokiaClient jolokiaClient;
    private final String filename;
    private boolean showStatusBool = true;

    private static final String ENDPOINT_DEFAULT = "http://localhost:8161/api/jolokia/";
    private static final String SLEEP_SECS_DEFAULT = "60";
    private static final String USER_DEFAULT = "admin";
    private static final String PASSWORD_DEFAULT = "admin";

    public LogData(String endpoint, String baseFilename) {
        try {
            JolokiaRequestBuilder requestBuilder = new JolokiaRequestBuilder()
                .dataType(query)
                .filter(queryFilter)
                .topic(topicName)
                .queue(queueName)
                .broker("*");
            this.jolokiaClient = new JolokiaClient(new URL(endpoint), requestBuilder, trace);
            String broker = jolokiaClient.getBrokerName();
            if (multiThreaded) {
                if (baseFilename.contains("/")) {
                    File baseFile = new File(baseFilename);
                    String bareFilename = baseFile.getName();
                    File path = baseFile.getParentFile();
                    if (path == null) {
                        throw new IllegalStateException("Must have a parent path for the file if there is a / in the name");
                    }
                    this.filename = new File(path, broker + "-" + bareFilename).getPath();
                } else {
                    this.filename = broker + "-" + baseFilename;
                }
            } else {
                this.filename = baseFilename;
            }
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid endpoint configuration: " + endpoint + " is not a valid URL", e);
        }
    }

    // Post request and log activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // Host is the url of the API - this is
        // http://localhost:8181/hawtio/jolokia/ for ActiveMQ 5.11 and below
        String endpoint = MyArgs.env("ACTIVEMQ_HOST", ENDPOINT_DEFAULT);

        String[] endpoints = endpoint.split(",");
        multiThreaded = endpoints.length > 1;
        query = DataType.fromAlias(MyArgs.arg(args, 0, DataType.STATUS.alias()));

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", "60"));

        queueName = MyArgs.arg(args, "-queue", "");
        topicName = MyArgs.arg(args, "-topic", "");
        queryFilter = MyArgs.arg(args, "-filter", "");
        trace = MyArgs.arg(args, "-trace", null);

        String basefilename = MyArgs.arg(args, "-file", "");
        if ("".equals(basefilename)) {
            basefilename = query + "-" + DateTimeFormatter.ofPattern("yyMMdd-HHmmss").format(OffsetDateTime.now()) + ".csv";
        }

        delChar = MyArgs.env("DELIMITER", "\t");

        boolean help = MyArgs.arg(args, "-help");
        if (!help) {
            help = MyArgs.arg(args, "-h");
        }
        if (!help) {
            help = MyArgs.arg(args, "-?");
        }
        if (help) {
            printUsage();
        }

        for (int i = 0; i < endpoints.length; i++) {
            Thread t = new Thread(new LogData(endpoints[i], basefilename)); // Start threads
            // sharing
            // connection
            t.start();
        }

    }

    @SuppressWarnings("squid:S106") // CLI usage is proper to System.out
    private static void printUsage() {
        System.out
            .println("usage: java -jar amq-jmx-api-x.x.x-shaded.jar [DisplayData|LogData] <query> [-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filtertext>] [-file <filename>]");
        System.out
            .println("Queries are: status,connectors,connections,queues,topics,destination,consumers,memory,threads,storage");
        System.out.println("For destination and consumers you must specify a queue or topic");
        System.out.println("The optional file parameter is only used when running for LogData");
        System.out.print("Querie types are: ");
        List<DataType> queries = new ArrayList<>(Arrays.asList(DataType.values()));
        queries.remove(DataType.TEST);
        for (Object q : queries.toArray()) {
            System.out.print(q + " ");
        }
        System.out.println();
        System.out.println("ACTIVEMQ_HOST=" + ENDPOINT_DEFAULT);
        System.out.println("REFRESH_TIME=" + SLEEP_SECS_DEFAULT);
        System.out.println("DELIMITER='\\t' (TAB)");
        System.out.println("ACTIVEMQ_USER=" + USER_DEFAULT);
        System.out.println("ACTIVEMQ_PASSWORD=" + PASSWORD_DEFAULT);

        System.exit(0);
    }

    private static String getKeyElement(String key, String subKey) {
        String[] elems = key.split(subKey + "=");
        if (elems.length > 1) {
            elems = elems[1].split(",");
        }
        return elems[0];
    }

    public void run() {

        try (PrintStream ps = new PrintStream(filename)) {

            writeColumnHeader(ps);

            boolean continueLoop = true;
            while (continueLoop) {

                MyJSONObject json = jolokiaClient.query();
                long respTimeMillis = jolokiaClient.getResponseTimeMillis();

                renderResponse(ps, json, respTimeMillis);
                ps.flush();

                try {
                    Thread.sleep(sleepSecs * 1000L);
                } catch (InterruptedException e) {
                    logger.error("Unexpected interruption");
                    continueLoop=false;
                    Thread.currentThread().interrupt();
                }
            }

        } catch (FileNotFoundException e) {
            logger.error("Could not open {} exception {}", filename, e);
            System.exit(1);
        }
    }

    private void renderResponse(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        if (json.getInteger(JSON_HTTP_RESULT_CODE) != 200) {
            renderResponseFailure(json);
            showStatusBool = true;
        } else {
            if (showStatusBool) {
                logger.info("Query success {}", query);
                // since we're reconnected after downtime the brokerName may have been reconfigured
                // don't store responsetime for this administrative request
                jolokiaClient.determineBroker();
                if (logger.isInfoEnabled()) {
                    logger.info("Active broker name is {} ", jolokiaClient.getBrokerName());
                }
            }
            showStatusBool = false;

            switch (query) {

            case STATUS:
                renderStatusFields(ps, json, respTimeMillis);
                break;
            case QUEUES:
            case TOPICS:
            case DESTINATION:
                renderQueuesOrTopicsFields(ps, json, respTimeMillis);
                break;
            case CONSUMERS:
                renderConsumersFields(ps, json, respTimeMillis);
                break;
            case CONNECTIONS:
                renderConnectionsFields(ps, json, respTimeMillis);
                break;
            case CONNECTORS:
                renderConnectorsFields(ps, json, respTimeMillis);
                break;
            case MEMORY:
                renderMemoryFields(ps, json, respTimeMillis);
                break;
            case STORAGE:
                renderStorageFields(ps, json, respTimeMillis);
                break;
            case THREADS:
                renderThreadsFields(ps, json, respTimeMillis);
                break;
            default:
                throw new JMXApiException("Internal error");
            }
        }
    }

    private void renderResponseFailure(MyJSONObject json) {
        switch (query) {
        case CONNECTIONS:
        case QUEUES:
        case TOPICS:
        case DESTINATION:
            if (json.getInteger(JSON_HTTP_RESULT_CODE) == 404) {
                logger.info("No {} to log!", query);
            } else {
                logger.error("Query failed; error {}; status {}",
                    json.getString("error"),
                    json.getString(JSON_HTTP_RESULT_CODE));
            }
            break;
        default:
            logger.error("Query failed; error {}; status {}",
                json.getString("error"),
                json.getString(JSON_HTTP_RESULT_CODE));
            break;
        }
    }

    private void renderConnectionsHeader(PrintStream ps) {
        writeField(ps, "Name", false);
        writeField(ps, "BrokerName", false);
        writeField(ps, "RemoteAddress", false);
        writeField(ps, "UserName", false);
        writeField(ps, "DispatchQueueSize", false);
        writeField(ps, "ActiveTransactionCount", false);
        writeField(ps, "ClientId", false);
        writeField(ps, "Consumers", false);
        writeField(ps, "Producers", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderConnectionsFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject value = (MyJSONObject) json.get("value");

        Set<String> keySet = value.keySet();
        for (String key : keySet) {
            String name = getKeyElement(key, "connectorName");

            MyJSONObject convalue = (MyJSONObject) value.get(key);
            Object caObj = convalue.get("Consumers");
            MyJSONArray ca = caObj == null ? new MyJSONArray() : (MyJSONArray) caObj;
            Object paObj = convalue.get("Producers");
            MyJSONArray pa = paObj == null ? new MyJSONArray() : (MyJSONArray) paObj;
            writeField(ps, name, false);
            writeField(ps, jolokiaClient.getBrokerName(), false);
            writeField(ps, convalue.getString("RemoteAddress"), false);
            writeField(ps, convalue.getString("UserName"), false);
            writeField(ps, convalue.getString("DispatchQueueSize"), false);
            writeField(ps, convalue.getString("ActiveTransactionCount"), false);
            writeField(ps, convalue.getString("ClientId"), false);
            writeField(ps, Integer.toString(ca.length()), false);
            writeField(ps, Integer.toString(pa.length()), false);
            writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        }
    }

    private void renderConnectorsHeaders(PrintStream ps) {
        writeField(ps, "Name", false);
        writeField(ps, "BrokerName", false);
        writeField(ps, "Parameters", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderConnectorsFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        while (tvalue.hasNext()) {
            String tkey = tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(tkey);
            MyJSONObject connectors = (MyJSONObject) value.get("TransportConnectors");

            Set<String> keySet = connectors.keySet();
            for (String key : keySet) {
                // TODO: move this filter to the query-level
                if (key.contains(queryFilter)) {
                    writeField(ps, key, false);
                    writeField(ps, jolokiaClient.getBrokerName(), false);
                    writeField(ps, "\"" + connectors.getString(key) + "\"", false);
                    writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
                    writeField(ps, Long.toString(respTimeMillis), true);
                }
            }
        }
    }

    private void renderConsumersHeader(PrintStream ps) {
        writeField(ps, "ConsumerId", false);
        writeField(ps, "BrokerName", false);
        writeField(ps, "ClientId", false);
        writeField(ps, "ConnectionId", false);
        writeField(ps, "DesitinationName", false);
        writeField(ps, "SessionId", false);
        writeField(ps, "Network", false);
        writeField(ps, "EnqueueCounter", false);
        writeField(ps, "DequeueCounter", false);
        writeField(ps, "DispatchedQueueSize", false);
        writeField(ps, "DispatchedCounter", false);
        writeField(ps, "PendingQueueSize", false);
        writeField(ps, "PrefetchSize", false);
        writeField(ps, "ConsumedCount", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderConsumersFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        while (tvalue.hasNext()) {
            String key = tvalue.next();
            String name = getKeyElement(key, "consumerId");
            MyJSONObject value = (MyJSONObject) tvalue.get(key);
            value.setFloatFormat("%1.0f");

            writeField(ps, name, false);
            writeField(ps, jolokiaClient.getBrokerName(), false);
            writeField(ps, value.getString("ClientId"), false);
            writeField(ps, value.getString("ConnectionId"), false);
            writeField(ps, value.getString("DestinationName"), false);
            writeField(ps, value.getString("SessionId"), false);
            writeField(ps, value.getString("Network"), false);
            writeField(ps, value.getString("EnqueueCounter"), false);
            writeField(ps, value.getString("DequeueCounter"), false);
            writeField(ps, value.getString("DispatchedQueueSize"), false);
            writeField(ps, value.getString("DispatchedCounter"), false);
            writeField(ps, value.getString("PendingQueueSize"), false);
            writeField(ps, value.getString("PrefetchSize"), false);
            writeField(ps, value.getString("ConsumedCount"), false);
            writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        }
    }

    private void renderMemoryHeaders(PrintStream ps) {
        writeField(ps, "HeapMemory init", false);
        writeField(ps, "HeapMemory committed", false);
        writeField(ps, "HeapMemory max", false);
        writeField(ps, "HeapMemory used", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderMemoryFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject value = (MyJSONObject) json.get("value");
        writeField(ps, value.getString("init"), false);
        writeField(ps, value.getString("committed"), false);
        writeField(ps, value.getString("max"), false);
        writeField(ps, value.getString("used"), false);
        writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
        writeField(ps, Long.toString(respTimeMillis), true);
    }

    private void renderQueuesOrTopicsHeader(PrintStream ps) {
        writeField(ps, "Name", false);
        writeField(ps, "BrokerName", false);
        writeField(ps, "AverageEnqueueTime", false);
        writeField(ps, "MinEnqueueTime", false);
        writeField(ps, "MaxEnqueueTime", false);
        writeField(ps, "AverageBlockedTime", false);
        writeField(ps, "ConsumerCount", false);
        writeField(ps, "ProducerCount", false);
        writeField(ps, "DequeueCount", false);
        writeField(ps, "EnqueueCount", false);
        writeField(ps, "ForwardCount", false);
        writeField(ps, "ExpiredCount", false);
        writeField(ps, "InFlightCount", false);
        writeField(ps, "ProducerFlowControl", false);
        writeField(ps, "BlockedSends", false);
        writeField(ps, "MaxMessageSize", false);
        writeField(ps, "MemoryLimit", false);
        writeField(ps, "MemoryPercentUsage", false);
        writeField(ps, "QueueSize", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderQueuesOrTopicsFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");

        while (tvalue.hasNext()) {
            String queueKey = tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(queueKey);
            logger.debug("Value returned {}", value);
            value.setFloatFormat("%1.0f");
            writeField(ps, value.getString("Name"), false);
            writeField(ps, jolokiaClient.getBrokerName(), false);
            writeField(ps, value.getString("AverageEnqueueTime"), false);
            writeField(ps, value.getString("MinEnqueueTime"), false);
            writeField(ps, value.getString("MaxEnqueueTime"), false);
            writeField(ps, value.getString("AverageBlockedTime"), false);
            writeField(ps, value.getString("ConsumerCount"), false);
            writeField(ps, value.getString("ProducerCount"), false);
            writeField(ps, value.getString("DequeueCount"), false);
            writeField(ps, value.getString("EnqueueCount"), false);
            writeField(ps, value.getString("ForwardCount"), false);
            writeField(ps, value.getString("ExpiredCount"), false);
            writeField(ps, value.getString("InFlightCount"), false);
            writeField(ps, value.getString("ProducerFlowControl"), false);
            writeField(ps, value.getString("BlockedSends"), false);
            writeField(ps, value.getString("MaxMessageSize"), false);
            writeField(ps, value.getString("MemoryLimit"), false);
            writeField(ps, value.getString("MemoryPercentUsage"), false);
            writeField(ps, value.getString("QueueSize"), false);
            writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        }
    }

    private void renderStatusHeader(PrintStream ps) {
        writeField(ps, "BrokerName", false);
        writeField(ps, "BrokerVersion", false);
        writeField(ps, "Uptime", false);
        writeField(ps, "MemoryLimit", false);
        writeField(ps, "MemoryPercentUsage", false);
        writeField(ps, "StorePercentUsage", false);
        writeField(ps, "TempLimit", false);
        writeField(ps, "TempPercentUsage", false);
        writeField(ps, "CurrentConnections", false);
        writeField(ps, "TotalConnections", false);
        writeField(ps, "TotalConsumers", false);
        writeField(ps, "TotalProducers", false);
        writeField(ps, "TotalEnqueues", false);
        writeField(ps, "TotalDequeues", false);
        writeField(ps, "TotalMessages", false);
        writeField(ps, "MaxMessageSize", false);
        writeField(ps, "AvgMessageSize", false);
        writeField(ps, "MinMessageSize", false);
        writeField(ps, "Persistent", false);
        writeField(ps, "Slave", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderStatusFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");

        while (tvalue.hasNext()) {
            String key = tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(key);
            value.setFloatFormat("%1.0f");
            writeField(ps, value.getString("BrokerName"), false);
            writeField(ps, value.getString("BrokerVersion"), false);
            writeField(ps, value.getString("Uptime"), false);
            writeField(ps, value.getString("MemoryLimit"), false);
            writeField(ps, value.getString("MemoryPercentUsage"), false);
            writeField(ps, value.getString("StoreLimit"), false);
            writeField(ps, value.getString("StorePercentUsage"), false);
            writeField(ps, value.getString("TempLimit"), false);
            writeField(ps, value.getString("TempPercentUsage"), false);
            writeField(ps, value.getString("CurrentConnectionsCount"), false);
            writeField(ps, value.getString("TotalConnectionsCount"), false);
            writeField(ps, value.getString("TotalConsumerCount"), false);
            writeField(ps, value.getString("TotalProducerCount"), false);
            writeField(ps, value.getString("TotalEnqueueCount"), false);
            writeField(ps, value.getString("TotalDequeueCount"), false);
            writeField(ps, value.getString("TotalMessageCount"), false);
            writeField(ps, value.getString("MaxMessageSize"), false);
            writeField(ps, value.getString("AverageMessageSize"), false);
            writeField(ps, value.getString("MinMessageSize"), false);
            writeField(ps, value.getString("Persistent"), false);
            writeField(ps, value.getString("Slave"), false);
            writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        }
    }

    private void renderStorageHeader(PrintStream ps) {
        writeField(ps, "Name", false);
        writeField(ps, "broker", false);
        writeField(ps, "Size", false);
        writeField(ps, "Transactions", false);
        writeField(ps, "Data", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderStorageFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");

        while (tvalue.hasNext()) {
            String key = tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(key);
            writeField(ps, "\"" + value.getString("Name") + "\"", false);
            writeField(ps, jolokiaClient.getBrokerName(), false);
            writeField(ps, value.getString("Size"), false);
            writeField(ps, "\"" + value.getString("Transactions") + "\"", false);
            writeField(ps, "\"" + value.getString("Data") + "\"", false);
            writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        }
    }

    private void renderThreadsHeader(PrintStream ps) {
        writeField(ps, "DaemonThreadCount", false);
        writeField(ps, "PeakThreadCount", false);
        writeField(ps, "ThreadCount", false);
        writeField(ps, "TotalStartedThreadCount", false);
        writeField(ps, "Time Stamp", false);
        writeField(ps, "Query time", true);
    }

    private void renderThreadsFields(PrintStream ps, MyJSONObject json, long respTimeMillis) {
        MyJSONObject value = (MyJSONObject) json.get("value");
        writeField(ps, value.getString("DaemonThreadCount"), false);
        writeField(ps, value.getString("PeakThreadCount"), false);
        writeField(ps, value.getString("ThreadCount"), false);
        writeField(ps, value.getString("TotalStartedThreadCount"), false);
        writeField(ps, TIMESTAMP_FORMATTER.format(OffsetDateTime.ofInstant(Instant.ofEpochSecond((long) json.getInteger("timestamp")), ZoneId.of("UTC"))), false);
        writeField(ps, Long.toString(respTimeMillis), true);
    }

    private void writeField(PrintStream output, String fieldValue, boolean lastField) {
        if (lastField) {
            output.println(fieldValue);
        } else {
            output.print(fieldValue);
            output.print(delChar);
        }
    }

    private void writeColumnHeader(PrintStream ps) {

        switch (query) {

        case STATUS:
            renderStatusHeader(ps);
            break;
        case QUEUES:
        case TOPICS:
        case DESTINATION:
            renderQueuesOrTopicsHeader(ps);
            break;
        case CONSUMERS:
            renderConsumersHeader(ps);
            break;
        case CONNECTORS:
            renderConnectorsHeaders(ps);
            break;
        case CONNECTIONS:
            renderConnectionsHeader(ps);
            break;
        case MEMORY:
            renderMemoryHeaders(ps);
            break;
        case STORAGE:
            renderStorageHeader(ps);
            break;
        case THREADS:
            renderThreadsHeader(ps);
            break;
        case TEST:
            writeField(ps, "Test-output", true);
            // fall-through intentional - TEST request should run only once for debugging
        default:
            logger.error("Unknown query {}", query);
            System.exit(1);
            break;
        }
    }


}
