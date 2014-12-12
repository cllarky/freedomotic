/**
 *
 * Copyright (c) 2009-2014 Freedomotic team http://freedomotic.com
 *
 * This file is part of Freedomotic
 *
 * This Program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 2, or (at your option) any later version.
 *
 * This Program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Freedomotic; see the file COPYING. If not, see
 * <http://www.gnu.org/licenses/>.
 */
package com.freedomotic.plugins.devices.emoncms;

import com.freedomotic.api.EventTemplate;
import com.freedomotic.api.Protocol;
import com.freedomotic.exceptions.PluginRuntimeException;
import com.freedomotic.exceptions.PluginStartupException;
import com.freedomotic.exceptions.UnableToExecuteException;
import com.freedomotic.reactions.Command;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *
 * @author gpt
 */
public final class EmoncmsProtocol extends Protocol {

    // make an object for the data..
    private final Map<String, Object> EMONCMSDATA = new HashMap<>();
    private final static Logger LOG = LoggerFactory.getLogger(EmoncmsProtocol.class.getName());
    private final String CHARSET = "UTF-8";
    private int SEND_FAILURES = 0;
    private final int SEND_RETRY = configuration.getIntProperty("send-retry", 5);

    public EmoncmsProtocol() {
        super("EmoncmsProtocol", "/emoncms/manifest.xml");
    }


    @Override
    public void onStart() throws PluginStartupException{
        setDescription("Starting plugin..");
        SEND_FAILURES = 0;
        
        try {
            // TODO: Lets try a connection to the target server to see if it's there..
            
            
            
            
            setDescription("Saving data to: " + configuration.getStringProperty("target",""));
            setPollingWait(configuration.getIntProperty("polling-time", 60000));
            LOG.info("Emoncms plugin started. Saving data to emoncms every {} seconds.", configuration.getIntProperty("polling-time", 20000)/1000);
        } catch (Exception e) {
            LOG.error("Emoncms plugin failed to start: {}", e.getLocalizedMessage());
            throw new PluginStartupException("Emoncms plugin failed to start.", e);
        }
    }

    @Override
    public void onStop() {
        setPollingWait(-1); // disable polling
        LOG.info("Emoncms plugin stopped.");
        this.setDescription("Save data to Emoncms.");
    }
    
    @Override
    protected void onRun() throws PluginRuntimeException {
        LOG.debug("In onRun.");
        
        // here we will push the data to emoncms..
        
        if (EMONCMSDATA.isEmpty()) {
            // do nothing..
            LOG.debug("No data to save.");
        } else {
            // send the data..
            Gson gson = new Gson(); 
            String json = gson.toJson(EMONCMSDATA); 
            LOG.debug("Saving data: {}", json);
            
            String url = configuration.getStringProperty("target","");
            String query;
            try {
                query = String.format("apikey=%s&node=%s&json=%s", 
                    URLEncoder.encode(configuration.getStringProperty("apikey",""), CHARSET), 
                    URLEncoder.encode(configuration.getStringProperty("node",""), CHARSET),
                    json
                );
                
                HttpURLConnection httpConnection = (HttpURLConnection) new URL(url + "?" + query).openConnection();
                httpConnection.setRequestMethod("GET");
                httpConnection.setRequestProperty("Accept-Charset", CHARSET);
                
                LOG.debug("Sending URL: {}", url + "?" + query);
                
                int status = httpConnection.getResponseCode();
                
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Response code was: {}", status );
                    InputStream errorstream = httpConnection.getErrorStream();
                    BufferedReader br;
                    if (errorstream == null) {
                        InputStream inputstream = httpConnection.getInputStream();
                        br = new BufferedReader(new InputStreamReader(inputstream));
                    } else {
                        br = new BufferedReader(new InputStreamReader(errorstream));
                    }

                    String response = "";
                    String line;
                    while ((line = br.readLine()) != null) {
                        response += line;
                    }

                    LOG.debug("Returned: {}", response);

                    for (Entry<String, List<String>> header : httpConnection.getHeaderFields().entrySet()) {
                        LOG.debug(header.getKey() + "=" + header.getValue());
                    }
                }
                
                httpConnection.disconnect();
                
                // were we able to send it ok?
                // if so, clear the stored data..
            
                if (status == 200) {
                    EMONCMSDATA.clear();
                    SEND_FAILURES = 0;
                }
                
            } catch (UnsupportedEncodingException | MalformedURLException e) {
                LOG.warn("Failed to form/encode URL, discarded data. JSON was: {}. {}", json, e.getLocalizedMessage());
                EMONCMSDATA.clear();
            } catch (UnknownHostException e) {
                LOG.warn("Failed to send data (unknown host): {}", e.getMessage());
                SEND_FAILURES +=1;
                setDescription("Send failed. Retrying (" + SEND_FAILURES + ")..");
            } catch (IOException e) {
                LOG.warn("Failed to send data: {}", e.getMessage());
                SEND_FAILURES +=1;
                setDescription("Send failed. Retrying (" + SEND_FAILURES + ")..");
            }
    
            if (SEND_FAILURES >= SEND_RETRY) {
                LOG.warn("Giving up after {} tries.", SEND_RETRY);
                stop();
                setDescription("Plugin gave up after " + SEND_RETRY + " failed sends.");
                throw new PluginRuntimeException("Emoncms plugin giving up after " + SEND_RETRY + " failed send attempts.");
            }
        }
        
    }

    @Override
    protected void onCommand(Command c) throws IOException, UnableToExecuteException {

        if (isRunning()) {
            if (c.getProperty("command") == null 
                    || c.getProperty("command").isEmpty() 
                    || c.getProperty("command").equalsIgnoreCase("SAVE-DATA")) {
                // LOG.info("We are going to save the data to emoncms.");
                saveData(c);
                
            } else if (c.getProperty("command").equals("EXTRACT-DATA")) { //extract data
                LOG.info("We are going to extract data from emoncms.");
                // TODO.

            } else {
                LOG.warn("Emoncms plugin received unrecognised command: {}", c.getProperty("command"));
            }
        } else {
            LOG.info("Plugin is not running, but has been sent a command - ignoring.");
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
    protected void onShowGui() {
    }

    private void saveData(Command c) {

        try {

            LOG.debug("Saving data for {} object.", c.getProperty("event.object.name"));
            String strKey;
            
            // Search for all object behavior changes..
            Pattern pat = Pattern.compile("^current\\.object\\.behavior\\.(.*)");
            for (Entry<Object, Object> entry : c.getProperties().entrySet()) {
                String key = (String) entry.getKey();
                Matcher fits = pat.matcher(key);
                
                if (fits.find() && !fits.group(1).equals("data")) { //exclude unwanted behaviors
                    strKey = c.getProperty("event.object.name") + '.' + fits.group(1);
                    
                    strKey = URLEncoder.encode(strKey, CHARSET);
                    LOG.debug("Saving: {}={}", strKey, entry.getValue());
                    
                    if (configuration.getProperty("valuemap-" + entry.getValue()) == null) {
                        // no mapping to do with this value..
                        EMONCMSDATA.put(strKey,entry.getValue());
                    } else {
                        // map and add it to the data..
                        EMONCMSDATA.put(strKey,configuration.getProperty("valuemap-" + entry.getValue()));
                    }

                }
            }    
            
    
        } catch (UnsupportedEncodingException ex) {
            LOG.warn("Unable to encode object name - ignoring.");
        }
    }
}
