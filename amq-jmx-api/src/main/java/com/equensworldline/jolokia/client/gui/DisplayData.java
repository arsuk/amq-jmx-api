package com.equensworldline.jolokia.client.gui;

import com.equensworldline.jolokia.client.DataType;
import com.equensworldline.jolokia.client.JolokiaClient;
import com.equensworldline.jolokia.client.JolokiaRequestBuilder;
import com.equensworldline.jolokia.client.Main;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.gui.ShowTable;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;

import javax.swing.*;
import javax.xml.crypto.Data;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;

public class DisplayData implements Runnable {

    private static final String JSON_HTTP_RESULT_CODE = "status";
    private static final Logger logger = LoggerFactory.getLogger(DisplayData.class);
    private static Thread[] thread;
    private static DisplayData[] instances;
    private static int sleepSecs;
    private static DataType query;
    private static String queueName;
    private static String topicName;
    private static String queryFilter;
    private static String consumerFilter;
    private static String trace;
    private final String endpoint;
    private final JolokiaClient jolokiaClient;
    private final JolokiaRequestBuilder requestBuilder;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private int[] lastEnqueueCounts;
    private int[] lastDequeueCounts;
    private int[] lastStoreSizes;
    private ShowTable showTable;
    private String title;
    private long respTimeMillis;
    private String host;
    private boolean resetStatistics = false;
    private boolean purgeDestination = false;
    private boolean showStatusBool = true;
    
    // props values
    static boolean purgeAllowed=true;
    static boolean resetAllowed=true;
    
    static final String endpointDefault="http://localhost:8161/api/jolokia/";
    static final String sleepSecsDefault="10";
    public static final String userDefault="admin";	// Used by Jolokia client
    public static final String passwordDefault="admin"; // Used by Jolokia client

    public DisplayData(String endpoint) {
        try {
            this.requestBuilder = new JolokiaRequestBuilder()
                .dataType(query)
                .filter(queryFilter)
                .topic(topicName)
                .queue(queueName)
                .broker("*");
            this.endpoint = endpoint;
            this.jolokiaClient = new JolokiaClient(new URL(endpoint), requestBuilder, trace);
            this.host = jolokiaClient.getBrokerName();
            this.respTimeMillis = jolokiaClient.getResponseTimeMillis();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid endpoint configuration: " + endpoint + " is not a valid URL", e);
        }
    }

    // Post request and display activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // Host is the url of the API - this is http://localhost:8181/hawtio/jolokia/ for ActiveMQ 5.11 and below
        String endpoint = MyArgs.env("ACTIVEMQ_HOST", endpointDefault);

        String endpoints[] = endpoint.split(",");

        query = DataType.fromAlias(MyArgs.arg(args, 0, DataType.STATUS.alias()));

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", sleepSecsDefault));

        queueName = MyArgs.arg(args, "-queue", "");
        topicName = MyArgs.arg(args, "-topic", "");
        queryFilter = MyArgs.arg(args, "-filter", "");
        consumerFilter = MyArgs.arg(args, "-consumerfilter", "");
        trace = MyArgs.arg(args, "-trace", null);

        boolean help = MyArgs.arg(args, "-help");
        if (!help) help = MyArgs.arg(args, "-h");
        if (!help) help = MyArgs.arg(args, "-?");
        if (help) {
            printUsage();
        }
        
        Properties properties = new Properties();
        try {
            properties.load(DisplayData.class.getResourceAsStream(File.separator + "prop.properties"));
            String purgeStr=properties.getProperty("purgeAllowed", "yes");
            purgeAllowed=purgeStr.startsWith("y")||purgeStr.startsWith("t");
            String resetStr=properties.getProperty("resetAllowed", "yes");
            resetAllowed=resetStr.startsWith("y")||resetStr.startsWith("t");
        } catch (Exception e) {
            // ignore errors
        }

        thread = new Thread[endpoints.length];
        instances = new DisplayData[endpoints.length];
        // Create a thread and task for each host url provided
        for (int i = 0; i < endpoints.length; i++) {
            instances[i] = new DisplayData(endpoints[i]);
            thread[i] = new Thread(instances[i]);
            thread[i].start();
        }

    }

    private static void printUsage() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println("DisplayData version "+version);
        System.out
            .println("usage: java -jar amq-jmx-api-1.1.2-shaded.jar [DisplayData|LogData] <query> [-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filertext>]");
        System.out.println("Queries are: status,connectors,connections,queues,topics,destination,consumers,memory,threads,storage");
        System.out.println("For destination and consumers you must specify a queue or topic");
        System.out.print("Querie types are: ");
        List<DataType> queries = new ArrayList<>(Arrays.asList(DataType.values()));
        queries.remove(DataType.TEST);
        for (Object q: queries.toArray()) {
        	System.out.print(q+" ");
        }
        System.out.println();
        System.out.println("ACTIVEMQ_HOST="+endpointDefault);
        System.out.println("REFRESH_TIME="+sleepSecsDefault);
        System.out.println("ACTIVEMQ_USER="+userDefault);
        System.out.println("ACTIVEMQ_PASSWORD="+passwordDefault);
        System.exit(0);
    }

    private static String getKeyElement(String key, String subKey) {
        String elems[] = key.split(subKey + "=");
        if (elems.length > 1) {
            elems = elems[1].split(",");
        }
        return elems[0];
    }

    private void constructTitle() {
        title = String.format("ActiveMQ Data %s (%s) %s", host, endpoint, query.alias());
    }

    public void run() {

        constructTitle();

        showTable = new ShowTable(title, new String[]{""}, new String[][]{{""}});

        showTable.add(createMenuBar(), BorderLayout.NORTH);

        lastEnqueueCounts = null;
        lastDequeueCounts = null;

        long nextSleepSecs = sleepSecs;
        while (true) {

            MyJSONObject json = jolokiaClient.query();
            respTimeMillis = jolokiaClient.getResponseTimeMillis();

            if (json.getInteger(JSON_HTTP_RESULT_CODE) != 200) {
                String statusStr = json.getString(JSON_HTTP_RESULT_CODE);
                switch (query) {
                case CONNECTIONS:
                case DESTINATION:
                    if (json.getInteger(JSON_HTTP_RESULT_CODE) == 404) {
                        statusStr = "No " + query.alias() + " found";
                    } else {
                        logger.error("Query failed, status " + statusStr);
                    }
                    break;
                default:
                    logger.error("Query failed, status " + statusStr);
                    break;
                }

                showStatusBool = true;
                String cols[] = {"Error", "status"};
                String data[][] = {{json.getString("error"), statusStr}};
                updateTableDisplay(cols, data);
                if (query == DataType.TEST) {
                    System.exit(1);
                }
            } else {
                if (showStatusBool) {
                    logger.info("Query success " + query.alias());
                    // since we're reconnected after downtime the brokerName may have been reconfigured;
                    // don't store responsetime for this administrative request
                    jolokiaClient.determineBroker();
                    this.host = jolokiaClient.getBrokerName();
                    this.constructTitle();
                }
                showStatusBool = false;
                nextSleepSecs = renderResult(nextSleepSecs, json);
            }

            try {
                Thread.sleep(nextSleepSecs * 1000);
            } catch (Exception e) {
                // Interrupted while sleeping, continue with main logic
            }

        }

    }

    private long renderResult(long nextSleepSecs, MyJSONObject json) {
        long resultingNextSleepSecs = nextSleepSecs;
        try {
            switch (query) {
            case STATUS:
                // Status display may reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderStatus(json);
                break;
            case QUEUES:
            case TOPICS:
                // Queues and Topics display may purge or reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderQueueOrTopic(json);
                break;
            case DESTINATION:
                // Destinations (Queue(s) or Topic(s)) display may purge or reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderDestination(json);
                break;
            case CONSUMERS:
                renderConsumers(json);
                break;
            case CONNECTIONS:
                renderConnections(json);
                break;
            case CONNECTORS:
                renderConnectors(json);
                break;
            case MEMORY:
                renderMemory(json);
                break;
            case THREADS:
                renderThreads(json);
                break;
            case STORAGE:
                renderStorage(json);
                break;
            default:
                logger.warn("Should not be here");
                throw new RuntimeException("Internal error");
            }
        } catch (RuntimeException e) {
            logger.error("Exception while parsing and rendering response",e);
            logger.error("Response received was >>>\n{}\n<<< end-of-response",json.toString(2));
        }
        return resultingNextSleepSecs;
    }

    private void renderStorage(MyJSONObject json) {
        String cols[] = {
            "Name", "Broker", "Total size", "Size delta", "Active TxnIds", "Current journal-sequences"};

        ArrayList<String[]> al = new ArrayList();

        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        int rowcnt = 0;

        while (tvalue.hasNext()) {
            String queueKey = (String) tvalue.next();
            String broker = getKeyElement(queueKey, "brokerName");
            MyJSONObject value = (MyJSONObject) tvalue.get(queueKey);
            value.setDecimalFormat("%,d");

            int storageDelta = 0;
            if (lastStoreSizes == null || lastStoreSizes.length != tvalue.length()) {
                lastStoreSizes = new int[tvalue.length()];
            } else {
                int c = value.getInteger("Size");
                storageDelta = c - lastStoreSizes[rowcnt];
                lastStoreSizes[rowcnt] = c;
            }

            String tdata[] = {
                value.getString("Name"), broker, value.getString("Size"),
                Integer.toString(storageDelta), value.getString("Transactions"),
                value.getString("Data")};
            al.add(tdata);

            rowcnt++;
        }
        String[][] data = new String[al.size()][];
        for (int i = 0; i < al.size(); i++) {
            data[i] = al.get(i);
        }
        updateTableDisplay(cols, data);
    }

    private void renderThreads(MyJSONObject json) {
        MyJSONObject value = (MyJSONObject) json.get("value");
        value.setDecimalFormat("%,d");

        String cols[] = {"Attribute", "Value"};
        String data[][] = {
            {"DaemonThreadCount", value.getString("DaemonThreadCount")},
            {"PeakThreadCount", value.getString("PeakThreadCount")},
            {"ThreadCount", value.getString("ThreadCount")},
            {"TotalStartedThreadCount", value.getString("TotalStartedThreadCount")},
            {"Time Stamp", timeFormat.format(new Date((long) json.getInteger("timestamp") * 1000))},
            {"Query time", respTimeMillis + " ms"}};
        updateTableDisplay(cols, data);
    }

    private void renderMemory(MyJSONObject json) {
        MyJSONObject value = (MyJSONObject) json.get("value");
        value.setDecimalFormat("%,d");

        String cols[] = {"Attribute", "Value"};
        String data[][] = {
            {"HeapMemoryUsage init", value.getString("init")},
            {"HeapMemoryUsage committed", value.getString("committed")},
            {"HeapMemoryUsage max", value.getString("max")},
            {"HeapMemoryUsage used", value.getString("used")},
            {"Time Stamp", timeFormat.format(new Date((long) json.getInteger("timestamp") * 1000))},
            {"Query time", respTimeMillis + " ms"}};
        updateTableDisplay(cols, data);
    }

    private void renderConnectors(MyJSONObject json) {
        String cols[] = {"Name", "BrokerName", "Parameters"};

        MyJSONObject value = (MyJSONObject) json.get("value");

        Set<String> keySet = value.keySet();
        ArrayList<String[]> al = new ArrayList();
        // Loop for multiple brokers
        for (String key : keySet) {
            MyJSONObject bvalue = (MyJSONObject) value.get(key);
            MyJSONObject cobj = (MyJSONObject) bvalue.get("TransportConnectors");
            // Loop for connectors
            while (cobj.hasNext()) {
                String name = (String) cobj.next();
                // TODO: move this filter to the query-level
                if (name.contains(queryFilter)) {
                    String tdata[] = {name, getKeyElement(key, "brokerName"), cobj.getString(name)};
                    al.add(tdata);
                }
            }
        }
        String[][] data = new String[al.size()][];
        for (int i = 0; i < al.size(); i++) {
            data[i] = al.get(i);
        }
        updateTableDisplay(cols, data);
    }

    private void renderConnections(MyJSONObject json) {
        String cols[] = {
            "Name", "BrokerName", "RemoteAddress", "UserName", "DispatchQueueSize",
            "ActiveTransactionCount", "ClientId", "Consumers", "Producers"};
        MyJSONObject value = (MyJSONObject) json.get("value");

        Set<String> keySet = value.keySet();
        ArrayList<String[]> al = new ArrayList();
        for (String key : keySet) {
            String name = getKeyElement(key, "connectorName");
            String broker = getKeyElement(key, "brokerName");

            MyJSONObject convalue = (MyJSONObject) value.get(key);
            Object caObj = convalue.get("Consumers");
            MyJSONArray ca = caObj == null ? new MyJSONArray() : (MyJSONArray) caObj;
            Object paObj = convalue.get("Producers");
            MyJSONArray pa = paObj == null ? new MyJSONArray() : (MyJSONArray) paObj;

            String tdata[] = {
                name, broker, convalue.getString("RemoteAddress"), convalue.getString("UserName"),
                convalue.getString("DispatchQueueSize"), convalue.getString("ActiveTransactionCount"),
                convalue.getString("ClientId"), Integer.valueOf(ca.length()).toString(),
                Integer.valueOf(pa.length()).toString()};
            al.add(tdata);
        }
        String[][] data = new String[al.size()][];
        for (int i = 0; i < al.size(); i++) {
            data[i] = al.get(i);
        }
        updateTableDisplay(cols, data);
    }

    private void updateTableDisplay(String[] cols, String[][] data) {
        showTable.updateTitle(title + ", items=" + data.length + ", resp. time " + respTimeMillis + "ms");
        showTable.updateTable(cols, data);
    }

    // array loops are needed as we transform 2 flat arrays into a 2-dimensional array
    @SuppressWarnings("PMD.AvoidArrayLoops")
    private long renderDestination(MyJSONObject json) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        if ((resetStatistics || purgeDestination) && tvalue.hasNext()) {
            String key = (String) tvalue.next();
            String execFilter = StringUtils.isEmpty(topicName) ? queueName : topicName;
            if (purgeDestination) {
                if (!StringUtils.isEmpty(execFilter) && !execFilter.contains("*")) {
                    jolokiaClient.purge(execFilter);
                } else {
                    logger.warn("Not purging for empty or wildcard filter: {}", execFilter);
                }
                respTimeMillis = jolokiaClient.getResponseTimeMillis();
            } else {
                jolokiaClient.resetStatistics(execFilter);
                respTimeMillis = jolokiaClient.getResponseTimeMillis();
            }
            // return immediately to trigger a refresh
            purgeDestination = false;
            resetStatistics = false;
            return 0;
        }

        tvalue = (MyJSONObject) json.get("value");
        String cols[] = new String[tvalue.length() + 1];

        int ci = 0;
        cols[ci] = "Attribute";

        while (tvalue.hasNext()) {
            String key = (String) tvalue.next();
            ci = ci + 1;
            cols[ci] = getKeyElement(key, "brokerName");
        }

        String attrs[] = {
            "Name",
            "AverageEnqueueTime",
            "AverageBlockedTime",
            "ConsumerCount",
            "ProducerCount",
            "DequeueCount",
            "DequeuesPerInterval",
            "EnqueueCount",
            "EnqueuesPerInterval",
            "ForwardCount",
            "ExpiredCount",
            "DispatchCount",
            "InFlightCount",
            "ProducerFlowControl",
            "BlockedSends",
            "ExpiredCount",
            "AverageMessageSize",
            "MaxMessageSize",
            "MemoryLimit",
            "MemoryPercentUsage",
            "QueueSize",
            "Time Stamp",
            "Query time"};

        String data[][] = new String[attrs.length][cols.length];
        for (int i = 0; i < attrs.length; i++) {
            data[i][0] = attrs[i];
        }
        int i = 0;
        tvalue = (MyJSONObject) json.get("value");
        while (tvalue.hasNext()) {
            String key = (String) tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(key);
            value.setDecimalFormat("%,d");

            i = i + 1;

            int dequeuesPerInterval = 0;
            if (lastDequeueCounts == null || lastDequeueCounts.length != cols.length) {
                lastDequeueCounts = new int[cols.length];
            } else {
                int c = value.getInteger("DequeueCount");
                dequeuesPerInterval = c - lastDequeueCounts[i];
                if (dequeuesPerInterval < 0) {
                    dequeuesPerInterval = 0;
                }
                lastDequeueCounts[i] = c;
            }
            int enqueuesPerInterval = 0;
            if (lastEnqueueCounts == null || lastEnqueueCounts.length != cols.length) {
                lastEnqueueCounts = new int[cols.length];
            } else {
                int c = value.getInteger("EnqueueCount");
                enqueuesPerInterval = c - lastEnqueueCounts[i];
                if (enqueuesPerInterval < 0) {
                    enqueuesPerInterval = 0;
                }
                lastEnqueueCounts[i] = c;
            }

            String coldata[] = {
                value.getString("Name"),
                value.getString("AverageEnqueueTime"),
                value.getString("AverageBlockedTime"),
                value.getString("ConsumerCount"),
                value.getString("ProducerCount"),
                value.getString("DequeueCount"),
                Integer.toString(dequeuesPerInterval),
                value.getString("EnqueueCount"),
                Integer.toString(enqueuesPerInterval),
                value.getString("ForwardCount"),
                value.getString("ExpiredCount"),
                value.getString("DispatchCount"),
                value.getString("InFlightCount"),
                value.getString("ProducerFlowControl"),
                value.getString("BlockedSends"),
                value.getString("ExpiredCount"),
                value.getString("AverageMessageSize"),
                value.getString("MaxMessageSize"),
                value.getString("MemoryLimit"),
                value.getString("MemoryPercentUsage"),
                value.getString("QueueSize"),
                timeFormat.format(new Date((long) json.getInteger("timestamp") * 1000)),
                respTimeMillis + " ms"};
            for (int j = 0; j < coldata.length; j++) {
                data[j][i] = coldata[j];
            }
        }
        updateTableDisplay(cols, data);
        return sleepSecs;
    }

    private long renderConsumers(MyJSONObject json) {

        String cols[] = {
            "ConsumerId", "BrokerName", "ClientId", "ConnectionId", "DesitinationName", "SessionId",
            "Network", "EnqueueCounter", "DequeueCounter", "DispatchedQueueSize",
            "DispatchedCounter", "PendingQueueSize", "PrefetchSize", "ConsumedCount"};
        MyJSONObject value = (MyJSONObject) json.get("value");

        ArrayList<String[]> al = new ArrayList();
        int rowcnt = 0;
        long nextSleepSecs = sleepSecs;

        int selectedRows[] = showTable.getSelectedRows();
        Set<String> keySet = value.keySet();

        for (String key : keySet) {
            String consumerId = getKeyElement(key, "consumerId");
            String broker = getKeyElement(key, "brokerName");

            MyJSONObject tvalue = (MyJSONObject) (MyJSONObject) value.get(key);

            String destinationName = tvalue.getString("DestinationName");
            String clientId = tvalue.getString("ClientId");
            String tdata[] = {
                consumerId, broker, clientId, tvalue.getString("ConnectionId"),
                destinationName, tvalue.getString("SessionId"),
                tvalue.getString("Network"), tvalue.getString("EnqueueCounter"), tvalue.getString("DequeueCounter"),
                tvalue.getString("DispatchedQueueSize"),
                tvalue.getString("DispatchedCounter"), tvalue.getString("PendingQueueSize"),
                tvalue.getString("PrefetchSize"), tvalue.getString("ConsumedCount")};
            al.add(tdata);

            if (resetStatistics) {
                boolean selected = false;
                for (int i = 0; i < selectedRows.length; i++) {
                    if (rowcnt == showTable.getSelectedRowUnsorted(selectedRows[i])) {
                        selected = true;
                    }
                }
                if (selected) {
                    jolokiaClient.resetConsumerStatistics(destinationName, consumerId, clientId);
                    respTimeMillis = jolokiaClient.getResponseTimeMillis();
                    nextSleepSecs = 0;
                }
            }
            rowcnt++;
        }
        if (resetStatistics) {
            showTable.clearSelection();
            resetStatistics = false;
            purgeDestination = false;
        }
        String[][] data = new String[al.size()][];
        for (int i = 0; i < al.size(); i++) {
            data[i] = al.get(i);
        }
        updateTableDisplay(cols, data);
        return nextSleepSecs;
    }

    private long renderQueueOrTopic(MyJSONObject json) {
        if (resetStatistics || purgeDestination) {
            resetOrPurgeSelectedQueuesOrTopics(json);
            // return 0 for an immediate refresh of the table
            showTable.clearSelection();
            resetStatistics = false;
            purgeDestination = false;
            return 0;
        }

        String cols[] = {
            "Name", "BrokerName", "AverageEnqueueTime", "MinEnqueueTime", "MaxEnqueueTime",
            "ConsumerCount", "ProducerCount", "DequeueCount", "DequeuesPerInterval",
            "EnqueueCount", "EnqueuesPerInterval", "ForwardCount",
            "ExpiredCount", "InFlightCount", "MaxMessageSize", "MemoryLimit",
            "MemoryPercentUsage", "QueueSize"};

        ArrayList<String[]> al = new ArrayList();

        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        int rowcnt = 0;
        long nextSleepSecs = sleepSecs;
        // Broker loop

        while (tvalue.hasNext()) {
            String queueKey = (String) tvalue.next();
            String broker = getKeyElement(queueKey, "brokerName");
            MyJSONObject value = (MyJSONObject) tvalue.get(queueKey);
            value.setDecimalFormat("%,d");

            int dequeuesPerInterval = 0;
            if (lastDequeueCounts == null || lastDequeueCounts.length != tvalue.length()) {
                lastDequeueCounts = new int[tvalue.length()];
            } else {
                int c = value.getInteger("DequeueCount");
                dequeuesPerInterval = c - lastDequeueCounts[rowcnt];
                if (dequeuesPerInterval < 0) {
                    dequeuesPerInterval = 0;
                }
                lastDequeueCounts[rowcnt] = c;
            }
            int enqueuesPerInterval = 0;
            if (lastEnqueueCounts == null || lastEnqueueCounts.length != tvalue.length()) {
                lastEnqueueCounts = new int[tvalue.length()];
            } else {
                int c = value.getInteger("EnqueueCount");
                enqueuesPerInterval = c - lastEnqueueCounts[rowcnt];
                if (enqueuesPerInterval < 0) {
                    enqueuesPerInterval = 0;
                }
                lastEnqueueCounts[rowcnt] = c;
            }

            String tdata[] = {
                value.getString("Name"), broker, value.getString("AverageEnqueueTime"),
                value.getString("MinEnqueueTime"), value.getString("MaxEnqueueTime"),
                value.getString("ConsumerCount"), value.getString("ProducerCount"),
                value.getString("DequeueCount"), Integer.toString(dequeuesPerInterval),
                value.getString("EnqueueCount"), Integer.toString(enqueuesPerInterval),
                value.getString("ForwardCount"),
                value.getString("ExpiredCount"), value.getString("InFlightCount"),
                value.getString("MaxMessageSize"), value.getString("MemoryLimit"),
                value.getString("MemoryPercentUsage"), value.getString("QueueSize")};
            al.add(tdata);

            rowcnt++;
        }
        String[][] data = new String[al.size()][];
        for (int i = 0; i < al.size(); i++) {
            data[i] = al.get(i);
        }
        updateTableDisplay(cols, data);
        return nextSleepSecs;
    }

    private void resetOrPurgeSelectedQueuesOrTopics(MyJSONObject json) {
        MyJSONObject tvalue = (MyJSONObject) json.get("value");


        int rowcnt = 0;
        int selectedRows[] = showTable.getSelectedRows();
        while (tvalue.hasNext()) {
            String queueKey = (String) tvalue.next();
            boolean selected = false;
            for (int i = 0; i < selectedRows.length; i++) {
                if (rowcnt == showTable.getSelectedRowUnsorted(selectedRows[i])) {
                    selected = true;
                }
            }
            if (selected) {
                MyJSONObject value = (MyJSONObject) tvalue.get(queueKey);
                if (purgeDestination) {
                    jolokiaClient.purge(value.getString("Name"));
                    respTimeMillis = jolokiaClient.getResponseTimeMillis();
                } else {
                    jolokiaClient.resetStatistics(value.getString("Name"));
                    respTimeMillis = jolokiaClient.getResponseTimeMillis();
                }
            }
            rowcnt++;
        }
    }

    private long renderStatus(MyJSONObject json) {

        if (resetStatistics) {
            MyJSONObject tvalue = (MyJSONObject) json.get("value");
            if (tvalue.hasNext()) {
                jolokiaClient.resetBrokerStatistics();
                respTimeMillis = jolokiaClient.getResponseTimeMillis();
            }
            resetStatistics = false;
            return 0;
        }

        MyJSONObject tvalue = (MyJSONObject) json.get("value");
        String cols[] = new String[tvalue.length() + 1];

        int ci = 0;
        cols[ci] = "Attribute";

        while (tvalue.hasNext()) {
            String key = (String) tvalue.next();
            ci = ci + 1;
            cols[ci] = getKeyElement(key, "brokerName");
        }

        String[] attrs = {
            "BrokerName",
            "BrokerVersion",
            "Uptime",
            "MemoryLimit",
            "MemoryPercentUsage",
            "StoreLimit",
            "StorePercentUsage",
            "TempLimit",
            "TempPercentUsage",
            "CurrentConnections",
            "TotalConnections",
            "TotalConsumers",
            "TotalProducers",
            "TotalEnqueues",
            "TotalDequeues",
            "TotalMessages",
            "MaxMessageSize",
            "AverageMessageSize",
            "MinMessageSize",
            "Persistent",
            "Slave",
            "Time Stamp",
            "Query time"};

        String data[][] = new String[attrs.length][cols.length];
        for (int i = 0; i < attrs.length; i++) {
            data[i][0] = attrs[i];
        }
        int i = 0;
        tvalue = (MyJSONObject) json.get("value");

        while (tvalue.hasNext()) {
            String key = (String) tvalue.next();
            MyJSONObject value = (MyJSONObject) tvalue.get(key);
            value.setDecimalFormat("%,d");
            i = i + 1;
            String coldata[] = {
                value.getString("BrokerName"),
                value.getString("BrokerVersion"),
                value.getString("Uptime"),
                value.getString("MemoryLimit"),
                value.getString("MemoryPercentUsage"),
                value.getString("StoreLimit"),
                value.getString("StorePercentUsage"),
                value.getString("TempLimit"),
                value.getString("TempPercentUsage"),
                value.getString("CurrentConnectionsCount"),
                value.getString("TotalConnectionsCount"),
                value.getString("TotalConsumerCount"),
                value.getString("TotalProducerCount"),
                value.getString("TotalEnqueueCount"),
                value.getString("TotalDequeueCount"),
                value.getString("TotalMessageCount"),
                value.getString("MaxMessageSize"),
                value.getString("AverageMessageSize"),
                value.getString("MinMessageSize"),
                value.getString("Persistent"),
                value.getString("Slave"),
                timeFormat.format(new Date((long) json.getInteger("timestamp") * 1000)),
                respTimeMillis + " ms"};
            for (int j = 0; j < coldata.length; j++) {
                data[j][i] = coldata[j];
            }

        }
        updateTableDisplay(cols, data);
        return sleepSecs;
    }

    private JMenuBar createMenuBar() {

        JMenuBar menuBar = new JMenuBar();

        ActionListener al = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (e.getActionCommand().startsWith("Options")) {
                    optionsDialog();
                } else {
                    if (e.getActionCommand().startsWith("Purge")) {
                        if (query == DataType.QUEUES || query == DataType.TOPICS || query == DataType.DESTINATION) {
                            purgeDestination = true;
                        }
                    } else if (e.getActionCommand().startsWith("Reset")) {
                        resetStatistics = true;
                    }
                    for (int ti = 0; ti < DisplayData.thread.length; ti++) {
                        DisplayData.thread[ti].interrupt();
                    }
                }
            }
        };
        if (resetAllowed) {
        	JButton resetButton = new JButton("Reset statistics");
        	resetButton.addActionListener(al);
        	resetButton.setToolTipText("Reset statistics of selected items");
        	menuBar.add(resetButton);
        }
        if (purgeAllowed) {
        	JButton purgeButton = new JButton("Purge destination");
        	purgeButton.addActionListener(al);
        	purgeButton.setToolTipText("Purge messages of selected items");
        	menuBar.add(purgeButton);
        }
        JButton optionsButton = new JButton("Options");
        optionsButton.addActionListener(al);
        optionsButton.setToolTipText("Open screen to change the query");
        menuBar.add(optionsButton);

        JButton refreshButton = new JButton("Refresh");
        refreshButton.addActionListener(al);
        refreshButton.setToolTipText("Cancel the sleep interval and refresh the display");
        menuBar.add(refreshButton);

        return menuBar;
    }

    private void optionsDialog() {
        JPanel pane = new JPanel();
        pane.setLayout(new GridBagLayout());
        GridBagConstraints gridBagConstraints = new GridBagConstraints();
        gridBagConstraints.fill = GridBagConstraints.BOTH;
        gridBagConstraints.weightx = 0.75;
        gridBagConstraints.weighty = 0.75;
        gridBagConstraints.gridx = 0;
        gridBagConstraints.gridy = 0;
        List<DataType> queries = new ArrayList<>(Arrays.asList(DataType.values()));
        queries.remove(DataType.TEST);
        int s = query.ordinal();

        JList queryList = new JList(queries.toArray());
        queryList.setSelectedIndex(s);
        queryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queryList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        queryList.setVisibleRowCount(2);
        queryList.setFixedCellWidth(100);
        
        if (query==DataType.QUEUES) {
        	int rows[]=showTable.getSelectedRows();
        	if (rows.length==1) {
        		queueName=showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]),0);
        	}
        }
        if (query==DataType.TOPICS) {
        	int rows[]=showTable.getSelectedRows();
        	if (rows.length==1) {
        		topicName=showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]),0);
        	}
        }
		showTable.clearSelection();

        JTextField queueText = new JTextField(40);
        queueText.setText(queueName);
        JTextField topicText = new JTextField(40);
        topicText.setText(topicName);
        JTextField queryFilterText = new JTextField(40);
        queryFilterText.setText(queryFilter);
        JTextField consumerFilterText = new JTextField(40);
        consumerFilterText.setText(consumerFilter);
        JTextField sleepText = new JTextField(5);
        sleepText.setText("" + sleepSecs);


        addToGrid(pane, new JLabel("Query type"), queryList, gridBagConstraints);
        addToGrid(pane, new JLabel("Queue name"), queueText, gridBagConstraints);
        addToGrid(pane, new JLabel("Topic name"), topicText, gridBagConstraints);
        addToGrid(pane, new JLabel("Filter"), queryFilterText, gridBagConstraints);
        addToGrid(pane, new JLabel("Consumer filter"), consumerFilterText, gridBagConstraints);
        addToGrid(pane, new JLabel("Display interval"), sleepText, gridBagConstraints);

        int option = JOptionPane.showConfirmDialog(null, pane, "Options", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);

        if (option == JOptionPane.YES_OPTION) {

            query = (DataType) queryList.getSelectedValue();
            queueName = queueText.getText();
            topicName = topicText.getText();
            queryFilter = queryFilterText.getText();
            consumerFilter = consumerFilterText.getText();
            title = "ActiveMQ Updated " + query.alias();
            try {
                int secs = Integer.parseInt(sleepText.getText());
                if (secs > 0) {
                    sleepSecs = secs;
                }
            } catch (NumberFormatException nfe) {
                logger.error("Invalid number format for sleepSecs '{}', keeping old value {}", sleepText.getText(), sleepSecs);
            }
            for (int ti = 0; ti < DisplayData.thread.length; ti++) {
                DisplayData.instances[ti].constructTitle();
                DisplayData.instances[ti].reconfigureRequestBuilder();
                DisplayData.thread[ti].interrupt();
            }
        }
    }

    private void addToGrid(JPanel pane, Component left, Component right, GridBagConstraints constraints) {
        pane.add(left, constraints);
        constraints.gridx = 1;
        pane.add(right, constraints);
        constraints.gridx = 0;
        constraints.gridy = constraints.gridy + 1;
    }

    private void reconfigureRequestBuilder() {
        requestBuilder
            .queue(queueName)
            .topic(topicName)
            .filter(queryFilter)
            .consumerFilter(consumerFilter)
            .dataType(query);
    }
}
