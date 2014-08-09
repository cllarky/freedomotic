package com.freedomotic.plugins.devices.resoldl2;

import com.freedomotic.api.EventTemplate;
import com.freedomotic.api.Protocol;
import com.freedomotic.app.Freedomotic;
import com.freedomotic.events.ProtocolRead;
import com.freedomotic.exceptions.UnableToExecuteException;
import com.freedomotic.reactions.Command;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * A sensor device for the VBus web device DL2 by Resol which is a Web interface and
 * data-logger for Resol solar controllers.
 * www.setfirelabs.com
 * @author Jonathan D Clark
 */
public class ResolDL2 extends Protocol {

    private static final Logger LOG = Logger.getLogger(ResolDL2.class.getName());
    private static ArrayList<Board> boards = null;
    private static int BOARD_NUMBER;
    private static int POLLING_TIME = 1000;
    private Socket socket = null;
    private DataOutputStream outputStream = null;
    private BufferedReader inputStream = null;
    private String address = null;
    private int SOCKET_TIMEOUT = configuration.getIntProperty("socket-timeout", 1000);

    /**
     * Initializations
     */
    public ResolDL2() {
        super("Resol DL2", "/resol-dl2/manifest.xml");
        setPollingWait(POLLING_TIME);
    }

    private void loadBoards() {
        if (boards == null) {
            boards = new ArrayList<Board>();
        }
        setDescription("Module running, " + BOARD_NUMBER + " sources.");
        LOG.info("Loading Resol DL2 devices..");
        for (int i = 0; i < BOARD_NUMBER; i++) {
            String ipToQuery = configuration.getTuples().getStringProperty(i, "ip-to-query", "192.168.1.201");
            int portToQuery = configuration.getTuples().getIntProperty(i, "port-to-query", 80);
            boolean logSourceData = configuration.getTuples().getBooleanProperty(i, "log_source_data", false);

            address = ipToQuery + ":" + portToQuery;

            LOG.info("Loading Resol DL2 device: " + address);

            Board board = new Board(ipToQuery, portToQuery, logSourceData);
            boards.add(board);

            // see what config we have for the devices..
            HashMap tupleSet = configuration.getTuples().getTuple(i);
            Iterator iter = tupleSet.keySet().iterator();

            while (iter.hasNext()) {

                String hashKey = (String) iter.next();
                String hashVal = (String) tupleSet.get(hashKey);

                // LOG.log(Level.INFO, "found key: {0}", hashKey);
                // LOG.log(Level.INFO, "found value: {0}", hashVal);
                if (hashKey.startsWith("device")) {

                    Pattern pattern = Pattern.compile("device(\\d+)\\.([^\\d]+)((\\d+)\\.(.*))?");
                    Matcher matcher = pattern.matcher(hashKey);

                    if (matcher.matches()) {
                        Integer deviceNo = Integer.parseInt(matcher.group(1));

                        if ("name".equals(matcher.group(2))) {
                            // LOG.log(Level.INFO, "Setting device name for id={0} to: {1}", new Object[]{deviceNo.toString(), hashVal});
                            board.setDeviceName(deviceNo, hashVal);
                        } else if ("id".equals(matcher.group(2))) {
                            // LOG.log(Level.INFO, "Resol device id={0}", hashVal);
                            board.setDeviceId(deviceNo, Integer.parseInt(hashVal));
                        } else if ("sensor".equals(matcher.group(2))) {
                            Integer sensorNo = Integer.parseInt(matcher.group(4));
                            // LOG.log(Level.INFO, "Sensor id={0}element: {1}", new Object[]{matcher.group(4), matcher.group(5)});
                            if ("index".equals(matcher.group(5))) {
                                board.setSensorIndex(i, sensorNo, Integer.parseInt(hashVal));
                            } else if ("name".equals(matcher.group(5))) {
                                board.setSensorName(i, sensorNo, hashVal);
                            } else {
                                LOG.log(Level.WARNING, "Bad config descriptor: {0}", hashKey);
                            }
                        }

                    } else {
                        LOG.log(Level.WARNING, "Bad config descriptor: {0}", hashKey);
                    }

                }

                iter.remove();
            }
        }
    }

    private void disconnect() {
        // close streams and socket
        try {
            inputStream.close();
            outputStream.close();
            socket.close();
        } catch (Exception ex) {
            //do nothing. Best effort
        }
    }

    @Override
    public void onStart() {

        BOARD_NUMBER = configuration.getTuples().size();

        if (BOARD_NUMBER == 0) {
            LOG.warning("ResolDL2 plugin failed: configuration not found.");

        } else {
            POLLING_TIME = configuration.getIntProperty("polling-time", 1000);
            LOG.log(Level.INFO, "Starting ResolDL2 plugin for {0} device(s).", BOARD_NUMBER);
            super.onStart();
            setPollingWait(POLLING_TIME);
            loadBoards();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        LOG.info("Stopping ResolDL2 plugin.");
        //release resources
        boards.clear();
        boards = null;
        setPollingWait(-1); //disable polling
        //display the default description
        setDescription(configuration.getStringProperty("description", "Resol DL2 Data"));
    }

    @Override
    protected void onRun() {
        if (BOARD_NUMBER > 0) {
            for (Board board : boards) {
                getData(board);
            }

            try {
                Thread.sleep(POLLING_TIME);
            } catch (InterruptedException ex) {
                LOG.info("Thread interrupted - shutting down module.");
                LOG.log(Level.SEVERE, null, ex);
            }
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

    private void getData(Board board) {
        // retrieve JSON from the device

        String statusFileURL = null;
        ResolData resolStatus;
        String jsonContent = null;
        try {
            statusFileURL = "http://" + board.getIpAddress() + ":"
                    + Integer.toString(board.getPort()) + "/dl2/download/download?source=current&output_type=json";
            LOG.log(Level.INFO, "ResolDL2 module getting data from {0}", statusFileURL);

            jsonContent = readUrl(statusFileURL);
            Gson gson = new Gson();

            resolStatus = gson.fromJson(jsonContent, ResolData.class);

            if (board.getLogSourceData()) {
                LOG.log(Level.INFO, "Received source data: {0}", jsonContent);
            }

            LOG.log(Level.INFO, "Resol DL2 devices={0}", resolStatus.devices());

            for (int deviceNo = 0; deviceNo < resolStatus.devices(); deviceNo++) {

                String boardAddress = board.getIpAddress() + ":" + board.getPort() + ":";

                // do we have pre-defined values to retrieve?
                if (board.getDeviceConfigSensors(deviceNo) > 0) {
                    //LOG.log(Level.INFO, "Getting {0} sensors..",
                    //        board.getDeviceConfigSensors(deviceNo));
                    for (int sensorNo = 1; sensorNo <= board.getDeviceConfigSensors(deviceNo); sensorNo++) {

                        String sensorName = board.getSensorName(deviceNo, sensorNo);

                        int sensorIndex = board.getSensorIndex(deviceNo, sensorNo);
                        Double sensorValue = resolStatus.getValueByIndex(deviceNo, sensorIndex);

                        LOG.log(Level.INFO, "Sensor:{0}={1}",
                                new Object[]{sensorName, sensorValue});

                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + sensorName);

                        event.addProperty("value", sensorValue.toString());
                        this.notifyEvent(event);

                    }
                } else {
                    // no pre-defined name/indexes to report on,
                    // so we fall-back to temps and relays..

                    LOG.log(Level.INFO, "Device id: {0}, Source: {1}, temps: {2}, relays: {3}",
                            new Object[]{deviceNo,
                                resolStatus.deviceName(deviceNo),
                                resolStatus.temps(deviceNo),
                                resolStatus.relays(deviceNo)});

                    // loop through the temps..
                    for (Integer intTemp = 1; intTemp <= resolStatus.temps(deviceNo); intTemp++) {
                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + "temp" + intTemp.toString());
                        LOG.log(Level.INFO, "Sensor:temp{0}={1}",
                                new Object[]{intTemp, resolStatus.temp(deviceNo, intTemp)});
                        event.addProperty("value", resolStatus.temp(deviceNo, intTemp).toString());
                        this.notifyEvent(event);
                    }
                    // loop through the relays..
                    for (Integer intRelay = 1; intRelay <= resolStatus.relays(deviceNo); intRelay++) {
                        ProtocolRead event = new ProtocolRead(this, "ResolDL2", boardAddress + "relay" + intRelay.toString());
                        LOG.log(Level.INFO, "Sensor:relay{0}={1}",
                                new Object[]{intRelay, resolStatus.relay(deviceNo, intRelay)});
                        event.addProperty("value", resolStatus.relay(deviceNo, intRelay).toString());
                        this.notifyEvent(event);
                    }
                }

            }

        } catch (JsonSyntaxException errMsg) {
            disconnect();
            this.stop();
            setDescription("Bad data from " + statusFileURL + jsonContent);
            LOG.log(Level.SEVERE, "Module Resol DL2 JSON parse error: {0}. Bad data from {1}, Data: {2}",
                    new Object[]{errMsg, statusFileURL, jsonContent});

        } catch (ConnectException connEx) {
            disconnect();
            this.stop();
            this.setDescription("Connection timed out, no reply from the board at " + statusFileURL);
//        } catch (ParserConfigurationException ex) {
//            disconnect();
//            this.stop();
//            LOG.severe(Freedomotic.getStackTraceInfo(ex));
        } catch (Exception ex) {
            disconnect();
            this.stop();
            setDescription("Unable to connect to " + statusFileURL);
            LOG.severe(Freedomotic.getStackTraceInfo(ex));
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
}
