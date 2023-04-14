package com.equensworldline.jolokia.client.logging;

import com.equensworldline.amq.utils.JMXApiException;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;
import com.equensworldline.jolokia.client.DataTypeArtemis;
import org.jolokia.client.BasicAuthenticator;
import org.jolokia.client.J4pClient;
import org.jolokia.client.exception.J4pException;
import org.jolokia.client.request.J4pExecRequest;
import org.jolokia.client.request.J4pExecResponse;
import org.jolokia.client.request.J4pReadRequest;
import org.jolokia.client.request.J4pReadResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MalformedObjectNameException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class LogDataArtemis implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LogDataArtemis.class);
    private static boolean multiThreaded = false;
    private static int sleepSecs;
    private static DataTypeArtemis query;
    private static String queueName;
    private static String addressName;
    private static String queryFilter;
    private static String delChar;
    private static String user;
    private static String password;
    private static boolean includeJSON=false;
    private String broker = null;
    private static boolean headersWritten = false;

    private final String endpoint;
    private final String baseFilename;

    private final J4pClient j4pClient;
    private boolean showStatusBool = true;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("yy/MM/dd HH:mm:ss");

    private static final String ENDPOINT_DEFAULT = "http://localhost:8161/console/jolokia/";
    private static final String SLEEP_SECS_DEFAULT = "60";
    private static final String USER_DEFAULT = "admin";
    private static final String PASSWORD_DEFAULT = "admin";

    public LogDataArtemis(String endpoint, String baseFilename) {
        this.j4pClient = J4pClient.url(endpoint)
            .user(user)
            .password(password)
            .authenticator(new BasicAuthenticator().preemptive())
            .connectionTimeout(3000)
            .build();
        this.endpoint = endpoint;
        this.baseFilename = baseFilename;
    }

    // Post request and log activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // Endpoint is the url of the API - this can be a comma separated list so we can monitor multiple brokers in multiple tasks
        String endpointList = MyArgs.env("ACTIVEMQ_HOST", ENDPOINT_DEFAULT);

        String[] endpoints = endpointList.split(",");
        multiThreaded = endpoints.length > 1;
        query = DataTypeArtemis.fromAlias(MyArgs.arg(args, 0, DataTypeArtemis.STATUS.alias()));

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", SLEEP_SECS_DEFAULT));

        user = MyArgs.env("ACTIVEMQ_USER", USER_DEFAULT);
        password = MyArgs.env("ACTIVEMQ_PASWORD", USER_DEFAULT);

        queueName = MyArgs.arg(args, "-queue", "");
        addressName = MyArgs.arg(args, "-address", "");
        queryFilter = MyArgs.arg(args, "-filter", "");
        
        includeJSON = MyArgs.arg(args, "-includeJSON");

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
            try {
                Thread t = new Thread(new LogDataArtemis(endpoints[i], basefilename)); // Start threads
                // sharing
                // connection
                t.start();
            } catch (IllegalArgumentException e) {
                logger.error("Exiting: ",e);
                System.exit(1);    // Stop if bad endpoint syntax
            }
        }
    }

    @SuppressWarnings("squid:S106") // CLI usage is proper to System.out
    private static void printUsage() {
        System.out
            .println("usage: java -jar amq-jmx-api-x.x.x-shaded.jar [DisplayDataArtemis|LogDataArtemis] <query> [-help] [-trace] [-queue <qname>] [-address <aname>] [-filter <filtertext>] [-file <filename>] -includeJSON");
        System.out.print("Queries are: ");
        List<DataTypeArtemis> queries = new ArrayList<>(Arrays.asList(DataTypeArtemis.values()));
        for (Object q : queries.toArray()) {
            System.out.print(q + " ");
        }
        System.out.println();
        System.out.println("For a specific destination or consumers you must specify a queue or address");
        System.out.println("If the optional file parameter is not used then the file is <broker>-<query>-<date-tim>.csv");
        System.out.println("Environment variables:");
        System.out.println("ACTIVEMQ_HOST=" + ENDPOINT_DEFAULT);
        System.out.println("REFRESH_TIME=" + SLEEP_SECS_DEFAULT);
        System.out.println("DELIMITER='\\t' (TAB)");
        System.out.println("ACTIVEMQ_USER=" + USER_DEFAULT);
        System.out.println("ACTIVEMQ_PASSWORD=" + PASSWORD_DEFAULT);
        System.exit(0);
    }

    public void run() {

        String host = null;
        try {
            URL url = new URL(endpoint);
            host = url.getHost();
        } catch (MalformedURLException e1) {
            logger.error("Malformed endpoint {}", endpoint);
            System.exit(1);
        }

        broker = getBrokerName();
        while (broker == null) {
            // Wait until we have connectivity and the broker name before creating the file and start logging
            logger.warn("Cannot access {}", endpoint);
            try {
                Thread.sleep(sleepSecs * 1000L);
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for broker ignored",e);
                Thread.currentThread().interrupt();
            }
            broker = getBrokerName();
        }

        String filename;
        if (multiThreaded) {
            if (baseFilename.contains("/")) {
                File baseFile = new File(baseFilename);
                String bareFilename = baseFile.getName();
                File path = baseFile.getParentFile();
                if (path == null) {
                    throw new IllegalStateException("Must have a parent path for the file if there is a / in the name");
                }
                filename = new File(path, host + "-" + bareFilename).getPath();
            } else {
                filename = host + "-" + baseFilename;
            }
        } else {
            filename = baseFilename;
        }

        logger.info("Active broker name for {} is {}", endpoint, broker);

        try (PrintStream ps = new PrintStream(filename)) {
            boolean continueLoop = true;
            while (continueLoop) {

                renderResponse(ps);
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
            logger.error("Could not open {}" , filename,e);
            System.exit(1);
        }
    }

    private void renderResponse(PrintStream ps) {
        if (showStatusBool) {
            logger.info("Query {}", query);
        }
        showStatusBool = false;

        if (broker == null) {
            broker = getBrokerName();
        }

        switch (query) {

        case STATUS:
            renderStatus(ps);
            break;
        case QUEUES:
            renderQueues(ps);
            break;
        case ADDRESSES:
            renderAddresses(ps);
            break;
        case DESTINATION:
            renderDestination(ps);
            break;
        case SESSIONS:
            renderSessions(ps);
            break;
        case CONSUMERS:
            renderConsumers(ps);
            break;
        case PRODUCERS:
            renderProducers(ps);
            break;
        case CONNECTIONS:
            renderConnections(ps);
            break;
        case ACCEPTORS:
            renderAcceptors(ps);
            break;
        case MEMORY:
            renderMemory(ps);
            break;
        case THREADS:
            renderThreads(ps);
            break;
        default:
            throw new JMXApiException("Internal error");
        }
    }

    private void renderDestination(PrintStream ps) {
        J4pReadRequest req;
        J4pReadResponse resp;

        try {
            long respTimeMillis = System.currentTimeMillis();
            // Destinations (Queue(s) or Address(es)) display may purge or reset statistics, which requires an earlier refresh
            if (addressName.length() > 0) {
                req = new J4pReadRequest(
                    "org.apache.activemq.artemis:address=\"" + addressName + "\",broker=\"" + broker + "\",component=addresses");
            } else {
                req = new J4pReadRequest(
                    "org.apache.activemq.artemis:address=\"" + queueName + "\",broker=\"" + broker + "\",component=addresses,queue=\"" + queueName + "\",routing-type=\"anycast\",subcomponent=queues");
            }
            resp = j4pClient.execute(req);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            Collection<String> attribute = resp.getAttributes();

            if (!headersWritten) {
            	writeField(ps, "Broker", false);
                for (String attr : attribute) {
                	if (includeJSON || !attr.endsWith("JSON")) {
                		writeField(ps, attr, false);	
                	}
                }
                writeField(ps, "Timestamp", false);
                writeField(ps, "QueryTime", true);
                markHeadersWritten();
            }
        	writeField(ps, broker, false);
            for (String attr : attribute) {
        		
            	if (includeJSON || !attr.endsWith("JSON")) {
	                Object v = resp.getValue(attr);
	                if (v == null) {
	                    writeField(ps, "null", false);
	                } else {
	                    writeField(ps, v.toString(), false);
	                }
            	}
            }
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        } catch (RuntimeException | MalformedObjectNameException | J4pException e) {
            logger.error("Query error {}", e);
        }

    }

    private void renderConnections(PrintStream ps) {
        J4pExecRequest ereq;
        J4pExecResponse eresp = null;

        try {
            long respTimeMillis = System.currentTimeMillis();
            ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "listConnectionsAsJSON");
            eresp = j4pClient.execute(ereq);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            String json = eresp.getValue();
            MyJSONArray recs = new MyJSONArray(json);

            while (recs.hasNext()) {

                MyJSONObject rec = (MyJSONObject) recs.next();

                if (!headersWritten) {
                	writeField(ps, "Broker", false);
                    Set<String> cols = rec.keySet();
                    for (String key : cols) {
                        writeField(ps, key, false);
                    }
                    writeField(ps, "Timestamp", false);
                    writeField(ps, "QueryTime", true);
                    markHeadersWritten();
                }
            	writeField(ps, broker, false);
                while (rec.hasNext()) {
                    String key = rec.next();
                    writeField(ps, rec.getString(key), false);
                }
                writeField(ps, timeFormat.format(eresp.getRequestDate()), false);
                writeField(ps, Long.toString(respTimeMillis), true);
            }
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}",e);
        }
    }

    private static void markHeadersWritten() {
        headersWritten = true;
    }

    private void renderAcceptors(PrintStream ps) {

        J4pReadResponse resp = null;
        J4pReadRequest req;

        try {
            req = new J4pReadRequest("java.lang:type=Threading");
            resp = j4pClient.execute(req);

            String[] cols = {"Name", "Started", "Parameters"};
            if (!headersWritten) {
                for (int i = 0; i < cols.length; i++) {
                    boolean lastField = i >= cols.length - 1;
                    writeField(ps, cols[i], lastField);
                }
                markHeadersWritten();
            }

            Map<String, Object> value = resp.getValue();

            for (Map.Entry<String, Object> row : value.entrySet()) {
                // Get value as string and convert it to JSON because I do not want to use the SimpleJSON object method getValueAsJSON
                MyJSONObject valueObject = new MyJSONObject(row.getValue().toString());
                writeField(ps, valueObject.getString("Name"), false);
                writeField(ps, valueObject.getString("Started"), false);
                writeField(ps, valueObject.getString("Parameters"), true);
            }
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e,e);
        }
    }

    private void renderSessions(PrintStream ps) {
        renderSessionTypes(ps, "listAllSessionsAsJSON");
    }

    private void renderConsumers(PrintStream ps) {
        renderSessionTypes(ps, "listAllConsumersAsJSON");
    }

    private void renderProducers(PrintStream ps) {
        renderSessionTypes(ps, "listProducersInfoAsJSON");
    }

    private void renderSessionTypes(PrintStream ps, String jmxMethod) {

        J4pExecRequest ereq;
        J4pExecResponse eresp = null;

        try {
            long respTimeMillis = System.currentTimeMillis();
            ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", jmxMethod);
            eresp = j4pClient.execute(ereq);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            String json = eresp.getValue();
            MyJSONArray recs = new MyJSONArray(json);

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

            while (recs.hasNext()) {

                MyJSONObject rec = (MyJSONObject) recs.next();

                if (!headersWritten) {
                	writeField(ps, "Broker", false);
                    Set<String> cols = rec.keySet();
                    for (String key : cols) {
                        if (!key.contentEquals("metadata")) {
                            writeField(ps, key, false);
                        }
                    }
                    writeField(ps, "Timestamp", false);
                    writeField(ps, "QueryTime", true);
                    markHeadersWritten();
                }
            	writeField(ps, broker, false);
                while (rec.hasNext()) {
                    String key = rec.next();
                    if (!key.contentEquals("metadata")) {
                        if ("creationTime".equals(key)) {
                            writeField(ps, timeFormat.format(new Date(rec.getLong(key))), false);
                        } else {
                            writeField(ps, rec.getString(key), false);
                        }
                    }
                }
                writeField(ps, timeFormat.format(eresp.getRequestDate()), false);
                writeField(ps, Long.toString(respTimeMillis), true);
            }
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void renderQueues(PrintStream ps) {

        try {
            J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "QueueNames");
            J4pReadResponse resp = j4pClient.execute(req);

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

            Collection<String> respQueues = resp.getValue();
            List<String> queues = new ArrayList<>();

            for (String key : respQueues) {
                if (key.contains(queryFilter)) {
                    queues.add(key);
                }
            }

            if (!headersWritten) {
            	writeField(ps, "Broker", false);
                for (int i = 0; i < cols.length; i++) {
                    writeField(ps, cols[i], false);
                }
                writeField(ps, "Timestamp", false);
                writeField(ps, "ResponseTime", true);
                markHeadersWritten();
            }

            for (String key : queues) {
            	writeField(ps, broker, false);
                try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
                    long respTimeMillis = System.currentTimeMillis();
                    req = new J4pReadRequest("org.apache.activemq.artemis:address=\"" + key + "\",broker=\"" + broker + "\"," +
                        "component=addresses,queue=\"" + key + "\",routing-type=\"anycast\",subcomponent=queues");
                    J4pReadResponse qresp = j4pClient.execute(req);
                    respTimeMillis = System.currentTimeMillis() - respTimeMillis;

                    for (String attr : cols) {
                        Object v = qresp.getValue(attr);
                        String value;
                        if (v == null) {
                            value = "";
                        } else {
                            value = v.toString();
                        }
                        writeField(ps, value, false);
                    }
                    writeField(ps, timeFormat.format(resp.getRequestDate()), false);
                    writeField(ps, Long.toString(respTimeMillis), true);

                } catch (MalformedObjectNameException | J4pException e) {
                    logger.debug("Exception for queue key {} {}", key, e);
                }
            }
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void renderAddresses(PrintStream ps) {

        try {
            J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"", "AddressNames");
            J4pReadResponse resp = j4pClient.execute(req);

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
                "RoutingTypes"};

            Collection<String> respAddresses = resp.getValue();
            List<String> addresses = new ArrayList<>();

            for (String key : respAddresses) {
                if (key.contains(queryFilter)) {
                    addresses.add(key);
                }
            }

            if (!headersWritten) {
            	writeField(ps, "Broker", false);
                for (int i = 0; i < cols.length; i++) {
                    writeField(ps, cols[i], false);
                }
                writeField(ps, "Timestamp", false);
                writeField(ps, "ResponseTime", true);
                markHeadersWritten();
            }

            for (String key : addresses) {
            	writeField(ps, broker, false);
                try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
                    long respTimeMillis = System.currentTimeMillis();
                    req = new J4pReadRequest("org.apache.activemq.artemis:address=\"" + key + "\",broker=\"" + broker + "\"," +
                        "component=addresses");
                    J4pReadResponse aresp = j4pClient.execute(req);
                    respTimeMillis = System.currentTimeMillis() - respTimeMillis;

                    for (String attr : cols) {
                        Object v = aresp.getValue(attr);
                        String value;
                        if (v == null) {
                            value = "";
                        } else {
                            value = v.toString();
                        }
                        writeField(ps, value, false);
                    }
                    writeField(ps, timeFormat.format(resp.getRequestDate()), false);
                    writeField(ps, Long.toString(respTimeMillis), true);

                } catch (MalformedObjectNameException | J4pException e) {
                    logger.debug("Exception for address key {} {}", key, e);
                }
            }
        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void renderStatus(PrintStream ps) {
        J4pReadResponse resp = null;
        J4pReadRequest req;

        try {
            long respTimeMillis = System.currentTimeMillis();
            req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"" + broker + "\"");
            resp = j4pClient.execute(req);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            Collection<String> attribute = resp.getAttributes();

            if (!headersWritten) {
            	writeField(ps, "Broker", false);
                for (String key : attribute) {
                	if (includeJSON || !key.endsWith("JSON")) 
                		writeField(ps, key, false);
                }
                writeField(ps, "Timestamp", false);
                writeField(ps, "QueryTime", true);
                markHeadersWritten();
            }
        	writeField(ps, broker, false);
            for (String key : attribute) {

            	if (includeJSON || !key.endsWith("JSON")) {
                	String data;
	            	if (resp.getValue(key)==null)
	            		data="null";	// Field might be null - example NodeId
	            	else
	            		data=resp.getValue(key).toString();
	                writeField(ps, data, false);
            	}
            }
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);

        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void renderMemoryHeaders(PrintStream ps) {
        writeField(ps, "HeapMemory init", false);
        writeField(ps, "HeapMemory committed", false);
        writeField(ps, "HeapMemory max", false);
        writeField(ps, "HeapMemory used", false);
        writeField(ps, "HeapMemory used%", false);
        writeField(ps, "Timestamp", false);
        writeField(ps, "QueryTime", true);
    }

    private void renderMemory(PrintStream ps) {
        J4pReadResponse resp = null;
        J4pReadRequest req;

        try {
            long respTimeMillis = System.currentTimeMillis();
            req = new J4pReadRequest("java.lang:type=Memory", "HeapMemoryUsage");
            resp = j4pClient.execute(req);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            if (!headersWritten) {
                renderMemoryHeaders(ps);
                markHeadersWritten();
            }

            Map<String, Long> value = resp.getValue();
            long used = value.get("used");
            long max = value.get("max");
            float usage = (float) used * 100 / max;

            writeField(ps, String.format("%d", value.get("init")), false);
            writeField(ps, String.format("%d", value.get("committed")), false);
            writeField(ps, String.format("%d", value.get("max")), false);
            writeField(ps, String.format("%d", value.get("used")), false);
            writeField(ps, String.format("%f", usage), false);
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);

        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void renderThreadsHeader(PrintStream ps) {

        writeField(ps, "DaemonThreadCount", false);
        writeField(ps, "PeakThreadCount", false);
        writeField(ps, "ThreadCount", false);
        writeField(ps, "TotalStartedThreadCount", false);
        writeField(ps, "Timestamp", false);
        writeField(ps, "QueryTime", true);
    }

    private void renderThreads(PrintStream ps) {
        J4pReadResponse resp = null;
        J4pReadRequest req;

        try {
            long respTimeMillis = System.currentTimeMillis();
            req = new J4pReadRequest("java.lang:type=Threading");
            resp = j4pClient.execute(req);
            respTimeMillis = System.currentTimeMillis() - respTimeMillis;

            if (!headersWritten) {
                renderThreadsHeader(ps);
                markHeadersWritten();
            }

            Map<String, Long> value = resp.getValue();

            writeField(ps, value.get("DaemonThreadCount").toString(), false);
            writeField(ps, value.get("PeakThreadCount").toString(), false);
            writeField(ps, value.get("ThreadCount").toString(), false);
            writeField(ps, value.get("TotalStartedThreadCount").toString(), false);
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);

        } catch (MalformedObjectNameException | J4pException e) {
            logger.warn("Query error {}", e);
        }
    }

    private void writeField(PrintStream output, String fieldValue, boolean lastField) {
        if (lastField) {
            output.println(fieldValue);
        } else {
            output.print(fieldValue);
            output.print(delChar);
        }
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
            logger.warn("Getting broker ID {}", e);
        }
        return brokerName;
    }

}
