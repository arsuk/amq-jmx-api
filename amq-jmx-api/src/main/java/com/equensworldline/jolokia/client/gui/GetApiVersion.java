package com.equensworldline.jolokia.client.gui;

import com.equensworldline.amq.utils.gui.ShowTable;
import com.equensworldline.amq.utils.MyArgs;
import com.equensworldline.amq.utils.json.MyJSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Base64;


public class GetApiVersion {

    // Get ActiveMQ JMS data via the API interface

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
            String encoding = Base64.getEncoder().encodeToString(credentials.getBytes("UTF-8"));

            URL url = new URL(host);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + encoding);
            conn.setRequestProperty("Accept", "application/json");

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("Failed : HTTP error code : "
                    + conn.getResponseCode());
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(
                (conn.getInputStream())));

            String output;
            // System.out.println("Output from Server .... \n");
            String jsonString = "";
            while ((output = br.readLine()) != null) {
                jsonString = jsonString + output;
                //System.out.println(output);
            }
            MyJSONObject jo = new MyJSONObject(jsonString);

            System.out.println(jo.toString(4));

            String cols[] = {"Product", "Vendor", "Version"};
            String data[][] = {{jo.findString("product"), jo.findString("vendor"), jo.findString("version")}};

            new ShowTable("Version data", cols, data);

            conn.disconnect();

        } catch (MalformedURLException e) {

            e.printStackTrace();

        } catch (java.net.ConnectException e) {
            System.err.println(e + " " + host);
            System.exit(1);

        } catch (java.net.UnknownHostException e) {
            System.err.println(e + " " + host);
            System.exit(1);

        } catch (IOException e) {

            e.printStackTrace();
            System.exit(1);

        }

    }
}
