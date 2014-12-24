package com.freedomotic.plugins.devices.resoldl2;

import com.freedomotic.api.EventTemplate;
import com.freedomotic.api.Protocol;
import com.freedomotic.events.ProtocolRead;
import com.freedomotic.exceptions.PluginShutdownException;
import com.freedomotic.exceptions.PluginStartupException;
import com.freedomotic.exceptions.PluginRuntimeException;
import com.freedomotic.exceptions.UnableToExecuteException;
import com.freedomotic.reactions.Command;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.SocketException;
import java.net.URL;
import java.util.ArrayList;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * A sensor device for the VBus web device DL2 by Resol which is a Web interface and
 * data-logger for Resol solar controllers.
 * www.setfirelabs.com
 * @author Jonathan D Clark
 */
public class ResolDL2 extends Protocol {

    private final static Logger LOG = LoggerFactory.getLogger(ResolDL2.class.getName());
    private static ArrayList<Board> boards = null;
    private static int BOARD_NUMBER;

    /**
     * Initializations
     */
    public ResolDL2() {
        super("Resol DL2", "/resol-dl2/manifest.xml");
    }

    private void loadBoards() {
        if (boards == null) {
            boards = new ArrayList<Board>();

            LOG.debug("Loading plugin config..");
            for (int i = 0; i < BOARD_NUMBER; i++) {
                String ipToQuery = configuration.getTuples().getStringProperty(i, "ip-to-query", "");
                int portToQuery = configuration.getTuples().getIntProperty(i, "port-to-query", 80);
                boolean logSourceData = configuration.getTuples().getBooleanProperty(i, "log_source_data", false);

                LOG.info("Loading config for Resol DL2 device: {}", ipToQuery + ":" + portToQuery);

                Board board = new Board(ipToQuery, portToQuery, logSourceData);
                boards.add(board);

                // see what config we have for the devices..
                HashMap tupleSet = configuration.getTuples().getTuple(i);
                Iterator iter = tupleSet.keySet().iterator();

                while (iter.hasNext()) {

                    String hashKey = (String) iter.next();
                    String hashVal = (String) tupleSet.get(hashKey);

                    LOG.debug("Found key: {}", hashKey);
                    LOG.debug("Found value: {}", hashVal);
                    if (hashKey.startsWith("device")) {

                        Pattern pattern = Pattern.compile("device(\\d+)\\.([^\\d]+)((\\d+)\\.(.*))?");
                        Matcher matcher = pattern.matcher(hashKey);

                        if (matcher.matches()) {
                            Integer deviceNo = Integer.parseInt(matcher.group(1));

                            if ("name".equals(matcher.group(2))) {
                                LOG.debug("Setting device name for id={} to: {}", deviceNo.toString(), hashVal);
                                board.setDeviceName(deviceNo, hashVal);
                            } else if ("id".equals(matcher.group(2))) {
                                LOG.debug("Resol device id={}", hashVal);
                                board.setDeviceId(deviceNo, Integer.parseInt(hashVal));
                            } else if ("sensor".equals(matcher.group(2))) {
                                Integer sensorNo = Integer.parseInt(matcher.group(4));
                                LOG.debug("Sensor id={} element: {}", matcher.group(4), matcher.group(5));
                                if ("index".equals(matcher.group(5))) {
                                    board.setSensorIndex(i, sensorNo, Integer.parseInt(hashVal));
                                } else if ("name".equals(matcher.group(5))) {
                                    board.setSensorName(i, sensorNo, hashVal);
                                } else {
                                    LOG.warn("Bad config descriptor: {}", hashKey);
                                }
                            }

                        } else {
                            LOG.warn("Bad config descriptor: {}", hashKey);
                        }

                    }

                    iter.remove();
                }
            }
        }
    }

    @Override
    public void onStart() throws PluginStartupException {

        BOARD_NUMBER = configuration.getTuples().size();

        if (BOARD_NUMBER == 0) {
            throw new PluginStartupException("ResolDL2 plugin failed to start: configuration not found or missing source config.");
        } else {
            
            if (BOARD_NUMBER == 1) {
                setDescription("Collecting from 1 device.");
                LOG.info("Starting ResolDL2 plugin for 1 device.");
            } else {
                setDescription("Collecting from " + BOARD_NUMBER + " devices.");
                LOG.info("Starting ResolDL2 plugin for {} devices.", BOARD_NUMBER);
            }
            
            // super.onStart();
            setPollingWait(configuration.getIntProperty("polling-time", 10000));
            loadBoards();
        }
    }

    @Override
    public void onStop() throws PluginShutdownException {
        LOG.debug("Stopping ResolDL2 plugin..");
        setPollingWait(-1); //disable polling
        //display the default description
        setDescription(configuration.getStringProperty("description", ""));
        LOG.info("ResolDL2 plugin stopped.");
    }

    @Override
    protected void onRun() throws PluginRuntimeException {

        for (Board board : boards) {
            getData(board);
        }
    }

    private static String readUrl(String urlString) throws Exception {
        BufferedReader reader = null;
        try {
            URL url = new URL(urlString);
            reader = new BufferedReader(new InputStreamReader(url.openStream()));
            StringBuffer buffer = new StringBuffer();
            int read;
            char[] chars = new char[1024];
            while ((read = reader.read(chars)) != -1) {
                buffer.append(chars, 0, read);
            }

            return buffer.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private void getData(Board board) {
        // retrieve JSON from the device

        String statusFileURL = null;
        ResolData resolStatus;
        String jsonContent = null;
        Boolean getFailed = false;
        try {
            statusFileURL = "http://" + board.getIpAddress() + ":"
                    + Integer.toString(board.getPort()) + "/dl2/download/download?source=current&output_type=json";
            LOG.info("ResolDL2 module getting data from: {}", statusFileURL);
            jsonContent = readUrl(statusFileURL);
            Gson gson = new Gson();

            resolStatus = gson.fromJson(jsonContent, ResolData.class);

            if (board.getLogSourceData()) {
                LOG.info("Received source data: {}", jsonContent);
            }

            LOG.info("Resol DL2 devices={}", resolStatus.devices());

            for (int deviceNo = 0; deviceNo < resolStatus.devices(); deviceNo++) {

                String boardAddress = board.getIpAddress() + ":" + board.getPort() + ":";

                // do we have pre-defined values to retrieve?
                if (board.getDeviceConfigSensors(deviceNo) > 0) {
                    LOG.debug("Getting {} sensors..", board.getDeviceConfigSensors(deviceNo));
                    for (int sensorNo = 1; sensorNo <= board.getDeviceConfigSensors(deviceNo); sensorNo++) {

                        String sensorName = board.getSensorName(deviceNo, sensorNo);

                        int sensorIndex = board.getSensorIndex(deviceNo, sensorNo);
                        Double sensorValue = resolStatus.getValueByIndex(deviceNo, sensorIndex);

                        LOG.debug("Sensor:{}={}", sensorName, sensorValue);

                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + sensorName);

                        event.addProperty("value", sensorValue.toString());
                        this.notifyEvent(event);

                    }
                } else {
                    // no pre-defined name/indexes to report on,
                    // so we fall-back to temps and relays..

                    LOG.info("Device id: {}, Source: {}, temps: {}, relays: {}",
                            new Object[]{deviceNo,
                                resolStatus.deviceName(deviceNo),
                                resolStatus.temps(deviceNo),
                                resolStatus.relays(deviceNo)});

                    // loop through the temps..
                    for (Integer intTemp = 1; intTemp <= resolStatus.temps(deviceNo); intTemp++) {
                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + "temp" + intTemp.toString());
                        LOG.debug("Sensor:temp{}={}", intTemp, resolStatus.temp(deviceNo, intTemp));
                        event.addProperty("value", resolStatus.temp(deviceNo, intTemp).toString());
                        this.notifyEvent(event);
                    }
                    // loop through the relays..
                    for (Integer intRelay = 1; intRelay <= resolStatus.relays(deviceNo); intRelay++) {
                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + "relay" + intRelay.toString());
                        LOG.debug("Sensor:relay{}={}", intRelay, resolStatus.relay(deviceNo, intRelay));
                        event.addProperty("value", resolStatus.relay(deviceNo, intRelay).toString());
                        this.notifyEvent(event);
                    }
                }

            }
            // if we got to here, all went well.

        } catch (SocketException errMsg) {
            // do we really want to permanently give up here?
            // maybe this is just a transient thing?
            // TODO: maybe count the failures, or failure time?
            LOG.warn("Plugin Resol DL2 connection timed out, no reply from the device at: {}. {}",
                    statusFileURL, errMsg.getLocalizedMessage());
            // this.setDescription("Connection timed out, no reply from the device at " + statusFileURL);
        } catch (JsonSyntaxException ex) {
            LOG.warn("Plugin Resol DL2 JSON parse error: {}. Bad data from {}, Data: {}",
                    new Object[]{ex.getLocalizedMessage(), statusFileURL, jsonContent});
            this.stop();
            // setDescription("Bad data from " + statusFileURL + jsonContent);

        } catch (Exception ex) {
            LOG.warn("Plugin Resol DL2 data collection error: {}", ex.getLocalizedMessage());
            this.stop();
            // setDescription("Unable to connect to " + statusFileURL);
        }
    }

    @Override
    protected boolean canExecute(Command c) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void onEvent(EventTemplate event) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    protected void onCommand(Command c) throws IOException, UnableToExecuteException {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    /* Define a class to contain the JSON output from the device..
     * We are getting the 'current' data but the format is for larger
     * timeframes and so is split into header and data sections..
     */
    static class ResolHeader {

        private String id;
        private String extId;
        private Integer channel;
        private Integer destination_address;
        private Integer source_address;
        private Integer protocol_version;
        private Integer command;
        private Integer length;
        private Integer info;
        private String destination_name;
        private String source_name;
        private ArrayList<ResolHeaderField> fields;
    }

    static class ResolHeaderField {

        private String id;
        private String name;
        private String unit;
    }

    static class ResolHeaderSet {

        private Integer timestamp;
        private ArrayList<ResolHeaderSetPacket> packets;
    }

    static class ResolHeaderSetPacket {

        private Integer headerindex;
        private ArrayList<ResolHeaderSetPacketFieldValue> field_values;
        // private ArrayList<ResolHeaderSetPacketData> data;
    }

    static class ResolHeaderSetPacketFieldValue {

        private Integer field_index;
        private Double raw_value;
        private String value;
    }

    static class ResolHeaderSetPacketData {

        private Integer data;
    }

    static class ResolData {

        private String min_time;
        private String max_time;
        private Integer sieve_interval;
        private Integer headerset_count;
        private Integer unique_header_count;
        private ArrayList<ResolHeader> headers;
        private ArrayList<ResolHeaderSet> headersets;

        @Override
        public String toString() {
            Integer headerSize = headers.size();
            Integer headersetsSize = headersets.size();
            Integer packetsSize = headersets.get(0).packets.size();
            return "Resol DL2 DataObject [headers=" + headerSize.toString()
                    + ", headersets=" + headersetsSize.toString()
                    + ", packets=" + packetsSize.toString()
                    + "]";
        }

        // returns the number of data rows in the data class
        public Integer packets() {
            Integer packetsSize = headersets.get(0).packets.size();
            return packetsSize;
        }

        // returns the number of devices on the VBus
        public Integer devices() {
            Integer headerSize = headers.size();
            return headerSize;
        }

        // returns the name of a particular device
        public String deviceName(int device) {
            return headers.get(device).source_name;
        }

        // returns the number of temps available for the appropriate device..
        public int temps(int device) {
            int count = 0;
            for (int i = 0; i < headers.get(device).fields.size(); i++) {
                if (headers.get(device).fields.get(i).name.startsWith("Temperature")) {
                    count++;
                }
            }
            return count;
        }

        // returns the array index of the temp of the corresponding device, number
        public int tempIndex(int device, Integer tempNo) {
            for (int i = 0; i < headers.get(device).fields.size(); i++) {
                if (headers.get(device).fields.get(i).name.startsWith("Temperature")
                        && headers.get(device).fields.get(i).name.endsWith(tempNo.toString())) {
                    return i;
                }
            }
            return -1;
        }

        // go find a temperature of the corresponding device,index..
        public Double temp(int device, int tempNo) {

            return headersets.get(device).packets.get(0).field_values.get(this.tempIndex(device, tempNo)).raw_value;
        }

        // return the number of relays available for the appropriate device..
        public int relays(int device) {
            // loop through the headers to count the temps..
            int intCount = 0;
            for (int i = 0; i < headers.get(device).fields.size(); i++) {
                if (headers.get(device).fields.get(i).name.startsWith("Pump speed relay")) {
                    intCount++;
                }
            }
            return intCount;
        }

        public Double getValueByIndex(int deviceNo, int index) {
            return headersets.get(deviceNo).packets.get(0).field_values.get(index).raw_value;
        }

        // returns the array index of the relay of the corresponding device, number
        public int relayIndex(int device, Integer relayNo) {
            for (int i = 0; i < headers.get(device).fields.size(); i++) {
                if (headers.get(device).fields.get(i).name.startsWith("Pump speed relay")
                        && headers.get(device).fields.get(i).name.endsWith(relayNo.toString())) {
                    return i;
                }
            }
            return -1;
        }

        public Double relay(int device, int relayNo) {
            return headersets.get(device).packets.get(0).field_values.get(this.relayIndex(device, relayNo)).raw_value;
        }
    }
}
