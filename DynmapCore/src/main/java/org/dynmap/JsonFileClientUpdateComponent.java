// Modified for MCCMap: Paper-only support and a read-only website (2026).
package org.dynmap;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.dynmap.storage.MapStorage;
import org.dynmap.utils.BufferInputStream;
import org.dynmap.utils.BufferOutputStream;
import org.dynmap.web.Json;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import static org.dynmap.JSONUtils.*;

import java.nio.charset.Charset;
import java.util.concurrent.CompletableFuture;

public class JsonFileClientUpdateComponent extends ClientUpdateComponent {
    protected long jsonInterval;
    protected long currentTimestamp = 0;
    protected long lastTimestamp = 0;
    private long last_confighash;
    private MapStorage storage;
    private File baseStandaloneDir;

    private static class FileToWrite {
        String filename;
        byte[] content;
        @Override
        public boolean equals(Object o) {
            if(o instanceof FileToWrite) {
                return ((FileToWrite)o).filename.equals(this.filename);
            }
            return false;
        }
    }
    private class FileProcessor implements Runnable {
        public void run() {
            while(true) {
                FileToWrite f = null;
                synchronized(lock) {
                    if(files_to_write.isEmpty() == false) {
                        f = files_to_write.removeFirst();
                    }
                    else {
                        pending = null;
                        return;
                    }
                }
                BufferOutputStream buf = null;
                if (f.content != null) {
                    buf = new BufferOutputStream();

                    buf.write(f.content);

                }
                if (!storage.setStandaloneFile(f.filename, buf)) {
                    Log.severe("Exception while writing JSON-file - " + f.filename);
                }
            }
        }
    }
    private Object lock = new Object();
    private FileProcessor pending;
    private LinkedList<FileToWrite> files_to_write = new LinkedList<FileToWrite>();

    private void enqueueFileWrite(String filename, byte[] content) {
        FileToWrite ftw = new FileToWrite();
        ftw.filename = filename;
        ftw.content = content;
        synchronized(lock) {
            boolean didadd = false;
            if(pending == null) {
                didadd = true;
                pending = new FileProcessor();
            }
            files_to_write.remove(ftw);
            files_to_write.add(ftw);
            if(didadd) {
                MapManager.scheduleDelayedJob(new FileProcessor(), 0);
            }
        }
    }

    private static Charset cs_utf8 = Charset.forName("UTF-8");
    public JsonFileClientUpdateComponent(final DynmapCore core, final ConfigurationNode configuration) {
        super(core, configuration);

        if (!core.isInternalWebServerDisabled) {
            Log.severe("Using JsonFileClientUpdateComponent with disable-webserver=false is not supported: there will likely be problems");
        }

        jsonInterval = (long)(configuration.getFloat("writeinterval", 1) * 1000);
        storage = core.getDefaultMapStorage();
        baseStandaloneDir = new File(core.configuration.getString("webpath", "web"), "standalone");
        if (!baseStandaloneDir.isAbsolute()) {
            baseStandaloneDir = new File(core.getDataFolder(), baseStandaloneDir.toString());
        }
        /* Generate our config.js file */
        generateConfigJS(core);

        core.getServer().scheduleServerTask(new Runnable() {
            @Override
            public void run() {
                currentTimestamp = System.currentTimeMillis();
                if(last_confighash != core.getConfigHashcode()) {
                    writeConfiguration();
                }
                writeUpdates();
                lastTimestamp = currentTimestamp;
                core.getServer().scheduleServerTask(this, jsonInterval/50);
            }}, jsonInterval/50);

        core.events.addListener("buildclientconfiguration", new Event.Listener<JSONObject>() {
            @Override
            public void triggered(JSONObject t) {
                s(t, "jsonfile", true);
            }
        });
        core.events.addListener("initialized", new Event.Listener<Object>() {
            @Override
            public void triggered(Object t) {
                writeConfiguration();
                writeUpdates(); /* Make sure we stay in sync */
            }
        });
        core.events.addListener("server-started", new Event.Listener<Object>() {
            @Override
            public void triggered(Object t) {
                writeConfiguration();
                writeUpdates(); /* Make sure we stay in sync */
            }
        });
        core.events.addListener("worldactivated", new Event.Listener<DynmapWorld>() {
            @Override
            public void triggered(DynmapWorld t) {
                writeConfiguration();
                writeUpdates(); /* Make sure we stay in sync */
            }
        });
    }

    private void generateConfigJS(DynmapCore core) {
        MapStorage store = core.getDefaultMapStorage();

        StringBuilder sb = new StringBuilder();
        sb.append("var config = {\n");
        sb.append(" url : {\n");
        /* Get configuration URL */
        sb.append("  configuration: '");
        sb.append(core.configuration.getString("url/configuration", store.getConfigurationJSONURI()));
        sb.append("',\n");
        /* Get update URL */
        sb.append("  update: '");
        sb.append(core.configuration.getString("url/update", store.getUpdateJSONURI()));
        sb.append("',\n");
        /* Get tiles URL */
        sb.append("  tiles: '");
        sb.append(core.configuration.getString("url/tiles", store.getTilesURI()));
        sb.append("',\n");
        /* Get markers URL */
        sb.append("  markers: '");
        sb.append(core.configuration.getString("url/markers", store.getMarkersURI()));
        sb.append("'\n }\n};\n");

        byte[] outputBytes = sb.toString().getBytes(cs_utf8);
        MapManager.scheduleDelayedJob(new Runnable() {
            public void run() {
                    File f = new File(baseStandaloneDir, "config.js");
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(f);
                        fos.write(outputBytes);
                    } catch (IOException iox) {
                        Log.severe("Exception while writing " + f.getPath(), iox);
                    } finally {
                        if(fos != null) {
                            try {
                                fos.close();
                            } catch (IOException x) {}
                            fos = null;
                        }
                    }
            }
        }, 0);
    }

    protected void writeConfiguration() {
        JSONObject clientConfiguration = new JSONObject();
        core.events.trigger("buildclientconfiguration", clientConfiguration);
        last_confighash = core.getConfigHashcode();

        byte[] content = clientConfiguration.toJSONString().getBytes(cs_utf8);

        enqueueFileWrite("mccmap_config.json", content);
    }

    @SuppressWarnings("unchecked")
    protected void writeUpdates() {
        if(core.mapManager == null) return;
        //Handles Updates
        ArrayList<DynmapWorld> wlist = new ArrayList<DynmapWorld>(core.mapManager.getWorlds());	// Grab copy of world list
        for (int windx = 0; windx < wlist.size(); windx++) {
            DynmapWorld dynmapWorld = wlist.get(windx);
            JSONObject update = new JSONObject();
            update.put("timestamp", currentTimestamp);
            ClientUpdateEvent clientUpdate = new ClientUpdateEvent(currentTimestamp - 30000, dynmapWorld, update);
            core.events.trigger("buildclientupdate", clientUpdate);

            String outputFile = "mccmap_" + dynmapWorld.getName() + ".json";

            CompletableFuture.runAsync(() -> {
                byte[] content = Json.stringifyJson(update).getBytes(cs_utf8);

                enqueueFileWrite(outputFile, content);
            });
        }
    }

    @Override
    public void dispose() {
        super.dispose();
    }
}
