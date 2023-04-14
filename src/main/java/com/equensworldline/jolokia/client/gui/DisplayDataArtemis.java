package com.equensworldline.jolokia.client.gui;

import com.equensworldline.amq.utils.JMXApiException;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.gui.ShowTable;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;
import com.equensworldline.jolokia.client.DataTypeArtemis;
import com.equensworldline.jolokia.client.Main;

import org.apache.commons.lang3.StringUtils;
import org.jolokia.client.BasicAuthenticator;
import org.jolokia.client.J4pClient;
import org.jolokia.client.exception.J4pException;
import org.jolokia.client.request.J4pExecRequest;
import org.jolokia.client.request.J4pExecResponse;
import org.jolokia.client.request.J4pReadRequest;
import org.jolokia.client.request.J4pReadResponse;
import org.jolokia.client.request.J4pRequest;
import org.jolokia.client.request.J4pResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MalformedObjectNameException;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class DisplayDataArtemis implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(DisplayDataArtemis.class);
    private static Thread[] thread;
    private static DisplayDataArtemis[] instances;
    private static int sleepSecs;
    private static DataTypeArtemis query;
    private static String queueName;
    private static String addressName;
    private static String queryFilter;
    private final String endpoint;

    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private ShowTable showTable;
    private String title;
    private long respTimeMillis;
    private String broker;
    private boolean resetStatistics = false;
    private boolean purgeDestination = false;
    private boolean showStatusBool = true;
    private J4pClient j4pClient;
    private String lastError = "";
    
    // props values
    static boolean purgeAllowed=true;
    static boolean resetAllowed=true;

    private static final String ENDPOINT_DEFAULT = "http://localhost:8161/console/jolokia/";
    private static final String SLEEP_SECS_DEFAULT = "10";
    private static final String USER_DEFAULT = "admin";
    private static final String PASSWORD_DEFAULT = "admin";
    private boolean immediateRefreshRequested=false;

    public DisplayDataArtemis(String endpoint) {
        this.endpoint = endpoint;
        this.respTimeMillis = 0;
    }

    // Post request and display activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // ActiveMQ Host is the url and root jmx path
        String endpoint = MyArgs.env("ACTIVEMQ_HOST", ENDPOINT_DEFAULT);

        String[] endpoints = endpoint.split(",");

        try {
            query = DataTypeArtemis.fromAlias(MyArgs.arg(args, 0, DataTypeArtemis.STATUS.alias()));
        } catch (IllegalArgumentException e) {
            logger.info("Not a valid query: ",e);
            System.exit(1);
        }

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", SLEEP_SECS_DEFAULT));

        queueName = MyArgs.arg(args, "-queue", "");
        addressName = MyArgs.arg(args, "-address", "");
        queryFilter = MyArgs.arg(args, "-filter", "");

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
        
        try {
            Properties properties = new Properties();
            InputStream stream=DisplayData.class.getResourceAsStream("/prop.properties");
            if (stream==null)
            	logger.warn("No internal properties file");
            else {
            	properties.load(stream);
            	String purgeStr=properties.getProperty("purgeAllowed", "yes");
            	if (purgeStr!=null) purgeAllowed=purgeStr.startsWith("y")||purgeStr.startsWith("t");
            	String resetStr=properties.getProperty("resetAllowed", "yes");
            	if (resetStr!=null) resetAllowed=resetStr.startsWith("y")||resetStr.startsWith("t");
            	logger.debug("props purgeAllowed "+purgeStr+" resetAllowed "+resetStr);
            }
        } catch (Exception e) {
            logger.warn("Internal properties error "+e);
        }

        thread = new Thread[endpoints.length];
        instances = new DisplayDataArtemis[endpoints.length];
        // Create a thread and task for each host url provided
        for (int i = 0; i < endpoints.length; i++) {
            instances[i] = new DisplayDataArtemis(endpoints[i]);
            thread[i] = new Thread(instances[i]);
            thread[i].start();
        }

    }

    @SuppressWarnings("squid:S106") // CLI usage is proper to System.out
    private static void printUsage() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println("DisplayDataArtemis "+version);
        System.out.println("usage: java -jar amq-jmx-api-x.x.x-shaded.jar [DisplayDataArtemis|LogDataArtemis] <query> " +
            "[-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filertext>]");
        System.out.print("Querie types are: ");
        List<DataTypeArtemis> queries = new ArrayList<>(Arrays.asList(DataTypeArtemis.values()));
        for (Object q : queries.toArray()) {
            System.out.print(q + " ");
        }
        System.out.println();
        System.out.println("For destination and consumers you must specify a queue or address");
        System.out.println("Environment variables:");
        System.out.println("ACTIVEMQ_HOST=" + ENDPOINT_DEFAULT);
        System.out.println("REFRESH_TIME=" + SLEEP_SECS_DEFAULT);
        System.out.println("ACTIVEMQ_USER=" + USER_DEFAULT);
        System.out.println("ACTIVEMQ_PASSWORD=" + PASSWORD_DEFAULT);
        System.exit(0);
    }

    private void constructTitle() {
        title = String.format("ActiveMQ Data %s (%s) %s", broker, endpoint, query.alias());
    }

    public void run() {

        constructTitle();

        showTable = new ShowTable(title, new String[]{""}, new String[][]{{""}});

        showTable.setMenu(createMenuBar());

        String user = MyArgs.env("ACTIVEMQ_USER", USER_DEFAULT);
        String password = MyArgs.env("ACTIVEMQ_PASSWORD", PASSWORD_DEFAULT);

        try {
        j4pClient = J4pClient.url(endpoint)
            .user(user)
            .password(password)
            .authenticator(new BasicAuthenticator().preemptive())
            .connectionTimeout(3000)
            .build();
        } catch (Exception e) {
        	logger.error("URL error "+e);
        	System.exit(1);
        }

        long nextSleepSecs = sleepSecs;
        boolean continueLoop = true;
        while (continueLoop) {

            J4pResponse<? extends J4pRequest> res = executeQuery(j4pClient);

            if (res != null) {
                if (showStatusBool) {
                    logger.info("Query success {}", query);
                    this.broker = getBrokerName();
                    this.constructTitle();
                }
                showStatusBool = false;
                nextSleepSecs = renderResult(nextSleepSecs, res);
            } else {
                showStatusBool = true;
                String[] cols = {"Query", "Error"};
                String[][] data = {{query.alias(), lastError}};
                updateTableDisplay(cols, data);
            }

            try {
                Thread.sleep(nextSleepSecs * 1000);
            } catch (InterruptedException e) {
                if (!immediateRefreshRequested) {
                    logger.warn("Got interrupted while sleeping for periodic refresh");
                    continueLoop = false;
                    // reset the interrupt state for this thread
                    Thread.currentThread().interrupt();
                } else {
                    // expected interrupt by self for immediate display refresh
                    // due to updated options and/or actions that influence displayed stats.
                    logger.trace("Got interrupted for immediate refresh");
                    immediateRefreshRequested = false;
                }
            }

        }

    }

    private J4pResponse<? extends J4pRequest> executeQuery(J4pClient j4pClient) {
        J4pResponse<? extends J4pRequest> resp = null;
        J4pReadRequest req;
        J4pExecRequest ereq;

        if (broker == null) {
            broker = getBrokerName();
        }

        long now = System.currentTimeMillis();
        try {
            switch (query) {
            case STATUS:
                // Status display may reset statistics, which requires an earlier refresh
                req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"");
                resp = j4pClient.execute(req);
                break;
            case QUEUES:
                // Queues display may purge or reset statistics, which requires an earlier refresh
                req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "QueueNames");
                resp = j4pClient.execute(req);
                break;
            case ADDRESSES:
                // Addresses display may purge or reset statistics, which requires an earlier refresh
                req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "AddressNames");
                resp = j4pClient.execute(req);
                break;
            case DESTINATION:
                // Destinations (Queue(s) or Address(es)) display may purge or reset statistics, which requires an earlier refresh
                if (addressName.length() > 0) {
                    req = new J4pReadRequest(
                        "org.apache.activemq.artemis:address=\"" + addressName + "\",broker=\"" + broker + "\",component=addresses");
                } else {
                    req = new J4pReadRequest(
                        "org.apache.activemq.artemis:address=\"" + queueName + "\",broker=\"" + broker + "\",component=addresses,queue=\"" + queueName + "\",routing-type=\"anycast\",subcomponent=queues");
                }
                resp = j4pClient.execute(req);
                break;
            case SESSIONS:
                ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "listAllSessionsAsJSON");
                resp = j4pClient.execute(ereq);
                break;
            case CONSUMERS:
                ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "listAllConsumersAsJSON");
                resp = j4pClient.execute(ereq);
                break;
            case PRODUCERS:
                ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "listProducersInfoAsJSON");
                resp = j4pClient.execute(ereq);
                break;
            case CONNECTIONS:
                ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "listConnectionsAsJSON");
                resp = j4pClient.execute(ereq);
                break;
            case ACCEPTORS:
                req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\",component=acceptors,name=\"*\"");
                resp = j4pClient.execute(req);
                break;
            case MEMORY:
                req = new J4pReadRequest("java.lang:type=Memory", "HeapMemoryUsage");
                resp = j4pClient.execute(req);
                break;
            case THREADS:
                req = new J4pReadRequest("java.lang:type=Threading");
                resp = j4pClient.execute(req);
                break;
            default:
                logger.warn("Should not be here");
                throw new JMXApiException("Internal error");
            }
            respTimeMillis = System.currentTimeMillis() - now;
        } catch (RuntimeException e) {
            logger.error("Exception executing query "+query, e);
        } catch (MalformedObjectNameException e) {
            lastError = "Malformed name "+query+" "+broker;
            logger.debug(lastError,e);
        } catch (J4pException e) {
            lastError = e.toString();
            if (lastError.contains("javax.management.InstanceNotFoundException")) {
                lastError = "Instance not found "+query+" "+broker;
                broker=null;	// Force lookup in case name changed
            } else 
            	logger.warn("Unexpected exception ",e);
        }

        return resp;
    }

    private void executeResetStatistics() {
        // ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
        J4pExecRequest ereq;
        try {
            ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "resetAllMessageCounters");
            j4pClient.execute(ereq);
            ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "resetAllMessageCounterHistories");
            j4pClient.execute(ereq);
        } catch (MalformedObjectNameException e) {
            logger.warn("Reset all syntax error ", e);
        } catch (J4pException e) {
            logger.warn("Reset all error ", e);
        }
    }

    private void executeResetStatistics(String queueId) {
        // ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
        J4pExecRequest ereq;
        try {
            String[] stats = {"resetMessageCounter", "resetMessagesAcknowledged", "resetMessagesAdded", "resetMessagesExpired",
                "resetMessagesKilled"};
            for (String call : stats) {
                ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\",component=addresses," +
                    "address=\"" + queueId + "\",subcomponent=queues,routing-type=\"anycast\"," +
                    "queue=\"" + queueId + "\"", call);
                j4pClient.execute(ereq);
            }
        } catch (MalformedObjectNameException e) {
            logger.warn("Reset syntax error", e);
        } catch (J4pException e) {
            logger.warn("Reset error", e);
        }
    }

    private void executePurge(String queueId) {
        // ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
        J4pExecRequest ereq;
        try {
            ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\",component=addresses," +
                "address=\"" + queueId + "\",subcomponent=queues,routing-type=\"anycast\"," +
                "queue=\"" + queueId + "\"", "removeAllMessages()");
            j4pClient.execute(ereq);
        } catch (MalformedObjectNameException e) {
            logger.warn("Purge syntax error ", e);
        } catch (J4pException e) {
            logger.warn("Purge error ", e);
        }
    }

    private <T extends J4pRequest> long renderResult(long nextSleepSecs, J4pResponse<T> results) {

        long resultingNextSleepSecs = nextSleepSecs;
        try {
            switch (query) {
            case STATUS:
                // Status display may reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderStatus((J4pReadResponse) results);
                break;
            case ADDRESSES:
                // Addresses display may purge or reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderAddresses((J4pReadResponse) results);
                break;
            case QUEUES:
                // Queues display may purge or reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderQueues((J4pReadResponse) results);
                break;
            case DESTINATION:
                // Destinations (Queue(s) or Address(s)) display may purge or reset statistics, which requires an earlier refresh
                resultingNextSleepSecs = renderDestination((J4pReadResponse) results);
                break;
            case SESSIONS:
                renderSessions((J4pExecResponse) results);
                break;
            case CONSUMERS:
                renderConsumers((J4pExecResponse) results);
                break;
            case PRODUCERS:
                renderProducers((J4pExecResponse) results);
                break;
            case CONNECTIONS:
                renderConnections((J4pExecResponse) results);
                break;
            case ACCEPTORS:
                renderAcceptors((J4pReadResponse) results);
                break;
            case MEMORY:
                renderMemory((J4pReadResponse) results);
                break;
            case THREADS:
                renderThreads((J4pReadResponse) results);
                break;
            default:
                logger.warn("Should not be here");
                throw new JMXApiException("Internal error");
            }
        } catch (RuntimeException e) {
            logger.error("Exception while parsing and rendering response", e);
        }
        return resultingNextSleepSecs;
    }

    private void renderThreads(J4pReadResponse resp) {

        Map<String, Long> value = resp.getValue();

        String[] cols = {"Attribute", "Value"};
        String[][] data = {
            {"DaemonThreadCount", "" + value.get("DaemonThreadCount")},
            {"PeakThreadCount", "" + value.get("PeakThreadCount")},
            {"ThreadCount", "" + value.get("ThreadCount")},
            {"TotalStartedThreadCount", "" + value.get("TotalStartedThreadCount")},
            {"Timetamp", timeFormat.format(resp.getRequestDate())},
            {"QueryTtime", respTimeMillis + " ms"}};
        updateTableDisplay(cols, data);
    }

    private void renderMemory(J4pReadResponse resp) {

        Map<String, Long> value = resp.getValue();
        long used = value.get("used");
        long max = value.get("max");
        float usage = (float) used * 100 / max;

        String[] cols = {"Attribute", "Value"};
        String[][] data = {
            {"HeapMemoryUsage init", String.format("%,d", value.get("init"))},
            {"HeapMemoryUsage committed", String.format("%,d", value.get("committed"))},
            {"HeapMemoryUsage max", String.format("%,d", value.get("max"))},
            {"HeapMemoryUsage used", String.format("%,d", value.get("used"))},
            {"HeapMemoryUsage percent used", String.format("%.2f", usage)},
            {"Timestamp", timeFormat.format(resp.getRequestDate())},
            {"QueryTime", respTimeMillis + " ms"}};
        updateTableDisplay(cols, data);
    }

    private void renderAcceptors(J4pReadResponse resp) {

        String[] cols = {"Name", "Started", "Parameters"};

        Map<String, Object> value = resp.getValue();

        String[][] data = new String[value.size()][cols.length];

        int r = 0;
        for (Map.Entry<String, Object> row : value.entrySet()) {
            // Get value as string and convert it to JSON because I do not want to use the SimpleJSON object method getValueAsJSON
            MyJSONObject valueObject = new MyJSONObject(row.getValue().toString());
            data[r][0] = valueObject.getString("Name");
            data[r][1] = valueObject.getString("Started");
            data[r][2] = valueObject.getString("Parameters");
            r++;
        }

        updateTableDisplay(cols, data);
    }

    private void renderConnections(J4pExecResponse resp) {

        String json = resp.getValue();
        MyJSONArray recs = new MyJSONArray(json);

        String[] cols = {"No Data"};
        String[][] data = {{""}};

        int r = 0;
        while (recs.hasNext()) {

            MyJSONObject rec = (MyJSONObject) recs.next();

            if (cols.length == 1) {
                cols = new String[rec.length()];
                data = new String[recs.length()][cols.length];
            }

            int a = 0;
            while (rec.hasNext()) {
                String key = rec.next();
                cols[a] = key;
                if ("creationTime".equals(key)) {
                    data[r][a] = timeFormat.format(new Date(rec.getLong(key)));
                } else {
                    data[r][a] = rec.getString(key);
                }
                a++;
            }
            r++;
        }

        updateTableDisplay(cols, data);
    }

    @SuppressWarnings("PMD.AvoidArrayLoops")
    private long renderDestination(J4pReadResponse resp) {

        if ((resetStatistics || purgeDestination)) {
            String execFilter = StringUtils.isEmpty(addressName) ? queueName : addressName;
            if (purgeDestination) {
                if (!StringUtils.isEmpty(execFilter) && !execFilter.contains("*")) {
                    executePurge(execFilter);
                } else {
                    logger.warn("Not purging for empty or wildcard filter: {}", execFilter);
                }
            } else {
                executeResetStatistics(execFilter);
            }
            // return immediately to trigger a refresh
            purgeDestination = false;
            resetStatistics = false;
            return 0;
        }

        String[] cols;
        String[][] data;

        try {
            Collection<String> attributeSet = resp.getAttributes();

            List<String> attributes = new ArrayList<>(attributeSet);

            Collections.sort(attributes);    // Sort alphabetically

            if (attributes.remove("Name")) {
                attributes.add(0, "Name");    // Move queue name to front
            }

            cols = new String[]{"Attribute", "Value"};
            data = new String[attributes.size() + 2][cols.length];

            int ai = 0;
            for (String attr : attributes) {
                data[ai][0] = attr;
                Object v = resp.getValue(attr);
                if (v == null) {
                    data[ai][1] = "";
                } else {
                    data[ai][1] = v.toString();
                }
                ai = ai + 1;
            }
            data[ai][0] = "Timestamp";
            data[ai][1] = timeFormat.format(resp.getRequestDate());
            ai++;
            data[ai][0] = "QueryTime";
            data[ai][1] = respTimeMillis + " ms";
        } catch (RuntimeException e) {
            logger.warn("RuntimeException", e);
            cols = new String[]{"Query", "Error"};
            data = new String[][]{{"destination", e.toString()}};
        }

        updateTableDisplay(cols, data);
        return sleepSecs;
    }

    private long renderConsumers(J4pExecResponse resp) {

        String json = resp.getValue();

        MyJSONArray recs = new MyJSONArray(json);

        String[] cols = {"No Data"};
        String[][] data = {{""}};

        // Filter out non-matching records
        if (!queryFilter.isEmpty()) {
            MyJSONArray newrecs = new MyJSONArray();
            for (int i = 0; i < recs.length(); i++) {
                MyJSONObject rec = (MyJSONObject) recs.next();
                String key = rec.next();
                if (rec.getString(key).contains(queryFilter)) {
                    newrecs.put((MyJSONObject) recs.get(i));
                }
            }
            recs = newrecs;
        }
        // Create display cols and data
        int r = 0;
        while (recs.hasNext()) {

            MyJSONObject rec = (MyJSONObject) recs.next();

            if (cols.length == 1) {
                if (rec.get("metadata") == null) {
                    cols = new String[rec.length()];
                } else {
                    cols = new String[rec.length() - 1]; // Ignore session metadata
                }
                data = new String[recs.length()][cols.length];
            }

            int a = 0;
            while (rec.hasNext()) {
                String key = rec.next();
                if (!"metadata".equals(key)) {// Ignore session metadata - not useful and not always present
                    cols[a] = key;
                    if ("creationTime".equals(key)) {
                        data[r][a] = timeFormat.format(new Date(rec.getLong(key)));
                    } else {
                        data[r][a] = rec.getString(key);
                    }
                    a++;
                }
            }
            r++;
        }

        updateTableDisplay(cols, data);
        return sleepSecs;
    }

    private long renderSessions(J4pExecResponse resp) {
        return renderConsumers(resp);
    }

    private long renderProducers(J4pExecResponse resp) {
        return renderConsumers(resp);
    }

    private long renderAddresses(J4pReadResponse resp) {

        String[] cols = {
            "Address",
            "NumberOfMessages",
            "MessageCount",
            "RoutedMessageCount",
            "AddressSize",
            "NumberOfBytesPerPage",
            "Paging",
            "RolesAsJSON",
            "BindingNames",
            "UnRoutedMessageCount",
            "QueueNames",
            "RoutingTypes"
        };
        String[][] data;
        long nextSleepSecs = sleepSecs;

        try {
            Collection<String> respAddresses = resp.getValue();
            List<String> addresses = new ArrayList<>();

            for (String key : respAddresses) {
                if (key.contains(queryFilter)) {
                    addresses.add(key);
                }
            }

            data = new String[addresses.size()][cols.length];

	        List<String>badKeys=new ArrayList<String>();
            int ci = 0;
            for (String key : addresses) {

                try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
                    J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:address=\"" + key + "\",broker=\"" + broker + "\"," +
                        "component=addresses");
                    J4pReadResponse qresp = j4pClient.execute(req);

                    filterAndConvertResponse(cols, data[ci], qresp);
                    ci = ci + 1;
                } catch (MalformedObjectNameException e) {
                    logger.error("Error", e);
                } catch (J4pException e) {
                    logger.debug("Exception for key {} ", key, e);	// Some internal queues are not visible
					badKeys.add(key);
                }
            }
            if (ci < data.length) {    // Query on row failed so make data smaller
                String[][] newData = new String[ci][cols.length];
                System.arraycopy(data, 0, newData, 0, ci);
                data = newData;
            }
	        for(String key: badKeys) {addresses.remove(key);}
	        
	        if (resetStatistics || purgeDestination) {
	            resetOrPurgeSelectedQueues(addresses);
	            showTable.clearSelection();
	            resetStatistics = false;
	            purgeDestination = false;
	            return 0;
	        }
        } catch (RuntimeException e) {
            logger.debug("Encountered and ignored RuntimeException",e);
            cols = new String[]{"Query", "Error"};
            data = new String[][]{{"addresses", e.toString()}};
        }

        updateTableDisplay(cols, data);

        return nextSleepSecs;
    }

    /**
     *
     * @param cols The set of attributes we want to retrieve for display
     * @param datarow The datarow to fill
     * @param qresp The J4pReadResponse that holds the data that we want to render
     */
    private void filterAndConvertResponse(String[] cols, String[] datarow, J4pReadResponse qresp) {
        int ai = 0;
        for (String attr : cols) {
            Object v = qresp.getValue(attr);
            if (v == null) {
                datarow[ai] = "";
            } else {
                datarow[ai] = v.toString();
            }
            ai = ai + 1;
        }
    }

    private long renderQueues(J4pReadResponse resp) {

        String[] cols = {
            "Name",
            "Address",
            "MessageCount",
            "MessagesAdded",
            "DeliveringCount",
            "MessagesAcknowledged",
            "AcknowledgeAttempts",
            "User",
            "ConfigurationManaged",
            "GroupCount",
            "Exclusive",
            "PurgeOnNoConsumers",
            "DurableDeliveringSize",
            "PersistentSize",
            "ScheduledCount",
            "MessagesKilled",
            "DurableScheduledCount",
            "DurableMessageCount",
            "ExpiryAddress",
            "ID",
            "RoutingType",
            "Paused",
            "DurableDeliveringCount",
            "DurablePersistentSize",
            "DeadLetterAddress",
            "ConsumerCount",
            "MessagesExpired",
            "DeliveringSize",
            "LastValue",
            "Temporary",
            "ScheduledSize",
            "Durable",
            "DurableScheduledSize"
        };
        String[][] data;

        long nextSleepSecs = sleepSecs;

        try {
            Collection<String> respQueues = resp.getValue();
            List<String> queues = new ArrayList<>();

            for (String key : respQueues) {
                if (key.contains(queryFilter)) {
                    queues.add(key);
                }
            }

            data = new String[queues.size()][cols.length];

	        List<String>badKeys=new ArrayList<String>();
	        int ci = 0;
            for (String key : queues) {

                try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
                    J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:address=\"" + key + "\",broker=\"" + broker + "\"," +
                        "component=addresses,queue=\"" + key + "\",routing-type=\"anycast\",subcomponent=queues");
                    J4pReadResponse qresp = j4pClient.execute(req);

                    filterAndConvertResponse(cols, data[ci], qresp);
                    ci = ci + 1;
                } catch (MalformedObjectNameException e) {
                    logger.error("Error",e);
                } catch (J4pException e) {
                    logger.debug("Exception for key {} ", key, e);	// Some internal queues are not visible
					badKeys.add(key);
                }
            }
            if (ci < data.length) {    // Query on row failed so make data smaller
                String[][] newData = new String[ci][cols.length];
                System.arraycopy(data, 0, newData, 0, ci);
                data = newData;
            }
	        for (String key: badKeys) {queues.remove(key);};
	        
            if (resetStatistics || purgeDestination) {
                resetOrPurgeSelectedQueues(queues);
                showTable.clearSelection();
                resetStatistics = false;
                purgeDestination = false;
                return 0;
            }	        
        } catch (RuntimeException e) {
            logger.debug("Encountered and ignored RuntimeException",e);
            cols = new String[]{"Query", "Error"};
            data = new String[][]{{"queues", e.toString()}};
        }

        updateTableDisplay(cols, data);

        return nextSleepSecs;
    }


    private long renderStatus(J4pReadResponse resp) {

        if (resetStatistics) {
            executeResetStatistics();
            resetStatistics = false;
            return 0;
        }
        String[] cols = {"Attribute", "Value"};
        String[][] data;

        try {

            Collection<String> attributeSet = resp.getAttributes();

            List<String> attributes = new ArrayList<>(attributeSet);

            Collections.sort(attributes);    // Sort alphabetically

            data = new String[attributes.size() + 2][cols.length];
            int ci = 0;
            for (String key : attributes) {
                data[ci][0] = key;
                if (resp.getValue(key)==null)
                	data[ci][1]="null";		// Field might be null - example NodeId
                else
                	data[ci][1] = resp.getValue(key).toString();
                ci = ci + 1;
            }
            data[ci][0] = "Timestamp";
            data[ci][1] = timeFormat.format(resp.getRequestDate());
            ci++;
            data[ci][0] = "QueryTime";
            data[ci][1] = respTimeMillis + " ms";

        } catch (RuntimeException e) {
            logger.debug("Encountered and ignored RuntimeException",e);
            cols = new String[]{"Query", "Error"};
            data = new String[][]{{"status", e.toString()}};
        }

        updateTableDisplay(cols, data);

        return sleepSecs;
    }

    private String getBrokerName() {
        String brokerName = null;

        try {
            J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"*\"");
            J4pReadResponse resp = j4pClient.execute(req);
            String resultStr = resp.asJSONObject().toString();
            MyJSONObject obj = new MyJSONObject(resultStr);
            MyJSONObject value = (MyJSONObject) obj.get("value");
            Set keys = value.keySet();    // Get set of objects (only one)
            String keyStr = keys.toString();    // change to text and filter out the broker name (within quotes)
            brokerName = keyStr.substring(keyStr.lastIndexOf('=') + 1).replace("\"", "").replace("]", "");
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Getting broker ID", e);
            brokerName = "unknown";
        }
        return brokerName;
    }

    private void resetOrPurgeSelectedQueues(List<String> queues) {

        if (queues.size() != showTable.getRows()) {
            logger.warn("Table size selection mismatch {} {}", queues.size(), showTable.getRows());
            return;
        }

        int rowcnt = 0;
        int[] selectedRows = showTable.getSelectedRows();
        for (String queueKey : queues) {

            boolean selected = false;
            for (int i = 0; i < selectedRows.length; i++) {
                if (rowcnt == showTable.getSelectedRowUnsorted(selectedRows[i])) {
                    selected = true;
                }
            }
            if (selected) {
                if (purgeDestination) {
                    executePurge(queueKey);
                } else {
                    executeResetStatistics(queueKey);
                }
            }
            rowcnt++;
        }
    }

    private void updateTableDisplay(String[] cols, String[][] data) {
        showTable.updateTitle(title + ", items=" + data.length + ", resp. time " + respTimeMillis + "ms");
        showTable.updateTable(cols, data);
    }

    private JMenuBar createMenuBar() {

        JMenuBar menuBar = new JMenuBar();

        ActionListener al = new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (e.getActionCommand().startsWith("Options")) {
                    optionsDialog();
                } else {
                    if (e.getActionCommand().startsWith("Purge")) {
                        if (query == DataTypeArtemis.QUEUES || query == DataTypeArtemis.ADDRESSES || query == DataTypeArtemis.DESTINATION) {
                            purgeDestination = true;
                        }
                    } else if (e.getActionCommand().startsWith("Reset")) {
                        resetStatistics = true;
                    }
                    for (int ti = 0; ti < DisplayDataArtemis.thread.length; ti++) {
                        // ensure that we do not wait for the current sleep() to expire for refresh
                        DisplayDataArtemis.instances[ti].immediateRefreshRequested=true;
                        DisplayDataArtemis.thread[ti].interrupt();
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
        List<DataTypeArtemis> queries = new ArrayList<>(Arrays.asList(DataTypeArtemis.values()));

        int s = query.ordinal();

        JList queryList = new JList(queries.toArray());
        queryList.setSelectedIndex(s);
        queryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        queryList.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        queryList.setVisibleRowCount(2);
        queryList.setFixedCellWidth(100);

        if (query == DataTypeArtemis.QUEUES) {
            int[] rows = showTable.getSelectedRows();
            if (rows.length == 1) {
                queueName = showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]), 0);
            }
        }
        if (query == DataTypeArtemis.ADDRESSES) {
            int[] rows = showTable.getSelectedRows();
            if (rows.length == 1) {
                addressName = showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]), 0);
            }
        }
        showTable.clearSelection();

        JTextField queueText = new JTextField(40);
        queueText.setText(queueName);
        JTextField addressText = new JTextField(40);
        addressText.setText(addressName);
        JTextField queryFilterText = new JTextField(40);
        queryFilterText.setText(queryFilter);
        JTextField sleepText = new JTextField(5);
        sleepText.setText("" + sleepSecs);


        addToGrid(pane, new JLabel("Query type"), queryList, gridBagConstraints);
        addToGrid(pane, new JLabel("Queue name"), queueText, gridBagConstraints);
        addToGrid(pane, new JLabel("Address name"), addressText, gridBagConstraints);
        addToGrid(pane, new JLabel("Filter"), queryFilterText, gridBagConstraints);
        addToGrid(pane, new JLabel("Display interval"), sleepText, gridBagConstraints);

        int option = JOptionPane.showConfirmDialog(null, pane, "Options", JOptionPane.YES_NO_OPTION, JOptionPane.INFORMATION_MESSAGE);

        if (option == JOptionPane.YES_OPTION) {

            query = (DataTypeArtemis) queryList.getSelectedValue();
            queueName = queueText.getText();
            addressName = addressText.getText();
            queryFilter = queryFilterText.getText();
            title = "ActiveMQ Updated " + query.alias();
            try {
                int secs = Integer.parseInt(sleepText.getText());
                if (secs > 0) {
                    sleepSecs = secs;
                }
            } catch (NumberFormatException nfe) {
                logger.error("Invalid number  for sleepSecs '{}', keeping old value {}", sleepText.getText(), sleepSecs);
            }
            for (int ti = 0; ti < DisplayDataArtemis.thread.length; ti++) {
                DisplayDataArtemis.instances[ti].constructTitle();
                // ensure that we do not wait for the current sleep() to expire for refresh
                DisplayDataArtemis.instances[ti].immediateRefreshRequested=true;
                DisplayDataArtemis.thread[ti].interrupt();
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

}
