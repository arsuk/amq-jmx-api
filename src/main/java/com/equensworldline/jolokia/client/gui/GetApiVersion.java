package com.equensworldline.jolokia.client.gui;

import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.gui.ShowTable;
import com.equensworldline.amq.utils.json.MyJSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;


public class GetApiVersion {

    // Get ActiveMQ JMS data via the API interface
    private static final Logger logger = LoggerFactory.getLogger(GetApiVersion.class);

    @SuppressWarnings("squid:S106")
    public static void main(String[] args) {

        String host = MyArgs.env("ACTIVEMQ_HOST", "http://localhost:8161/api/jolokia");

        String help = MyArgs.arg(args, "-help", null);

        if (help != null) {
            System.out.println("usage: GetVersion");
            System.out.println("Environment variables:");
            System.out.println("ACTIVEMQ_HOST: default=http://localhost:8161/api/jolokia");
            System.out.println("ACTIVEMQ_USER: admin");
            System.out.println("ACTIVEMQ_PASSWORD: admin");
            System.exit(0);
        }

        try {
            String credentials = MyArgs.env("ACTIVEMQ_USER", "admin") + ":" + MyArgs.env("ACTIVEMQ_PASSWORD", "admin");
            String encoding = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

            URL url = new URL(host);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + encoding);
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) {
                logger.error("Failed : HTTP error code : {}",conn.getResponseCode());
                System.exit(1);
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(
                (conn.getInputStream())));

            String output;
            StringBuilder jsonString = new StringBuilder();
            while ((output = br.readLine()) != null) {
                jsonString.append(output);
            }
            MyJSONObject jo = new MyJSONObject(jsonString.toString());

            System.out.println(jo.toString(4));

            String[] cols = {"Product", "Vendor", "Version"};
            String[][] data = {{jo.findString("product"), jo.findString("vendor"), jo.findString("version")}};

            new ShowTable("Version data", cols, data);

            conn.disconnect();

        } catch (ConnectException | UnknownHostException e) {
            logger.error("Error communicating to {}:",host,e);
            System.exit(1);
        } catch (IOException e) {
            logger.error("Error: ", e);
            System.exit(1);
        }

    }
}
