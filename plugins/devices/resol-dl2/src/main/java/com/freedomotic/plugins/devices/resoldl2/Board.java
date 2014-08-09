package com.freedomotic.plugins.devices.resoldl2;

/**
 * 
 * @author Jonathan D Clark
 */


import java.util.HashMap;
import java.util.Map;


public class Board {

    private String ipAddress = null;
    private String lineToMonitorize;
    private int port;
    private boolean logSourceData;
    private Map<Integer, DeviceConfig> configs = new HashMap<Integer, DeviceConfig>();

    public Board(String ipAddress, int port, boolean logSourceData) {
        setIpAddress(ipAddress);
        setPort(port);
        setLogSourceData(logSourceData);
    }

    static class DeviceConfig {

        private String name;
        private int deviceId;
        private Map<Integer, DeviceSensor> sensors = new HashMap<Integer, DeviceSensor>();
    }

    void setDeviceConfig(int deviceID, DeviceConfig config) {
        if (this.configs.containsKey(deviceID)) {
            // we found the entry
        } else {
            this.configs.put(deviceID, config);

        }

    }

    static class DeviceSensor {

        private String sensorName;
        private Integer sensorIndex;
    }

    // return how many sensors are defined for a device
    public int getDeviceConfigSensors(int deviceNo) {
        if (!this.configs.containsKey(deviceNo)) {
            return 0;
        }
        return this.configs.get(deviceNo).sensors.size();
    }

    public void setDeviceName(int deviceNo, String deviceName) {
        if (this.configs.containsKey(deviceNo)) {
            // thereis a DeviceConfig..
            this.configs.get(deviceNo).name = deviceName;
        } else {
            // we need to make a DeviceConfig..
            this.configs.put(deviceNo, new DeviceConfig());
            this.configs.get(deviceNo).name = deviceName;
        }
    }

    public String getDeviceName(int deviceNo) {
        return this.configs.get(deviceNo).name;
    }

    public void setDeviceId(int deviceNo, int deviceId) {
        if (!this.configs.containsKey(deviceNo)) {
            this.configs.put(deviceNo, new DeviceConfig());
        }
        this.configs.get(deviceNo).deviceId = deviceId;
    }

    public int getDeviceId(int deviceNo) {
        return this.configs.get(deviceNo).deviceId;
    }

    public void setSensorName(int deviceNo, int sensorNo, String sensorName) {
        if (!this.configs.containsKey(deviceNo)) {
            this.configs.put(deviceNo, new DeviceConfig());
        }
        if (!this.configs.get(deviceNo).sensors.containsKey(sensorNo)) {
            this.configs.get(deviceNo).sensors.put(sensorNo, new DeviceSensor());
        }

        this.configs.get(deviceNo).sensors.get(sensorNo).sensorName = sensorName;
    }

    public void setSensorIndex(int deviceNo, int sensorNo, int sensorIndex) {
        if (!this.configs.containsKey(deviceNo)) {
            this.configs.put(deviceNo, new DeviceConfig());
        }
        if (!this.configs.get(deviceNo).sensors.containsKey(sensorNo)) {
            this.configs.get(deviceNo).sensors.put(sensorNo, new DeviceSensor());
        }

        this.configs.get(deviceNo).sensors.get(sensorNo).sensorIndex = sensorIndex;
    }

    public String getSensorName(int deviceNo, int sensorNo) {
        return this.configs.get(deviceNo).sensors.get(sensorNo).sensorName;
    }

    public int getSensorIndex(int deviceNo, int sensorNo) {
        return this.configs.get(deviceNo).sensors.get(sensorNo).sensorIndex;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void setLogSourceData(boolean logSourceData) {
        this.logSourceData = logSourceData;
    }

    public boolean getLogSourceData() {
        return logSourceData;
    }

    public String getLineToMonitorize() {
        return lineToMonitorize;
    }

    public void setLineToMonitorize(String lineToMonitorize) {
        this.lineToMonitorize = lineToMonitorize;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }
}