package com.equensworldline.jolokia.client.logging;

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
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONArray;
import com.equensworldline.amq.utils.json.MyJSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.management.MalformedObjectNameException;

public class LogDataArtemis implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(LogDataArtemis.class);
    private static boolean multiThreaded=false;
    private static int sleepSecs;
    private static DataTypeArtemis query;
    private static String queueName;
    private static String addressName;
    private static String queryFilter;
    private static String trace;
    private static String delChar;
    private static String user;
    private static String password;
    private String host=null;
    private String broker=null;
    private static boolean showCols=true;

    private final String endpoint;
    private final String baseFilename;

    private final J4pClient j4pClient;
    private String filename;
    private boolean showStatusBool = true;
    private SimpleDateFormat timeFormat = new SimpleDateFormat("yy/MM/dd HH:mm:ss");
    
    static final String endpointDefault="http://localhost:8161/console/jolokia/";
    static final String sleepSecsDefault="60";
    static final String userDefault="admin";
    static final String passwordDefault="admin";

    public LogDataArtemis(String endpoint, String baseFilename) {
        try {
            this.j4pClient = J4pClient.url(endpoint)
                    .user(user)
                    .password(password)
                    .authenticator(new BasicAuthenticator().preemptive())
                    .connectionTimeout(3000)
                    .build();
            this.endpoint = endpoint;
            this.baseFilename=baseFilename;
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid endpoint configuration: " + endpoint + " is not a valid URL", e);
        }
    }

    // Post request and log activemq jmx data using the jolokia REST interface
    public static void main(String[] args) {

        // Endpoint is the url of the API - this can be a comma separated list so we can monitor multiple brokers in multiple tasks
        String endpointList = MyArgs.env("ACTIVEMQ_HOST",endpointDefault);

        String endpoints[] = endpointList.split(",");
        multiThreaded = endpoints.length > 1;
        query = DataTypeArtemis.fromAlias(MyArgs.arg(args, 0, DataTypeArtemis.STATUS.alias()));

        sleepSecs = MyArgs.toInt(MyArgs.env("REFRESH_TIME", sleepSecsDefault));

        user = MyArgs.env("ACTIVEMQ_USER",userDefault);
        password = MyArgs.env("ACTIVEMQ_PASWORD",userDefault);
        
        queueName = MyArgs.arg(args, "-queue", "");
        addressName = MyArgs.arg(args, "-address", "");
        queryFilter = MyArgs.arg(args, "-filter", "");
        trace = MyArgs.arg(args, "-trace", null);

        String basefilename = MyArgs.arg(args, "-file", "");
        if ("".equals(basefilename)) {
            basefilename = query + "-" + DateTimeFormatter.ofPattern("yyMMdd-HHmmss").format(OffsetDateTime.now()) + ".csv";
        }

        delChar = MyArgs.env("DELIMITER", "\t");

        boolean help = MyArgs.arg(args, "-help");
        if (!help) help = MyArgs.arg(args, "-h");
        if (!help) help = MyArgs.arg(args, "-?");
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
        		System.err.println("Exiting: "+e);
        		System.exit(1);	// Stop if bad endpoint syntax
        	}
        }
    }

    private static void printUsage() {
        System.out
            .println("usage: java -jar amq-jmx-api-1.1.2-shaded.jar [DisplayDataArtemis|LogDataArtemis] <query> [-help] [-trace] [-queue <qname>] [-topic <tname>] [-filter <filtertext>] [-file <filename>]");
        System.out.print("Queries are: ");
        List<DataTypeArtemis> queries = new ArrayList<>(Arrays.asList(DataTypeArtemis.values()));
        for (Object q: queries.toArray()) {
        	System.out.print(q+" ");
        }
        System.out.println();
        System.out.println("For destination and consumers you must specify a queue or topic");
        System.out.println("The optional file parameter is only used when running for LogData");
        System.out.println("Environment variables:");
        System.out.println("ACTIVEMQ_HOST="+endpointDefault);
        System.out.println("REFRESH_TIME="+sleepSecsDefault);
        System.out.println("DELIMITER='\\t' (TAB)");
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

    public void run() {
    	
		try {
			URL url = new URL(endpoint);
	    	this.host=url.getHost();
		} catch (MalformedURLException e1) {
            logger.error("Malformed endpoint " + endpoint);
            System.exit(1);
		} 
    	
    	broker=getBrokerName();            
        while (broker==null) {
        	// Wait until we have connectivity and the broker name before creating the file and start logging
        	logger.warn("Cannot access "+endpoint);
            try {
                Thread.sleep(sleepSecs * 1000);
            } catch (Exception e) {}
        	broker=getBrokerName(); 
        }

        if (multiThreaded) {
            if (baseFilename.contains("/")) {
                File baseFile = new File(baseFilename);
                String bareFilename = baseFile.getName();
                File path = baseFile.getParentFile();
                if (path == null) {
                    throw new IllegalStateException("Must have a parent path for the file if there is a / in the name");
                }
                this.filename = new File(path, this.host + "-" + bareFilename).getPath();
            } else {
                this.filename = this.host + "-" + baseFilename;
            }
        } else {
            this.filename = baseFilename;
        }

        logger.info("Active broker name for " + endpoint + " is " + broker);
        
        try (PrintStream ps = new PrintStream(filename)) {

            while (true) {

                renderResponse(ps);
                ps.flush();

                try {
                    Thread.sleep(sleepSecs * 1000);
                } catch (Exception e) {}
            }
        } catch (FileNotFoundException e) {
            logger.error("Could not open " + filename);
            System.exit(1);
        } 
    }

    private void renderResponse(PrintStream ps) {
    	{
            if (showStatusBool) {
                logger.info("Query " + query.alias());
            }
            showStatusBool = false;
            
            if (broker==null)
            	broker=getBrokerName();

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
                throw new RuntimeException("Internal error");
            }
        }
    }
    
    private void renderDestination(PrintStream ps) {
    	J4pReadRequest req;
    	J4pReadResponse resp; 
 
    	try {
       		long respTimeMillis=System.currentTimeMillis();
	        // Destinations (Queue(s) or Address(es)) display may purge or reset statistics, which requires an earlier refresh
	    	if (addressName.length()>0)
	    		req = new J4pReadRequest(
	    			"org.apache.activemq.artemis:address=\""+addressName+"\",broker=\""+broker+"\",component=addresses");
	    	else
	    		req = new J4pReadRequest(
	    				"org.apache.activemq.artemis:address=\""+queueName+"\",broker=\""+broker+"\",component=addresses,queue=\""+queueName+"\",routing-type=\"anycast\",subcomponent=queues");
	        resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
	        Collection<String> attribute=resp.getAttributes();

            if (showCols) {
    	        for (String attr : attribute) {
                    writeField(ps, attr, false);              		
            	}
                writeField(ps, "Timestamp", false);
                writeField(ps, "QueryTime", true);
                showCols=false;
            }
            
	        for (String attr : attribute) {

	            Object v=resp.getValue(attr);
			    if (v==null)
			    	writeField(ps, "", false);
			    else
			    	writeField(ps, v.toString(), false);	            
			}
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);
    	} catch (RuntimeException e) {
			logger.warn("Query error "+e);
        } catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			e.printStackTrace();
		}
    	
    }

    private void renderConnections(PrintStream ps) {
    	J4pExecRequest ereq;
    	J4pExecResponse eresp=null;  	

       	try {
       		long respTimeMillis=System.currentTimeMillis();
			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"","listConnectionsAsJSON");
            eresp = j4pClient.execute(ereq);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
            
        	String json=eresp.getValue();
        	//System.out.println("HERE "+json);
        	MyJSONArray recs=new MyJSONArray(json);
        	
            while (recs.hasNext()) {
     
                MyJSONObject rec=(MyJSONObject)recs.next();

                if (showCols) {
                	Set<String> cols=rec.keySet();
                	for (String key:cols) {
                        writeField(ps, key, false);              		
                	}
                    writeField(ps, "Timestamp", false);
                    writeField(ps, "QueryTime", true);
                    showCols=false;
                }
            	
                while (rec.hasNext()) {
                	String key=(String)rec.next();
                    writeField(ps, rec.getString(key), false);
                }
                writeField(ps, timeFormat.format(eresp.getRequestDate()), false);
                writeField(ps, Long.toString(respTimeMillis), true);
            }
		} catch (MalformedObjectNameException | J4pException e) {
			logger.warn("Query error "+e);
		}
    }

    private void renderAcceptors(PrintStream ps) {
    	
    	J4pReadResponse resp=null;
    	J4pReadRequest req;
    	
    	try {
       		long respTimeMillis=System.currentTimeMillis();
        	req = new J4pReadRequest("java.lang:type=Threading");
	        resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;

	        String cols[] = {"Name", "Started", "Parameters"};
	        if (showCols) {
	        	for(int i=0;i<cols.length;i++) {
	        		if (i<cols.length-1)
	        			writeField(ps, cols[i], false);
	        		else
	        			writeField(ps, cols[i], true);
	        	}
	        	showCols=false;
	        }

	        Map<String, String> value = resp.getValue();
	        
	        int r=0;
	        for (Map.Entry<String,String> row : value.entrySet())  {
	        	// Get value as string and convert it to JSON because I do not want to use the SimpleJSON object method getValueAsJSON
	        	MyJSONObject valueObject=new MyJSONObject(((Object)row.getValue()).toString());
	        	writeField(ps, valueObject.getString("Name"), false);
	        	writeField(ps, valueObject.getString("Started"), false);
	        	writeField(ps, valueObject.getString("Parameters"), true);
	        }
		} catch (MalformedObjectNameException | J4pException e) {
			logger.warn("Query error "+e);;
		}
    }

    private void renderSessions(PrintStream ps) {   	
    	renderSessionTypes(ps,"listAllSessionsAsJSON");
    }

    private void renderConsumers(PrintStream ps) {
    	renderSessionTypes(ps,"listAllConsumersAsJSON");
    }
    
    private void renderProducers(PrintStream ps) {
    	renderSessionTypes(ps,"listProducersInfoAsJSON");
    }

    private void renderSessionTypes(PrintStream ps, String jmxMethod) {
    	
    	J4pExecRequest ereq;
    	J4pExecResponse eresp=null;  	

       	try {
       		long respTimeMillis=System.currentTimeMillis();
			ereq = new J4pExecRequest("org.apache.activemq.artemis:broker=\""+broker+"\"",jmxMethod);
            eresp = j4pClient.execute(ereq);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
            
        	String json=eresp.getValue();
        	//System.out.println("HERE "+json);
        	MyJSONArray recs=new MyJSONArray(json);
        	
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
        	
            while (recs.hasNext()) {
     
                MyJSONObject rec=(MyJSONObject)recs.next();

                if (showCols) {
                	Set<String> cols=rec.keySet();
                	for (String key:cols) {
                		if(!key.contentEquals("metadata"))
                			writeField(ps, key, false);              		
                	}
                    writeField(ps, "Timestamp", false);
                    writeField(ps, "QueryTime", true);
                    showCols=false;
                }
            	
                while (rec.hasNext()) {
                	String key=(String)rec.next();
            		if(!key.contentEquals("metadata"))
    	            	if (key.equals("creationTime"))
    	            		writeField(ps, timeFormat.format(new Date(rec.getLong(key))), false);
    	            	else
    	            		writeField(ps, rec.getString(key), false);
                }
                writeField(ps, timeFormat.format(eresp.getRequestDate()), false);
                writeField(ps, Long.toString(respTimeMillis), true);
            }
		} catch (MalformedObjectNameException | J4pException e) {
			logger.warn("Query error "+e);
		}
    }
    
    private void renderQueues(PrintStream ps) {

    	try {
       		long respTimeMillis=System.currentTimeMillis();
       		J4pReadRequest  req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"", "QueueNames");
       		J4pReadResponse resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
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
	        
	        Collection<String> respQueues=resp.getValue();
	        List<String> queues=new ArrayList<String>();

	        for (String key : respQueues) {
	        	if (key.contains(queryFilter))
	        		queues.add(key);
	        }

	        if(showCols) {
		        for (int i=0;i<cols.length;i++ ) {
		        	writeField(ps, cols[i], false);
		        }
	        	writeField(ps, "Timestamp", false);
	        	writeField(ps, "ResponseTime", true);
	        	showCols=false;
	        }
	        
	        for (String key : queues) {
	            
				try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
					respTimeMillis=System.currentTimeMillis();
					req = new J4pReadRequest("org.apache.activemq.artemis:address=\""+key+"\",broker=\""+broker+"\"," +
												"component=addresses,queue=\""+key+"\",routing-type=\"anycast\",subcomponent=queues");
					J4pReadResponse qresp = j4pClient.execute(req);
			        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
			 
			        for (String attr : cols) {
			            Object v=qresp.getValue(attr);
			            String value;
			            if (v==null)
			            	value = "";
			            else
			            	value = v.toString();
			            writeField(ps, value, false);
			        }
	                writeField(ps, timeFormat.format(resp.getRequestDate()), false);
	                writeField(ps, Long.toString(respTimeMillis), true);	            
					
				} catch (MalformedObjectNameException e) {
					e.printStackTrace();
				} catch (J4pException e) {
					logger.info("Exception for address "+key+" "+e);
				}            
	        }
	    } catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			logger.warn("Query error "+e);
		}
    }
    
    private void renderAddresses(PrintStream ps) {

    	try {
       		long respTimeMillis=System.currentTimeMillis();
       		J4pReadRequest req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"", "AddressNames");
       		J4pReadResponse resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
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
	        		"RoutingTypes"	        	};
	        
	        Collection<String> respAddresses=resp.getValue();
	        List<String> addresses=new ArrayList<String>();

	        for (String key : respAddresses) {
	        	if (key.contains(queryFilter))
	        		addresses.add(key);
	        }

	        if(showCols) {
		        for (int i=0;i<cols.length;i++ ) {
		        	writeField(ps, cols[i], false);
		        }
	        	writeField(ps, "Timestamp", false);
	        	writeField(ps, "ResponseTime", true);
	        	showCols=false;
	        }
	        
	        for (String key : addresses) {
	            
				try { // "org.apache.activemq.artemis:address=*,broker=*,component=addresses,queue=*,*"
					respTimeMillis=System.currentTimeMillis();
					req = new J4pReadRequest("org.apache.activemq.artemis:address=\""+key+"\",broker=\""+broker+"\"," +
							"component=addresses");
					J4pReadResponse aresp = j4pClient.execute(req);
			        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
			 
			        for (String attr : cols) {
			            Object v=aresp.getValue(attr);
			            String value;
			            if (v==null)
			            	value = "";
			            else
			            	value = v.toString();
			            writeField(ps, value, false);
			        }
	                writeField(ps, timeFormat.format(resp.getRequestDate()), false);
	                writeField(ps, Long.toString(respTimeMillis), true);	            
					
				} catch (MalformedObjectNameException e) {
					e.printStackTrace();
				} catch (J4pException e) {
					logger.info("Exception for address "+key+" "+e);
				}            
	        }
	    } catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			logger.warn("Query error "+e);
		}
    }

    private void renderStatus(PrintStream ps) {
    	J4pReadResponse resp=null;
    	J4pReadRequest req;
    	
    	try {
       		long respTimeMillis=System.currentTimeMillis();
			req = new J4pReadRequest("org.apache.activemq.artemis:broker=\""+broker+"\"");
	        resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
	        Collection<String> attribute=resp.getAttributes();

	        if (showCols) {
		        for (String key : attribute) {
		            writeField(ps, key, false);	            
		        };
		        writeField(ps, "Timestamp", false);
		        writeField(ps, "QueryTime", true);
		        showCols=false;
	        }
	        
	        for (String key : attribute) {
	            writeField(ps, resp.getValue(key).toString(), false);	            
	        }
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);
	        
		} catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			logger.warn("Query error "+e);
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
    	J4pReadResponse resp=null;
    	J4pReadRequest req;
    	
    	try {
       		long respTimeMillis=System.currentTimeMillis();
        	req = new J4pReadRequest("java.lang:type=Memory", "HeapMemoryUsage");
	        resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
	        if (showCols) {
	        	renderMemoryHeaders(ps);
	        	showCols=false;
	        }
	        
	        Map<String, Long> value = resp.getValue();
	        long used = value.get("used");
	        long max = value.get("max");
	        float usage = (float) used * 100 / max;
	        
	        writeField(ps, String.format("%d", value.get("init")), false);
	        writeField(ps, String.format("%d", value.get("committed")), false);
	        writeField(ps, String.format("%d", value.get("max")), false);
	        writeField(ps, String.format("%d", value.get("used")), false);
	        writeField(ps, String.format("%f",usage), false);
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);
        
    	} catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			logger.warn("Query error "+e);;
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
    	J4pReadResponse resp=null;
    	J4pReadRequest req;
    	
    	try {
       		long respTimeMillis=System.currentTimeMillis();
        	req = new J4pReadRequest("java.lang:type=Threading");
	        resp = j4pClient.execute(req);
	        respTimeMillis=System.currentTimeMillis()-respTimeMillis;
	        
	        if (showCols) {
	        	renderThreadsHeader(ps);
	        	showCols=false;
	        }
	        
	        Map<String, Long> value = resp.getValue();

	        writeField(ps, value.get("DaemonThreadCount").toString(), false);
	        writeField(ps, value.get("PeakThreadCount").toString(), false);
	        writeField(ps, value.get("ThreadCount").toString(), false);
	        writeField(ps, value.get("TotalStartedThreadCount").toString(), false);
            writeField(ps, timeFormat.format(resp.getRequestDate()), false);
            writeField(ps, Long.toString(respTimeMillis), true);
	        
		} catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (J4pException e) {
			logger.warn("Query error "+e);
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
			logger.warn("Getting broker ID "+e);
		} catch (J4pException e) {
		}
    	return brokerName;
    }

}
