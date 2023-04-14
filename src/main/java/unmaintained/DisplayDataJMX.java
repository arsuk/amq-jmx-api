package unmaintained;

import com.equensworldline.amq.utils.gui.ShowTable;
//import org.apache.activemq.broker.jmx.BrokerViewMBean;
//import org.apache.activemq.broker.jmx.ConnectionViewMBean;
//import org.apache.activemq.broker.jmx.DestinationViewMBean;
import com.equensworldline.amq.utils.MyArgs;

import javax.management.MBeanServerConnection;
import javax.management.MBeanServerInvocationHandler;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Set;

public class DisplayDataJMX {
/*
    reviving this class requires addition of org.apache.activemq:activemq-broker to the dependencies

    // Post request and display activemq jmx data
    public static void main(String[] args) {

        SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");

        String host = MyArgs.env("ACTIVEMQ_HOST", "localhost:1099");
        String broker = MyArgs.env("ACTIVEMQ_BROKER", "localhost");
        int sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", "5"));
        String query = MyArgs.arg(args, 0, "status");

        String queueName = MyArgs.arg(args, "-queue", "example/myQueue");
        String topicName = MyArgs.arg(args, "-topic", "");
        String filter = MyArgs.arg(args, "-filter", "");
        String trace = MyArgs.arg(args, "-trace", null);
        String help = MyArgs.arg(args, "-help", null);

        long enqueueCount = 0;
        long dequeueCount = 0;
        long oldEnqueueCount = 0;
        long oldDequeueCount = 0;

        if (help != null) {
            System.out.println("usage: DisplayData <query> -help -trace -queue qname -topic tname -filter filertext");
            System.out.println("Queries are: status,connectors,connections,queues,topics,destination,memory");
            System.out.println("Environment variables:");
            System.out.println("ACTIVEMQ_HOST: default=localhost:1099");
            System.out.println("REFRESH_TIME: default=5");
            System.out.println("ACTIVEMQ_USER: admin");
            System.out.println("ACTIVEMQ_PASSWORD: admin");
            System.exit(0);
        }

        ShowTable table = new ShowTable("ActiveMQ Data " + query + ", " + broker, new String[]{""}, new String[][]{
            {""}});

        try {
            String credentials = MyArgs.env("ACTIVEMQ_USER", "admin") + ":" + MyArgs.env("ACTIVEMQ_PASSWORD", "admin");
            String encoding = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"));

            String url = "service:jmx:rmi:///jndi/rmi://" + host + "/jmxrmi";
            // url="service:jmx:rmi:///jndi/rmi://localhost:1616/jmxrmi";

            JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(url));
            MBeanServerConnection connection = connector.getMBeanServerConnection();

            while (true) {

                long timeNanos = System.nanoTime();

                if (query.equals("status")) {

                    ObjectName brokerObj = new ObjectName("org.apache.activemq:type=Broker,brokerName=" + broker);
                    BrokerViewMBean mbView = MBeanServerInvocationHandler.newProxyInstance(connection, brokerObj, BrokerViewMBean.class, true);

                    int ci = 0;
                    String cols[] = {"Attribute", "Value"};

                    String[] attrs = {
                        "BrokerVersion",
                        "Uptime",
                        "MemoryLimit",
                        "MemoryPercentUsage",
                        "CurrentConnections",
                        "TotalConnections",
                        "TotalConsumers",
                        "TotalProducers",
                        "TotalEnqueues",
                        "TotalDequeues",
                        "TotalMessages",
                        "BrokerName",
                        "Slave",
                        "Time",
                        "Response ms"};

                    String data[][] = new String[attrs.length][cols.length + 1];
                    for (int i = 0; i < attrs.length; i++) {
                        data[i][0] = attrs[i];
                    }
                    int i = 1;
                    String coldata[] = {
                        mbView.getBrokerVersion(),
                        mbView.getUptime(),
                        "" + mbView.getMemoryLimit(),
                        "" + mbView.getMemoryPercentUsage(),
                        "" + mbView.getCurrentConnectionsCount(),
                        "" + mbView.getTotalConnectionsCount(),
                        "" + mbView.getTotalConsumerCount(),
                        "" + mbView.getTotalProducerCount(),
                        "" + mbView.getTotalEnqueueCount(),
                        "" + mbView.getTotalDequeueCount(),
                        "" + mbView.getTotalMessageCount(),
                        "" + mbView.getBrokerName(),
                        "" + mbView.isSlave(),
                        timeFormat.format(new Date()),
                        "" + (System.nanoTime() - timeNanos) / 1000000};
                    for (int j = 0; j < coldata.length; j++) {
                        data[j][i] = coldata[j];
                    }
                    table.updateTable(cols, data);
                } else if (query.equals("queues") || query.equals("topics")) {

                    String cols[] = {
                        "Name", "AverageEnqueueTime", "MinEnqueueTime", "MaxEnqueueTime",
                        "ConsumerCount", "ProducerCount", "DequeueCount", "EnqueueCount",
                        "ExpiredCount", "InFlightCount", "MaxMessageSize", "MemoryLimit",
                        "MemoryPercentUsage", "QueueSize", "Time",
                        "Resp. ms"};

                    ArrayList<String[]> al = new ArrayList();

                    ObjectName brokerObj = new ObjectName("org.apache.activemq:type=Broker,brokerName=" + broker);
                    BrokerViewMBean viewMBean = MBeanServerInvocationHandler.newProxyInstance(connection, brokerObj, BrokerViewMBean.class, true);

                    ObjectName[] queueObjects = new ObjectName[0];

                    if (viewMBean != null) {
                        if (query.equals("queues")) {
                            queueObjects = viewMBean.getQueues();
                        } else {
                            queueObjects = viewMBean.getTopics();
                        }
                    }
                    for (ObjectName objectName : queueObjects) {
                        DestinationViewMBean mbView = MBeanServerInvocationHandler.newProxyInstance(connection, objectName, DestinationViewMBean.class, true);

                        if (mbView != null && mbView.getName().contains(filter)) {

                            String tdata[] = {
                                mbView.getName(),
                                "" + mbView.getAverageEnqueueTime(),
                                "" + mbView.getMinEnqueueTime(),
                                "" + mbView.getMaxEnqueueTime(),
                                "" + mbView.getConsumerCount(),
                                "" + mbView.getProducerCount(),
                                "" + mbView.getDequeueCount(),
                                "" + mbView.getEnqueueCount(),
                                "" + mbView.getExpiredCount(),
                                "" + mbView.getInFlightCount(),
                                "" + mbView.getMaxMessageSize(),
                                "" + mbView.getMemoryLimit(),
                                "" + mbView.getMemoryPercentUsage(),
                                "" + mbView.getQueueSize(),
                                timeFormat.format(new Date()),
                                "" + (System.nanoTime() - timeNanos) / 1000000};

                            timeNanos = System.nanoTime();

                            al.add(tdata);
                        }
                    }
                    String[][] data = new String[al.size()][];
                    for (int i = 0; i < al.size(); i++) {
                        data[i] = al.get(i);
                    }
                    table.updateTable(cols, data);
                } else if (query.equals("destination")) {

                    int ci = 0;
                    String cols[] = {"Attribute", "Value"};

                    String attrs[] = {
                        "Name",
                        "AverageEnqueueTime",
                        "AverageBlockedTime",
                        "ConsumerCount",
                        "ProducerCount",
                        "DequeueCount",
                        "EnqueueCount",
                        "ExpiredCount",
                        "DispatchCount",
                        "InFlightCount",
                        "ProducerFlowControl",
                        "BlockedSends",
                        "ExpiredCount",
                        "MaxMessageSize",
                        "MemoryLimit",
                        "MemoryPercentUsage",
                        "QueueSize",
                        "DequeueTPS",
                        "EnqueueTPS",
                        "Time",
                        "Response ms"};

                    String data[][] = new String[attrs.length][2];
                    for (int i = 0; i < attrs.length; i++) {
                        data[i][0] = attrs[i];
                    }
                    int i = 0;
                    // get queue data
                    ObjectName nameConsumers = new ObjectName("org.apache.activemq:type=Broker,brokerName=" + broker + ",destinationType=Queue,destinationName=" + queueName);
                    DestinationViewMBean mbView = MBeanServerInvocationHandler.newProxyInstance(connection, nameConsumers, DestinationViewMBean.class, true);

                    enqueueCount = mbView.getEnqueueCount();
                    dequeueCount = mbView.getDequeueCount();
                    float enqTPS = 0;
                    float deqTPS = 0;
                    if (oldEnqueueCount > 0) {
                        enqTPS = (enqueueCount - oldEnqueueCount) / new Float(sleepSecs);
                    }
                    if (oldDequeueCount > 0) {
                        deqTPS = (dequeueCount - oldDequeueCount) / new Float(sleepSecs);
                    }
                    oldEnqueueCount = enqueueCount;
                    oldDequeueCount = dequeueCount;

                    i = i + 1;
                    String coldata[] = {
                        mbView.getName(),
                        "" + mbView.getAverageEnqueueTime(),
                        "" + mbView.getAverageBlockedTime(),
                        "" + mbView.getConsumerCount(),
                        "" + mbView.getProducerCount(),
                        "" + dequeueCount,
                        "" + enqueueCount,
                        "" + mbView.getExpiredCount(),
                        "" + mbView.getDispatchCount(),
                        "" + mbView.getInFlightCount(),
                        "" + mbView.isProducerFlowControl(),
                        "" + mbView.getBlockedSends(),
                        "" + mbView.getExpiredCount(),
                        "" + mbView.getMaxMessageSize(),
                        "" + mbView.getMemoryLimit(),
                        "" + mbView.getMemoryPercentUsage(),
                        "" + mbView.getQueueSize(),
                        "" + deqTPS,
                        "" + enqTPS,
                        timeFormat.format(new Date()),
                        "" + (System.nanoTime() - timeNanos) / 1000000};
                    for (int j = 0; j < coldata.length; j++) {
                        data[j][i] = coldata[j];
                    }

                    table.updateTable(cols, data);
                } else if (query.equals("connections")) {

                    String cols[] = {
                        "Name", "RemoteAddress", "UserName", "DispatchQueueSize",
                        "ActiveTransactionCount", "ClientId", "Consumers", "Producers"};
                    ArrayList<String[]> al = new ArrayList();

                    ObjectName connectionNames =
                        new ObjectName("org.apache.activemq:type=Broker,brokerName=" +
                            broker + "" +
                            ",connector=clientConnectors,connectorName=*,connectionViewType=clientId,connectionName=*");
                    Set<ObjectName> queryList = connection.queryNames(connectionNames, null);

                    if (queryList != null) {
                        for (Object view : queryList) {
                            String name = getKeyElement(view.toString(), "connectionName");
                            if (name.contains(filter)) {
                                ConnectionViewMBean mbView = MBeanServerInvocationHandler.
                                    newProxyInstance(connection, (ObjectName) view, ConnectionViewMBean.class, true);
                                String tdata[] = {
                                    name,
                                    "" + mbView.getRemoteAddress(),
                                    "" + mbView.getUserName(),
                                    "" + mbView.getDispatchQueueSize(),
                                    "" + mbView.getActiveTransactionCount(),
                                    "" + mbView.getClientId(),
                                    Integer.valueOf(mbView.getConsumers().length).toString(),
                                    Integer.valueOf(mbView.getProducers().length).toString()};
                                al.add(tdata);
                            }
                        }
                    }
                    String[][] data = new String[al.size()][];
                    for (int i = 0; i < al.size(); i++) {
                        data[i] = al.get(i);
                    }
                    table.updateTable(cols, data);

                } else if (query.equals("connectors")) {

                    String cols[] = {"Name", "Parameters"};

                    ArrayList<String[]> al = new ArrayList();


                    ObjectName brokerObj = new ObjectName("org.apache.activemq:type=Broker,brokerName=" + broker);
                    BrokerViewMBean mbView = MBeanServerInvocationHandler.newProxyInstance(connection, brokerObj, BrokerViewMBean.class, true);

                    Map queryList = mbView.getTransportConnectors();

                    if (queryList != null) {
                        for (Object view : queryList.keySet()) {
                            System.out.println("View " + view);

                            String name = view.toString();
                            if (name.contains(filter)) {
                                String tdata[] = {name, "" + queryList.get(view)};
                                al.add(tdata);
                            }
                        }
                    }
                    String[][] data = new String[al.size()][];
                    for (int i = 0; i < al.size(); i++) {
                        data[i] = al.get(i);
                    }
                    table.updateTable(cols, data);
                } else if (query.equals("memory")) {
                    MemoryMXBean memproxy =
                        ManagementFactory.newPlatformMXBeanProxy(connection,
                            ManagementFactory.MEMORY_MXBEAN_NAME,
                            MemoryMXBean.class);
                    MemoryUsage heap = memproxy.getHeapMemoryUsage();
                    MemoryUsage nonheap = memproxy.getNonHeapMemoryUsage();

                    OperatingSystemMXBean proxy =
                        ManagementFactory.newPlatformMXBeanProxy(connection,
                            ManagementFactory.OPERATING_SYSTEM_MXBEAN_NAME,
                            OperatingSystemMXBean.class);

                    String cols[] = {"Attribute", "Value"};
                    String data[][] = {
                        {"HeapMemoryUsed", "" + heap.getUsed()},
                        {"HeapMemoryCommitted", "" + heap.getCommitted()},
                        {"HeapMemoryMax", "" + heap.getMax()},
                        {"HeapMemoryInit", "" + heap.getInit()},
                        {"NonHeapMemoryUsed", "" + nonheap.getUsed()},
                        {"NonHeapMemoryCommitted", "" + nonheap.getCommitted()},
                        {"NonHeapMemoryMax", "" + nonheap.getMax()},
                        {"NonHeapMemoryInit", "" + nonheap.getInit()},
                        {"OperatingSystem", proxy.getName()},
                        {"Version", proxy.getVersion()},
                        {"SystemLoadAverage", "" + proxy.getSystemLoadAverage()},
                        {"AvailableProcessors", "" + proxy.getAvailableProcessors()},
                        {"Architecture", proxy.getArch()},
                        {"Time", timeFormat.format(new Date())},
                        {"Response ms", "" + (System.nanoTime() - timeNanos) / 1000000}};
                    table.updateTable(cols, data);
                } else {
                    System.out.println("Unknown query");
                    System.exit(1);
                }

                try {
                    Thread.sleep(sleepSecs * 1000L);
                } catch (Exception e) {
                }
                ;
            }

        } catch (java.net.ConnectException e) {

            System.out.println("Connection error" + e);
            System.exit(1);

        } catch (java.io.IOException e) {

            System.out.println("Error calling " + host + ", " + e);
            System.exit(1);

        } catch (MalformedObjectNameException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.exit(1);
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            System.exit(1);
        }

    }
*/
    static String getKeyElement(String key, String subKey) {
        String elems[] = key.split(subKey + "=");
        if (elems.length > 1) {
            elems = elems[1].split(",");
        }
        return elems[0];
    }
}
