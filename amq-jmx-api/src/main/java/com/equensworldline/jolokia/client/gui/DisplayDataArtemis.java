package com.equensworldline.jolokia.client.gui;

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

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.gui.ShowTable;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;

import javax.management.MalformedObjectNameException;
import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class DisplayDataArtemis implements Runnable {

    private static final String JSON_HTTP_RESULT_CODE = "status";
    private static final Logger logger = LoggerFactory.getLogger(DisplayDataArtemis.class);
    private static Thread[] thread;
    private static DisplayDataArtemis[] instances;
    private static int sleepSecs;
    private static DataTypeArtemis query;
    private static String queueName;
    private static String addressName;
    private static String queryFilter;
    private static String trace;
    private final String endpoint;

    private SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private int[] lastEnqueueCounts;
    private int[] lastDequeueCounts;
    private int[] lastStoreSizes;
    private ShowTable showTable;
    private String title;
    private long respTimeMillis;
    private String broker;
    private boolean resetStatistics = false;
    private boolean purgeDestination = false;
    private boolean showStatusBool = true;
    private J4pClient j4pClient;
    private String lastError="";
    
    static final String endpointDefault="http://localhost:8161/console/jolokia/";
    static final String sleepSecsDefault="10";
    static final String userDefault="admin";
    static final String passwordDefault="admin";

    public DisplayDataArtemis(String endpoint) {
            this.endpoint = endpoint;
            this.respTimeMillis = 0;
    }

    // Post request and display activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // ActiveMQ Host is the url and root jmx path
        String endpoint = MyArgs.env("ACTIVEMQ_HOST", endpointDefault);

        String endpoints[] = endpoint.split(",");

        try {
        	query = DataTypeArtemis.fromAlias(MyArgs.arg(args, 0, DataTypeArtemis.STATUS.alias()));
        } catch (RuntimeException e) {
        	logger.info(e.getMessage());
        	System.exit(1);
        }

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", sleepSecsDefault));

        queueName = MyArgs.arg(args, "-queue", "");
        addressName = MyArgs.arg(args, "-address", "");
        queryFilter = MyArgs.arg(args, "-filter", "");
        trace = MyArgs.arg(args, "-trace", null);

        boolean help = MyArgs.arg(args, "-help");
        if (!help) help = MyArgs.arg(args, "-h");
        if (!help) help = MyArgs.arg(args, "-?");
        if (help) {
            printUsage();
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

    private static void printUsage() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println("DisplayDataArtemis version "+version);
        System.out.println
        ("usage: java -jar amq-jmx-api-1.1.2-shaded.jar [DisplayDataArtemis|LogDataArtemis] <query> [-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filertext>]");
        System.out.print("Querie types are: ");
        List<DataTypeArtemis> queries = new ArrayList<>(Arrays.asList(DataTypeArtemis.values()));
        for (Object q: queries.toArray()) {
        	System.out.print(q+" ");
        }
        System.out.println();
        System.out.println("For destination and consumers you must specify a queue or address");
        System.out.println("Environment variables:");
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
        title = String.format("ActiveMQ Data %s (%s) %s", broker, endpoint, query.alias());
    }

    public void run() {

        constructTitle();

        showTable = new ShowTable(title, new String[]{""}, new String[][]{{""}});

        showTable.add(createMenuBar(), BorderLayout.NORTH);

        lastEnqueueCounts = null;
        lastDequeueCounts = null;
 
        String user = MyArgs.env("ACTIVEMQ_USER", userDefault);
        String password = MyArgs.env("ACTIVEMQ_PASSWORD", passwordDefault);
        
        j4pClient = J4pClient.url(endpoint)
                .user(user)
                .password(password)
                .authenticator(new BasicAuthenticator().preemptive())
                .connectionTimeout(3000)
                .build();

        long nextSleepSecs = sleepSecs;
        while (true) {

        	Object res=executeQuery(j4pClient);

            if (res!=null) {
	            if (showStatusBool) {
	                logger.info("Query success " + query.alias());
                    this.broker = getBrokerName();
                    this.constructTitle();
                }
                showStatusBool = false;
                nextSleepSecs = renderResult(nextSleepSecs, res);          	
            } else {
                showStatusBool = true;
                String cols[] = {"Query", "Error"};
                String data[][] = {{query.alias(), lastError}};
                updateTableDisplay(cols, data);
        	}

            try {
                Thread.sleep(nextSleepSecs * 1000);
            } catch (Exception e) {
                // Interrupted while sleeping, continue with main logic
            }

        }

    }

    private Object executeQuery (J4pClient j4pClient) {
    	J4pReadResponse resp=null;
    	J4pReadRequest req;
    	J4pExecRequest ereq;
    	J4pExecResponse eresp=null;
  	
    	if (broker==null) {
            broker=getBrokerName();
            logger.info("Broker name "+broker);
    	}
    	
    	long now=System.currentTimeMillis();
        try {
            switch (query) {
            case STATUS:
                // Status display may reset statistics, which requires an earlier refresh
            	req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"");
                resp = j4pClient.execute(req);
                break;
            case QUEUES:
                // Queues display may purge or reset statistics, which requires an earlier refresh
            	req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"", "QueueNames");
                resp = j4pClient.execute(req);
                break;
            case ADDRESSES:
                // Addresses display may purge or reset statistics, which requires an earlier refresh
            	req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"", "AddressNames");
                resp = j4pClient.execute(req);
                break;
            case DESTINATION:
                // Destinations (Queue(s) or Address(es)) display may purge or reset statistics, which requires an earlier refresh
            	if (addressName.length()>0)
            		req = new J4pReadRequest(
            			"org.apache.activemq.artemis:address=\""+addressName+"\",broker=\""+broker+"\",component=addresses");
            	else
            		req = new J4pReadRequest(
            				"org.apache.activemq.artemis:address=\""+queueName+"\",broker=\""+broker+"\",component=addresses,queue=\""+queueName+"\",routing-type=\"anycast\",subcomponent=queues");
                resp = j4pClient.execute(req);
                break;
            case SESSIONS:
            	ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","listAllSessionsAsJSON");
                eresp = j4pClient.execute(ereq);
                break;
            case CONSUMERS:
            	ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","listAllConsumersAsJSON");
                eresp = j4pClient.execute(ereq);
                break;
            case PRODUCERS:
            	ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","listProducersInfoAsJSON");
                eresp = j4pClient.execute(ereq);
                break;
            case CONNECTIONS:
            	ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","listConnectionsAsJSON");
                eresp = j4pClient.execute(ereq);
                break;
            case ACCEPTORS:
            	req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\",component=acceptors,name=\"*\"");
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
                throw new RuntimeException("Internal error");
            }
            respTimeMillis=System.currentTimeMillis()-now;
        } catch (RuntimeException e) {
       		logger.error("Exception executing query ",e);
        } catch (MalformedObjectNameException e) {
        	lastError="Malformed name";
		} catch (J4pException e) {
			lastError=e.toString();
			if (lastError.contains("javax.management.InstanceNotFoundException"))
				lastError="Instance not found";
		}

        if (resp!=null)
        	return resp;
        else
        	return eresp;
    }

    private void executeResetStatistics () {
    	// ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
    	J4pExecRequest ereq;
    	J4pExecResponse eresp;
    	try {
   			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","resetAllMessageCounters");
   			eresp = j4pClient.execute(ereq);
   			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","resetAllMessageCounterHistories");
   			eresp = j4pClient.execute(ereq);
		} catch (MalformedObjectNameException e) {
			logger.warn("Reset all syntax error "+e);
		} catch (J4pException e) {
			logger.warn("Reset all error "+e);
		}
    } 

    private void executeResetStatistics (String queue_id) {
    	// ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
    	J4pExecRequest ereq;
    	J4pExecResponse eresp;
    	try {
    		String[] stats= {"resetMessageCounter","resetMessagesAcknowledged","resetMessagesAdded","resetMessagesExpired","resetMessagesKilled"};
    		for (String call: stats) {
    			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\",component=addresses,"+
										"address=\""+queue_id+"\",subcomponent=queues,routing-type=\"anycast\","+
										"queue=\""+queue_id+"\"",call);
    			eresp = j4pClient.execute(ereq);
    		}
		} catch (MalformedObjectNameException e) {
			logger.warn("Reset syntax error "+e);
		} catch (J4pException e) {
			logger.warn("Reset error "+e);
		}
    } 
    
    private void executePurge (String queue_id) {
    	// ,queue=%22instantpayments_anadolu_beneficiary_payment_response%22/removeAllMessages()
    	J4pExecRequest ereq;
    	J4pExecResponse eresp;
    	try {
			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\",component=addresses,"+
										"address=\""+queue_id+"\",subcomponent=queues,routing-type=\"anycast\","+
										"queue=\""+queue_id+"\"","removeAllMessages()");
	        eresp = j4pClient.execute(ereq);
		} catch (MalformedObjectNameException e) {
			logger.warn("Purge syntax error "+e);
		} catch (J4pException e) {
			logger.warn("Purge error "+e);
		}
    }

    private long renderResult(long nextSleepSecs, Object results) {
    	
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
                throw new RuntimeException("Internal error");
            }
        } catch (RuntimeException e) {
            logger.error("Exception while parsing and rendering response",e);
            //logger.error("Response received was >>>\n{}\n<<< end-of-response",json.toString(2));
        }
        return resultingNextSleepSecs;
    }

    private void renderThreads(J4pReadResponse resp) {
    	
        Map<String, Long> value = resp.getValue();

        String cols[] = {"Attribute", "Value"};
        String data[][] = {
            {"DaemonThreadCount", ""+value.get("DaemonThreadCount")},
            {"PeakThreadCount", ""+value.get("PeakThreadCount")},
            {"ThreadCount", ""+value.get("ThreadCount")},
            {"TotalStartedThreadCount", ""+value.get("TotalStartedThreadCount")},
            {"Timetamp", timeFormat.format(resp.getRequestDate())},
            {"QueryTtime", respTimeMillis + " ms"}};
        updateTableDisplay(cols, data);
    }

    private void renderMemory(J4pReadResponse resp) {

        Map<String, Long> value = resp.getValue();
        long used = value.get("used");
        long max = value.get("max");
        float usage = (float) used * 100 / max;
        
        String cols[] = {"Attribute", "Value"}; 
        String data[][] = {
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
    	
        String cols[] = {"Name", "Started", "Parameters"};
        
        Map<String, String> value = resp.getValue();

        String[][] data = new String[value.size()][cols.length];
        
        int r=0;
        for (Map.Entry<String,String> row : value.entrySet())  {
        	// Get value as string and convert it to JSON because I do not want to use the SimpleJSON object method getValueAsJSON
        	MyJSONObject valueObject=new MyJSONObject(((Object)row.getValue()).toString());
            data[r][0] = valueObject.getString("Name");
            data[r][1] = valueObject.getString("Started");
        	data[r][2] = valueObject.getString("Parameters");
        	r++;
        } 

        updateTableDisplay(cols, data);
    }

    private void renderConnections(J4pExecResponse resp) {
 
    	String json=resp.getValue();
    	//System.out.println("HERE "+json);
    	MyJSONArray recs=new MyJSONArray(json);
    	
        String cols[] = {"No Data"};
        String data[][] = {{""}};

        int r=0;
        while (recs.hasNext()) {
 
            MyJSONObject rec=(MyJSONObject)recs.next();
            
        	if (cols.length==1) {
        		cols = new String [rec.length()];
        		data= new String [recs.length()][cols.length]; 
        	}
        	
        	int a=0;
            while (rec.hasNext()) {
            	String key=(String)rec.next();
            	cols[a]=key;
            	if (key.equals("creationTime"))
            		data[r][a]=timeFormat.format(new Date(rec.getLong(key)));
            	else
            		data[r][a]=rec.getString(key);
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

       	String cols[];
		String data[][];

        try {
	        Collection<String> attributeSet=resp.getAttributes();
	        
	        List<String> attributes=new ArrayList<>(attributeSet);
	        
	        Collections.sort(attributes);	// Sort alphabetically
	        
	        if (attributes.remove("Name")) attributes.add(0,"Name");	// Move queue name to front
	        
        	cols = new String []{"Attribute","Value"};
        	data = new String [attributes.size()+2] [cols.length];
	
	        int ai = 0;
	        for (String attr : attributes) {

			    data[ai][0]=attr;
	            Object v=resp.getValue(attr);
			    if (v==null)
			    	data[ai][1] = "";
			    else
			       	data[ai][1] = v.toString();
			    ai = ai + 1;	            
			}
	        data[ai][0] = "Timestamp";
	        data[ai][1] = timeFormat.format(resp.getRequestDate());
	        ai++;
	        data[ai][0] = "QueryTime";
	        data[ai][1] = respTimeMillis + " ms";
        } catch (RuntimeException e) {
        	cols = new String [] {"Query", "Error"};
        	data = new String [][] {{"destination",e.toString()}};
        }

        updateTableDisplay(cols, data);
        return sleepSecs;
    }

    private long renderConsumers(J4pExecResponse resp) {
    	
    	String json=resp.getValue();

    	MyJSONArray recs=new MyJSONArray(json);
    	
        String cols[] = {"No Data"};
        String data[][] = {{""}};

        // Filter out non-matching records
        if (!queryFilter.isEmpty()) {
        	MyJSONArray newrecs=new MyJSONArray();
        	for (int i=0;i<recs.length();i++) {
                MyJSONObject rec=(MyJSONObject)recs.next();
                String key=(String)rec.next();
                if (rec.getString(key).contains(queryFilter))
        			newrecs.put((MyJSONObject) recs.get(i));
        	}
        	recs=newrecs;
        }
        // Create display cols and data
        int r=0;
        while (recs.hasNext()) {
 
            MyJSONObject rec=(MyJSONObject)recs.next();
            
        	if (cols.length==1) {
        		if (rec.get("metadata")==null)
        			cols = new String [rec.length()];
        		else
        			cols = new String [rec.length()-1]; // Ignore session metadata       			
        		data= new String [recs.length()][cols.length]; 
        	}
        	
        	int a=0;
            while (rec.hasNext()) {
            	String key=(String)rec.next();
            	if (!key.equals("metadata")) {// Ignore session metadata - not useful and not always present
	            	cols[a]=key;
	            	if (key.equals("creationTime"))
	            		data[r][a]=timeFormat.format(new Date(rec.getLong(key)));
	            	else
	            		data[r][a]=rec.getString(key);
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
 
        String cols[] = {
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
        String data[][];
        long nextSleepSecs=sleepSecs;

        try {
	        Collection<String> respAddresses=resp.getValue();
	        List<String> addresses=new ArrayList<String>();
	
	        for (String key : respAddresses) {
	        	if (key.contains(queryFilter))
	        		addresses.add(key);
	        }
	        
	        data = new String [addresses.size()][cols.length];

	        List<String>badKeys=new ArrayList<String>();
	        int ci = 0;
	        for (String key : addresses) {
	            
				try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
					J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:address=\""+key+"\",broker=\""+broker+"\"," +
												"component=addresses");
					J4pReadResponse qresp = j4pClient.execute(req);
			 
			        int ai = 0;
			        for (String attr : cols) {
			            cols[ai] = attr;
			            Object v=qresp.getValue(attr);
			            if (v==null)
			            	data[ci][ai] = "";
			            else
			            	data[ci][ai] = v.toString();
			            ai = ai + 1;	            
			        }	            
		            ci = ci + 1;				
				} catch (MalformedObjectNameException e) {
					e.printStackTrace();
				} catch (J4pException e) {
					logger.warn("Exception for "+key+" "+e);
					badKeys.add(key);
				}           	            
	        }
	        if (ci<data.length) {	// Query on row failed so make data smaller
	        	String newData [][] = new String [ci][cols.length];
	        	for (int i=0;i<ci;i++)
	        		newData[i]=data[i];
	        	data=newData;
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
        	cols = new String [] {"Query", "Error"};
        	data = new String [][] {{"destination",e.toString()}};
        }

        updateTableDisplay(cols, data);

        return nextSleepSecs;
    }
    
    private long renderQueues(J4pReadResponse resp) {

        String cols[] = {
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
        String data[][];
        
        long nextSleepSecs=sleepSecs;
        
    	try {
	        Collection<String> respQueues=resp.getValue();
	        List<String> queues=new ArrayList<String>();
	
	        for (String key : respQueues) {
	        	if (key.contains(queryFilter))
	        		queues.add(key);
	        }
	 
	        data = new String [queues.size()][cols.length];

	        List<String>badKeys=new ArrayList<String>();
	        int ci = 0;
	        for (String key : queues) {
	            
				try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
					J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:address=\""+key+"\",broker=\""+broker+"\"," +
												"component=addresses,queue=\""+key+"\",routing-type=\"anycast\",subcomponent=queues");
					J4pReadResponse qresp = j4pClient.execute(req);
			 
			        int ai = 0;
			        for (String attr : cols) {
			            cols[ai] = attr;
			            Object v=qresp.getValue(attr);
			            if (v==null)
			            	data[ci][ai] = "";
			            else
			            	data[ci][ai] = v.toString();
			            ai = ai + 1;	            
			        }
		            ci = ci + 1;
				} catch (MalformedObjectNameException e) {
					e.printStackTrace();
				} catch (J4pException e) {
					if (e.toString().contains("javax.management.InstanceNotFoundException"))
					{
						logger.debug("Exception for "+key+" "+e);
						badKeys.add(key);
					}
					else
						logger.warn("Exception for "+key+" "+e);
				}           	            
	        }
	        if (ci<data.length) {	// Query on row failed so make data smaller
	        	String newData [][] = new String [ci][cols.length];
	        	for (int i=0;i<ci;i++)
	        		newData[i]=data[i];
	        	data=newData;
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
        	cols = new String [] {"Query", "Error"};
        	data = new String [][] {{"destination",e.toString()}};
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
        String cols[] = {"Attribute","Value"};
        String data[][];

        try{
	        	
	        Collection<String> attributeSet=resp.getAttributes();
	        
	        List<String> attributes=new ArrayList<>(attributeSet);
	        
	        Collections.sort(attributes);	// Sort alphabetically
	        
	        data = new String[attributes.size()+2][cols.length];
	        int ci = 0;
	        for (String key : attributes) {
	            data[ci][0] = key;
	            data[ci][1] = resp.getValue(key).toString();
	            ci = ci + 1;	            
	        }
	        data[ci][0] = "Timestamp";
	        data[ci][1] = timeFormat.format(resp.getRequestDate());
	        ci++;
	        data[ci][0] = "QueryTime";
	        data[ci][1] = respTimeMillis + " ms";
	
        } catch (RuntimeException e) {
        	cols = new String [] {"Query", "Error"};
        	data = new String [][] {{"destination",e.toString()}};
        }
        
        updateTableDisplay(cols, data);
        
        return sleepSecs;
    }
    
    private String getBrokerName() {
    	String brokerName=null;

    	try {
    		J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:broker=\"*\"");
			J4pReadResponse resp = j4pClient.execute(req);	        
	        String resultStr=resp.asJSONObject().toString();
	        MyJSONObject obj=new MyJSONObject(resultStr);
	        MyJSONObject value=(MyJSONObject) obj.get("value");
	        Set keys=value.keySet();	// Get set of objects (only one)
	        String keyStr=keys.toString();	// change to text and filter out the broker name (within quotes)
	        brokerName=keyStr.substring(keyStr.lastIndexOf("=") + 1).replace("\"","").replace("]","");
		} catch (MalformedObjectNameException e) {
			logger.warn("Getting broker ID: "+e);
		} catch (J4pException e) {
			logger.error("Get Name failed: " + e);
		}
    	return brokerName;
    }
    
    private void resetOrPurgeSelectedQueues(List<String>queues ) {
    	
    	if(queues.size()!=showTable.getRows()) {
    		logger.warn("Table size selection mismatch "+queues.size()+" "+showTable.getRows());
    		return;
    	}

        int rowcnt = 0;
        int selectedRows[] = showTable.getSelectedRows();
        for (String queueKey: queues ) {
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
                        DisplayDataArtemis.thread[ti].interrupt();
                    }
                }
            }
        };
        JButton resetButton = new JButton("Reset statistics");
        resetButton.addActionListener(al);
        resetButton.setToolTipText("Reset statistics of selected items");
        menuBar.add(resetButton);

        JButton purgeButton = new JButton("Purge destination");
        purgeButton.addActionListener(al);
        purgeButton.setToolTipText("Purge messages of selected items");
        menuBar.add(purgeButton);

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

        if (query==DataTypeArtemis.QUEUES) {
        	int rows[]=showTable.getSelectedRows();
        	if (rows.length==1) {
        		queueName=showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]),0);
        	}
        }
        if (query==DataTypeArtemis.ADDRESSES) {
        	int rows[]=showTable.getSelectedRows();
        	if (rows.length==1) {
        		addressName=showTable.getValue(showTable.getSelectedRowUnsorted(rows[0]),0);
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
