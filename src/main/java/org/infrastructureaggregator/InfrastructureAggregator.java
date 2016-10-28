package org.infrastructureaggregator;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

import static org.apache.http.protocol.HTTP.USER_AGENT;

/**
 * Created by dmetallidis on 8/26/16.
 */
public class InfrastructureAggregator {


    Map<String, Map<Long,Double>> rawMetrics = new HashMap<String, Map<Long, Double>>();
    Map<String, Map<Long,Double>> compositeMetrics = new HashMap<String, Map<Long, Double>>();

    final static String [] infrastructureMetrics = {"PacketLossFreq"
            , "ConnErrorRate", "PING", "BandwidthInput", "BandwidthOuput",
    "DriveSda1", "DriveSda2", "DiskUtilization", "RAMUtilization", "CPULoad"};

    Map<Long,Double> checkHypervisor = new HashMap<Long, Double>();
    Map<Long,Double> checkVirtualCPUs = new HashMap<Long, Double>();

    public static void main(String[] args) throws Exception {


        InfrastructureAggregator http = new InfrastructureAggregator();
        KairosDbClient client = new KairosDbClient("http://localhost:8088/");
//        KairosDbClient centralKairosDbClient = new KairosDbClient("http://147.52.82.63:8088/");
        KairosDbClient centralKairosDbClient = new KairosDbClient("http://" +args[0]+ ":8088/");


        client = client.initializeFullBuilder(client);
        centralKairosDbClient = centralKairosDbClient.initializeFullBuilder(centralKairosDbClient);

        while(true){
        System.out.println("Send Nagios Http GET request");
        //simple GET of Request Completion Time
        http.rawMetricCalculation();
        http.compositeMetricCalculation();
        http.printCompositeMetricToBePut();
        http.putIntoKairosDB(client);
        Thread.sleep(10000);
        http.putIntoKairosDB(centralKairosDbClient);
        Thread.sleep(60000);
        }


    }

    private  void printCompositeMetricToBePut() {

        for ( Map.Entry E : compositeMetrics.entrySet()){
            System.out.println("For the metric of : " + E.getKey() + " we have the below values  -->");
            Map<Long,Double> map = (Map<Long, Double>) E.getValue();
            for( Map.Entry e : map.entrySet())
            {
                System.out.println("Key :" + e.getKey() + " and value: " + e.getValue());
            }
        }

    }


    // HTTP GET request
    private void rawMetricCalculation() throws Exception {

        checkHypervisors();
        checkVirtualCPUs();

        for(int i =0 ; i<infrastructureMetrics.length; i++) {
            String url = "http://localhost/nagios/cgi-bin/statusjson.cgi?query=service&hostname=localhost&servicedescription=" + infrastructureMetrics[i];
            String metricResultPerfData;

            URL obj = new URL(url);
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestProperty("Authorization", "Basic " + encodeBase64("nagiosadmin" + ":" + "nagios"));

            // optional default is GET
            con.setRequestMethod("GET");

            //add request header
            con.setRequestProperty("User-Agent", USER_AGENT);

            int responseCode = con.getResponseCode();
            System.out.println("\nSending 'GET' request to URL : " + url);
            System.out.println("Response Code : " + responseCode);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuffer response = new StringBuffer();

            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            //print result
//        System.out.printf(response.toString());
            String nonApplicable = response.toString().replace("\"host_name\": \"localhost\",", "");
            String nonApplicable2 = nonApplicable.replace("\"description\": \""+infrastructureMetrics[i]+"\",", "");
            JSONObject jsonObj = new JSONObject(nonApplicable2.toString());
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            String json = gson.toJson(jsonObj);
            System.out.println(json);

            // in case of the mean packetLossFrequency
            if (jsonObj.getJSONObject("data").getJSONObject("service") != null) {


                JSONObject metricResult = jsonObj.getJSONObject("data").getJSONObject("service");


                if(infrastructureMetrics[i].equals("PacketLossFreq")){
                     metricResultPerfData = metricResult.getString("plugin_output");
                    calculatePacketLossFreq(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("ConnErrorRate")){
                    metricResultPerfData = metricResult.getString("perf_data");
                    calculateConnErrorRate(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("PING")){
                    metricResultPerfData = metricResult.getString("perf_data");
                    calculatePacketTransferTime(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("BandwidthInput")){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateIncomingBandwidth(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("BandwidthOuput")){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateOutcomingBandwidth(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("DriveSda1") || infrastructureMetrics[i].equals("DriveSda2") ){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateHardDiskWriteReadSpeed(metricResultPerfData, infrastructureMetrics[i]);
                }

                if(infrastructureMetrics[i].equals("DiskUtilization")){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateDiskUtilization(metricResultPerfData);
                }

                if(infrastructureMetrics[i].equals("RAMUtilization")){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateRAMUtilization(metricResultPerfData);
                }


                if(infrastructureMetrics[i].equals("CPULoad")){
                    metricResultPerfData = metricResult.getString("plugin_output");
                    calculateCPUAvgLoad(metricResultPerfData);
                }

            }
        }
    }

    private void putIntoKairosDB(KairosDbClient kairosClient) throws Exception {

        Map<Double, Long> metricsMap;
        String metricName;

        for (Map.Entry e : compositeMetrics.entrySet()) {
            metricName = e.getKey().toString();
            metricsMap = (Map) e.getValue();
            for (Map.Entry p : metricsMap.entrySet()) {
//                String toConvert = p.toString();
////                if(toConvert.contains("=")){
////                    int idx = toConvert.indexOf("=");
////                    toConvert = toConvert.replace('=','\0');
////                    toConvert = toConvert.substring(0,idx-1);
////                }
////                Long lvalue = Long.parseLong(toConvert);
                System.out.println("Metric to be put is : " + metricName);
                kairosClient.putAlreadyInstantiateMetric(metricName, new Date().getTime(), p.getValue());
       //         centralKairosClient.putAlreadyInstantiateMetric(metricName, new Date().getTime(), p.getKey());
            }
        }

    }


    private void compositeMetricCalculation() {


        //calcualtion of mean PacketLossFreq
        double sumPacketLossFreq = 0;
        double valuePacketLossFreq = 0;
        Map<Long, Double> packetLossFreq  =  rawMetrics.get("PacketLossFreq");
        for(Map.Entry e : packetLossFreq.entrySet())
        {
            valuePacketLossFreq =  Double.parseDouble(e.getValue().toString());
            sumPacketLossFreq = sumPacketLossFreq + valuePacketLossFreq;
        }
        Map<Long,Double> mapMeanPacketLoss = new HashMap<Long, Double>();
        mapMeanPacketLoss.put(new Date().getTime(), sumPacketLossFreq/packetLossFreq.size());
        //put of meanPacketLossFreq
        compositeMetrics.put("meanPacketLossFreq", mapMeanPacketLoss);



        //calculation of maxConnectionRate
        Map<Long, Double> connErrorRate  =  rawMetrics.get("ConnErrorRate");
        Map.Entry<Long,Double> maxEntry = null;
        for(Map.Entry<Long,Double> e : connErrorRate.entrySet())
        {
            if(maxEntry == null || e.getValue().compareTo(maxEntry.getValue()) >0 )
            maxEntry = e;
        }
        Map<Long,Double> maxConnectionErrorRate = new HashMap<Long, Double>();
        maxConnectionErrorRate.put(new Date().getTime(), maxEntry.getValue());
        compositeMetrics.put("maxConnectionErrorRate", maxConnectionErrorRate);


        // packetTransferTIme
        double sumPacketTransferTime = 0;
        double valuePacketTransferTIme = 0;
        Map<Long, Double> packetTransferTime  =  rawMetrics.get("packetTransferTime");
        for(Map.Entry e : packetTransferTime.entrySet())
        {
            valuePacketTransferTIme =  Double.parseDouble(e.getValue().toString());
            sumPacketTransferTime = sumPacketTransferTime + valuePacketTransferTIme;
        }
        Map<Long,Double> mapMeanPacketTransferTime = new HashMap<Long, Double>();
        mapMeanPacketTransferTime.put(new Date().getTime(), sumPacketTransferTime/packetTransferTime.size());
        compositeMetrics.put("meanPacketTransferTime", rawMetrics.get("packetTransferTime"));



        //calculation of MaxIncomingBandwidth
        Map<Long, Double> incommingBandwidth  =  rawMetrics.get("incomingBandwidth");
        Map.Entry<Long,Double> maxinbandEntry = null;
        for(Map.Entry<Long,Double> e : incommingBandwidth.entrySet())
        {
            if(maxinbandEntry == null || e.getValue().compareTo(maxinbandEntry.getValue()) >0 )
                maxinbandEntry = e;
        }
        Map<Long,Double> maxIncomingBandwidth = new HashMap<Long, Double>();
        maxIncomingBandwidth.put(new Date().getTime(), maxinbandEntry.getValue());
        compositeMetrics.put("maxIncomingBandwidth", maxIncomingBandwidth);


        //calculation of minIncomingBandwidth
        Map<Long, Double> minIncommingBandwidth  =  rawMetrics.get("incomingBandwidth");
        Map.Entry<Long,Double> mininbandEntry = null;
        for(Map.Entry<Long,Double> e : minIncommingBandwidth.entrySet())
        {
            if(mininbandEntry == null ||  mininbandEntry.getValue() > e.getValue() )
                mininbandEntry = e;
        }
        Map<Long,Double> minIncomingBandwidth = new HashMap<Long, Double>();
        minIncomingBandwidth.put(new Date().getTime(), mininbandEntry.getValue());
        compositeMetrics.put("minIncomingBandwidth", minIncomingBandwidth);

        // calculation of hypervisor,virtualCPUs and outcomingbandwidth
        calculateOutcomingBandwidth();
        compositeMetrics.put("checkHypervisor", checkHypervisor);
        compositeMetrics.put("checkVirtualCPUs", checkHypervisor);

        //multiCpuAvgLoad
        compositeMetrics.put("multiCpuAvgLoad", rawMetrics.get("cpuAvgLoad"));

        //calculation of min/max cpuLoad
        calculationCpuLoadMinMax();
        compositeMetrics.put("avgRamUtilization", rawMetrics.get("ramUtilization"));

        //calculation of mean max min hard disk speed write/read
        calculateMeanMaxMinHardDiskSpeedSda1("Write");
        calculateMeanMaxMinHardDiskSpeedSda1("Read");

        calculateMeanMaxMinHardDiskSpeedSda2("Write");
        calculateMeanMaxMinHardDiskSpeedSda2("Read");
        //disk Utilization put
        compositeMetrics.put("hardDiskUtilization", rawMetrics.get("diskUtilization"));
    }

    // we have done the right convertions in order to have the appropriate
    // numbers in the output.
    private void calculateMeanMaxMinHardDiskSpeedSda2(String operation) {

        //calcualtion of mean HardDiskSpeed
        double sum = 0;
        double valuePacketLossFreq = 0;
        Map<Long, Double> speed  =  rawMetrics.get("DriveSda2"+operation);
        for(Map.Entry e : speed.entrySet())
        {
            valuePacketLossFreq =  Double.parseDouble(e.getValue().toString());
            sum = sum + valuePacketLossFreq;
        }
        Map<Long,Double> mapSpeed = new HashMap<Long, Double>();
        mapSpeed.put(new Date().getTime(), (sum/speed.size())/100);
        //put of meanPacketLossFreq
        compositeMetrics.put("meanHardDiskSda2"+operation+"Speed", mapSpeed);


        //calculation of max Hard Disk Speed
        Map<Long, Double> maxmultiHardDisk  =  rawMetrics.get("DriveSda2"+operation);
        Map.Entry<Long,Double> maxinbandEntry = null;
        for(Map.Entry<Long,Double> e : maxmultiHardDisk.entrySet())
        {
            if(maxinbandEntry == null || e.getValue().compareTo(maxinbandEntry.getValue()) >0 )
                maxinbandEntry = e;
        }
        Map<Long,Double> mapMaxmultiHardDisk = new HashMap<Long, Double>();
        mapMaxmultiHardDisk.put(new Date().getTime(), maxinbandEntry.getValue()/100);
        compositeMetrics.put("maxHardDiskSda2"+operation+"Speed", mapMaxmultiHardDisk);


        //calculation of min Hard Disk Speed
        Map<Long, Double> maxmultiMinHardDisk  =  rawMetrics.get("DriveSda2"+operation);
        Map.Entry<Long,Double> mininbandEntry = null;
        for(Map.Entry<Long,Double> e : maxmultiMinHardDisk.entrySet())
        {
            if(mininbandEntry == null ||  mininbandEntry.getValue() > e.getValue() )
                mininbandEntry = e;
        }
        Map<Long,Double> mapMinmultiMinHardDisk = new HashMap<Long, Double>();
        mapMinmultiMinHardDisk.put(new Date().getTime(), mininbandEntry.getValue()/100);
        compositeMetrics.put("minHardDiskSda2"+operation+"Speed", mapMinmultiMinHardDisk);
    }


    private void calculateMeanMaxMinHardDiskSpeedSda1(String operation) {

        //calcualtion of mean HardDiskSpeed
        double sum = 0;
        double valuePacketLossFreq = 0;
        Map<Long, Double> speed  =  rawMetrics.get("DriveSda1"+operation);
        for(Map.Entry e : speed.entrySet())
        {
            valuePacketLossFreq =  Double.parseDouble(e.getValue().toString());
            sum = sum + valuePacketLossFreq;
        }
        Map<Long,Double> mapSpeed = new HashMap<Long, Double>();
        mapSpeed.put(new Date().getTime(), sum/speed.size());
        //put of meanPacketLossFreq
        compositeMetrics.put("meanHardDiskSda1"+operation+"Speed", mapSpeed);


        //calculation of max Hard Disk Speed
        Map<Long, Double> maxmultiHardDisk  =  rawMetrics.get("DriveSda1"+operation);
        Map.Entry<Long,Double> maxinbandEntry = null;
        for(Map.Entry<Long,Double> e : maxmultiHardDisk.entrySet())
        {
            if(maxinbandEntry == null || e.getValue().compareTo(maxinbandEntry.getValue()) >0 )
                maxinbandEntry = e;
        }
        Map<Long,Double> mapMaxmultiHardDisk = new HashMap<Long, Double>();
        mapMaxmultiHardDisk.put(new Date().getTime(), maxinbandEntry.getValue());
        compositeMetrics.put("maxHardDiskSda1"+operation+"Speed", mapMaxmultiHardDisk);


        //calculation of min Hard Disk Speed
        Map<Long, Double> maxmultiMinHardDisk  =  rawMetrics.get("DriveSda1"+operation);
        Map.Entry<Long,Double> mininbandEntry = null;
        for(Map.Entry<Long,Double> e : maxmultiMinHardDisk.entrySet())
        {
            if(mininbandEntry == null ||  mininbandEntry.getValue() > e.getValue() )
                mininbandEntry = e;
        }
        Map<Long,Double> mapMinmultiMinHardDisk = new HashMap<Long, Double>();
        mapMinmultiMinHardDisk.put(new Date().getTime(), mininbandEntry.getValue());
        compositeMetrics.put("minHardDiskSda1"+operation+"Speed", mapMinmultiMinHardDisk);
    }

    private void calculationCpuLoadMinMax() {

        //calculation of multiCpuMaxLoad
        Map<Long, Double> maxmultiCpuMaxLoad  =  rawMetrics.get("cpuAvgLoad");
        Map.Entry<Long,Double> maxinbandEntry = null;
        for(Map.Entry<Long,Double> e : maxmultiCpuMaxLoad.entrySet())
        {
            if(maxinbandEntry == null || e.getValue().compareTo(maxinbandEntry.getValue()) >0 )
                maxinbandEntry = e;
        }
        Map<Long,Double> mapMaxmultiCpuMaxLoad = new HashMap<Long, Double>();
        mapMaxmultiCpuMaxLoad.put(new Date().getTime(), maxinbandEntry.getValue());
        compositeMetrics.put("multiCpuMaxLoad", mapMaxmultiCpuMaxLoad);


        //calculation of minIncomingBandwidth
        Map<Long, Double> minmultiCpuMaxLoad  =  rawMetrics.get("cpuAvgLoad");
        Map.Entry<Long,Double> mininbandEntry = null;
        for(Map.Entry<Long,Double> e : minmultiCpuMaxLoad.entrySet())
        {
            if(mininbandEntry == null ||  mininbandEntry.getValue() > e.getValue() )
                mininbandEntry = e;
        }
        Map<Long,Double> mapMinmultiCpuMaxLoad = new HashMap<Long, Double>();
        mapMinmultiCpuMaxLoad.put(new Date().getTime(), mininbandEntry.getValue());
        compositeMetrics.put("minOutcomingBandwidth", mapMinmultiCpuMaxLoad);


    }

    private void calculateOutcomingBandwidth() {

        //calculation of MaxIncomingBandwidth
        Map<Long, Double> outcommingBandwidth  =  rawMetrics.get("outcomingBandwidth");
        Map.Entry<Long,Double> maxinbandEntry = null;
        for(Map.Entry<Long,Double> e : outcommingBandwidth.entrySet())
        {
            if(maxinbandEntry == null || e.getValue().compareTo(maxinbandEntry.getValue()) >0 )
                maxinbandEntry = e;
        }
        Map<Long,Double> maxOutcomingBandwidth = new HashMap<Long, Double>();
        maxOutcomingBandwidth.put(new Date().getTime(), maxinbandEntry.getValue());
        compositeMetrics.put("maxOutcomingBandwidth", maxOutcomingBandwidth);


        //calculation of minIncomingBandwidth
        Map<Long, Double> minOutcommingBandwidth  =  rawMetrics.get("outcomingBandwidth");
        Map.Entry<Long,Double> mininbandEntry = null;
        for(Map.Entry<Long,Double> e : minOutcommingBandwidth.entrySet())
        {
            if(mininbandEntry == null ||  mininbandEntry.getValue() > e.getValue() )
                mininbandEntry = e;
        }
        Map<Long,Double> minOutcomingBandwidth = new HashMap<Long, Double>();
        minOutcomingBandwidth.put(new Date().getTime(), mininbandEntry.getValue());
        compositeMetrics.put("minOutcomingBandwidth", minOutcomingBandwidth);


    }

    private void calculateCPUAvgLoad(String metricResultPerfData) {
        String cpuAvgLoad = metricResultPerfData.substring(metricResultPerfData.lastIndexOf("load average") + 14, metricResultPerfData.lastIndexOf("load average") + 18);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        double dcpuAvgLoad = Double.parseDouble(cpuAvgLoad);
        metricData.put(new Date().getTime(), dcpuAvgLoad *10); // in order to catch the (%) percentage
        rawMetrics.put("cpuAvgLoad", metricData);
    }

    private void checkVirtualCPUs() throws IOException, JSONException {


        String url = "http://localhost/nagios/cgi-bin/statusjson.cgi?query=service&hostname=localhost&servicedescription=VirtualCPUs";
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestProperty("Authorization", "Basic " + encodeBase64("nagiosadmin" + ":" + "nagios"));

        // optional default is GET
        con.setRequestMethod("GET");

        //add request header
        con.setRequestProperty("User-Agent", USER_AGENT);

        int responseCode = con.getResponseCode();
        System.out.println("\nSending 'GET' request to URL : " + url);
        System.out.println("Response Code : " + responseCode);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        //print result
//        System.out.printf(response.toString());
        String nonApplicable = response.toString().replace("\"host_name\": \"localhost\",", "");
        String nonApplicable2 = nonApplicable.replace("\"description\": \""+"VirtualCPUs"+"\",", "");
        JSONObject jsonObj = new JSONObject(nonApplicable2.toString());
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(jsonObj);
        System.out.println(json);

        if (jsonObj.getJSONObject("data").getJSONObject("service") != null) {


            JSONObject metricResult = jsonObj.getJSONObject("data").getJSONObject("service");
            String virutalCPUs = metricResult.getString("plugin_output");
            double dvirtualCPUs = Double.parseDouble(virutalCPUs);
            checkVirtualCPUs.put(new Date().getTime(), dvirtualCPUs);

        }
    }
    private void checkHypervisors() throws IOException, JSONException {


        String url = "http://localhost/nagios/cgi-bin/statusjson.cgi?query=service&hostname=localhost&servicedescription=HypervisorCheck";
        URL obj = new URL(url);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        con.setRequestProperty("Authorization", "Basic " + encodeBase64("nagiosadmin" + ":" + "nagios"));

        // optional default is GET
        con.setRequestMethod("GET");

        //add request header
        con.setRequestProperty("User-Agent", USER_AGENT);

        int responseCode = con.getResponseCode();
        System.out.println("\nSending 'GET' request to URL : " + url);
        System.out.println("Response Code : " + responseCode);

        BufferedReader in = new BufferedReader(
                new InputStreamReader(con.getInputStream()));
        String inputLine;
        StringBuffer response = new StringBuffer();

        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();

        //print result
//        System.out.printf(response.toString());
        String nonApplicable = response.toString().replace("\"host_name\": \"localhost\",", "");
        String nonApplicable2 = nonApplicable.replace("\"description\": \""+"HypervisorCheck"+"\",", "");
        JSONObject jsonObj = new JSONObject(nonApplicable2.toString());
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        String json = gson.toJson(jsonObj);
        System.out.println(json);

        if (jsonObj.getJSONObject("data").getJSONObject("service") != null) {


            JSONObject metricResult = jsonObj.getJSONObject("data").getJSONObject("service");
            String metricResultPerfData = metricResult.getString("plugin_output");
            if(metricResultPerfData.contains("VMware"))
                checkHypervisor.put(new Date().getTime(), 1.0);
                else if (metricResultPerfData.contains("xen"))
                checkHypervisor.put(new Date().getTime(), 2.0);
                else if (metricResultPerfData.contains("kvm"))
                checkHypervisor.put(new Date().getTime(), 3.0);
            else
                checkHypervisor.put(new Date().getTime(), 4.0);

        }
    }

    private void calculateRAMUtilization(String metricResultPerfData) {

        String ramUtil = metricResultPerfData.substring(metricResultPerfData.lastIndexOf("used") - 4, metricResultPerfData.lastIndexOf("used") - 2);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        double dramUtil = Double.parseDouble(ramUtil);
        metricData.put(new Date().getTime(), dramUtil);
        rawMetrics.put("ramUtilization", metricData);

    }

    private void calculateDiskUtilization(String metricResultPerfData) {
        String diskUtil = metricResultPerfData.substring(metricResultPerfData.lastIndexOf("utilization") + 12, metricResultPerfData.length()-1);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        double ddiskUtil = Double.parseDouble(diskUtil);
        metricData.put(new Date().getTime(), ddiskUtil);
        rawMetrics.put("diskUtilization", metricData);
    }


    private void calculateHardDiskWriteReadSpeed(String metricResultPerfData, String infrastructureMetric) {
        String readKbs = metricResultPerfData.substring(metricResultPerfData.lastIndexOf("KB_read/s=") + 10,metricResultPerfData.lastIndexOf("KB_read/s=") +15);
        Double dreadKbs = Double.parseDouble(readKbs);
        Map<Long,Double> readMetricData = new HashMap<Long, Double>();
        readMetricData.put(new Date().getTime(), dreadKbs);
        rawMetrics.put(infrastructureMetric+"Read", readMetricData);// metric name like DriveSda1Read

        String writeKbs = metricResultPerfData.substring(metricResultPerfData.lastIndexOf("KB_written/s=") + 13,metricResultPerfData.lastIndexOf("KB_written/s=") + 17);
        Double dwriteKbs = Double.parseDouble(writeKbs);
        Map<Long,Double> writeMetricData = new HashMap<Long, Double>();
        writeMetricData.put(new Date().getTime(), dwriteKbs);
        rawMetrics.put(infrastructureMetric+"Write", writeMetricData); // metric name like DriveSda1Write
    }

    private void calculateOutcomingBandwidth(String metricResultPerfData) {

        String bandwidthOutcoming = metricResultPerfData.substring(33,35);
        double dbandwidthOutcoming = Double.parseDouble(bandwidthOutcoming);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        metricData.put(new Date().getTime(), dbandwidthOutcoming);
        rawMetrics.put("outcomingBandwidth", metricData);
    }

    private void calculateIncomingBandwidth(String metricResultPerfData) {

        String bandwidthIncoming = metricResultPerfData.substring(33,35);
        double dbandwidthIncoming = Double.parseDouble(bandwidthIncoming);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        metricData.put(new Date().getTime(), dbandwidthIncoming);
        rawMetrics.put("incomingBandwidth", metricData);
    }

    private void calculatePacketTransferTime(String metricResultPerfData) {

        String transferTime = metricResultPerfData.substring(4,12);
        double dtransferTime = Double.parseDouble(transferTime);
        Map<Long,Double> metricData = new HashMap<Long, Double>();
        metricData.put(new Date().getTime(), dtransferTime);
        rawMetrics.put("packetTransferTime", metricData);
    }

    private void calculateConnErrorRate(String metricResultPerfData) {

        int idx_out_errors = metricResultPerfData.indexOf("out_errors");
        char out_errors = metricResultPerfData.charAt(idx_out_errors+11);

        int idx_in_errors = metricResultPerfData.indexOf("in_errors");
        char in_errors =  metricResultPerfData.charAt(idx_in_errors+10);

        double connectionErrorRate = (Double.parseDouble(String.valueOf(out_errors)) + Double.parseDouble(String.valueOf(in_errors)))/ 2 ;

        Map<Long,Double> metricData = new HashMap<Long, Double>();
        metricData.put(new Date().getTime(), connectionErrorRate);

        rawMetrics.put("ConnErrorRate", metricData);
    }

    private void calculatePacketLossFreq(String metricResultPerfData) {

        int unknownPort = metricResultPerfData.indexOf("went");
        char packetsUnknownPort = metricResultPerfData.charAt(unknownPort - 2);
        int receiveErrors = metricResultPerfData.indexOf("receive errors");
        char packetErrors = metricResultPerfData.charAt(receiveErrors - 2);

        int packetLoss = Integer.parseInt(String.valueOf(packetsUnknownPort)) + Integer.parseInt(String.valueOf(packetErrors));

        Map<Long,Double> metricData = new HashMap<Long, Double>();
        double dvalue = Double.parseDouble(String.valueOf(packetLoss));
        metricData.put(new Date().getTime(), dvalue);
        rawMetrics.put("PacketLossFreq", metricData);
    }

    String encode3Chars(String in)
    {
        String result = new String();
        String encodingChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        int tripple;
        tripple = in.charAt(0);
        tripple <<= 8;
        if (in.length() >= 2)
            tripple += in.charAt(1);
        tripple <<= 8;
        if (in.length() >= 3)
            tripple += in.charAt(2);

        for (int outputIndex=0; outputIndex<4; outputIndex++)
        {
            int ecIndex = tripple % 64;
            result = encodingChars.substring(ecIndex, ecIndex + 1) + result;
            tripple /= 64;
        }

        return result;
    }
    public String encodeBase64(String input)
    {
        String output = new String();
        int inputLength = input.length(), index = 0;

        while(inputLength > 0)
        {
            output += encode3Chars(input.substring(index, index + Math.min(3, inputLength)));

            index += 3;
            inputLength -= 3;
        }

        return output;
    }



}
