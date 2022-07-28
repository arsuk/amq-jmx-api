package com.equensworldline.jolokia.client;

import com.equensworldline.jolokia.client.gui.DisplayData;
import com.equensworldline.jolokia.client.gui.DisplayDataArtemis;
import com.equensworldline.jolokia.client.logging.LogData;
import com.equensworldline.jolokia.client.logging.LogDataArtemis;
import com.equensworldline.jolokia.client.gui.GetApiVersion;

import java.util.Arrays;

public class Main {
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
            System.exit(1);
        }

        switch (args[0]) {
        case "DisplayData":
            DisplayData.main(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "DisplayDataArtemis":
            DisplayDataArtemis.main(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "LogData":
            LogData.main(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "LogDataArtemis":
            LogDataArtemis.main(Arrays.copyOfRange(args, 1, args.length));
            break;
        case "Version":
            GetApiVersion.main(Arrays.copyOfRange(args, 1, args.length));
            break;
        default:
            printUsage();
            System.exit(1);
            break;
        }
    }

    private static void printUsage() {
        String version = Main.class.getPackage().getImplementationVersion();
        System.out.println("AMQ Tool version "+version);
        System.out.println("usage: java -jar amq-jmx-api-"+version+"-shaded.jar [DisplayData|LogData|DisplayDataArtemis|LogDataArtemis|Version] [-help]");
        System.out.println("Two sets of sub-commands are provided: for ActiveMQ and ActiveMQ Artemis");
        System.out.println("Use the -help option with the commands to see the parameters and environment variables");
        System.exit(0);
    }

}
