package uk.ac.cam.tfc_server.rita;

// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************
// Rita.java
//
// RITA is the user "master controller" of the tfc modules...
// Based on user requests via the http UI, RITA will spawn feedplayers and zones, to
// display the analysis in real time on the user browser.
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
// Forms part of the 'tfc_server' next-generation Realtime Intelligent Traffic Analysis system
//
// Provides an HTTP server that serves the end user
//
// *************************************************************************************************
// *************************************************************************************************
// *************************************************************************************************

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.DeploymentOptions;

import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpMethod;

import io.vertx.core.file.FileSystem;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.buffer.Buffer;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// vertx web, service proxy, sockjs eventbus bridge
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;
import io.vertx.ext.web.handler.sockjs.BridgeOptions;
import io.vertx.ext.web.handler.sockjs.PermittedOptions;
import io.vertx.ext.web.handler.sockjs.SockJSHandler;
import io.vertx.ext.web.handler.sockjs.SockJSHandlerOptions;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;
import io.vertx.ext.web.Session;

import java.io.*;
import java.time.*;
import java.time.format.*;
import java.util.*;

public class Rita extends AbstractVerticle {

    private int HTTP_PORT; // from config()
    private String RITA_ADDRESS; // from config()
    private String EB_SYSTEM_STATUS; // from config()
    private String EB_MANAGER; // from config()
    private String MODULE_NAME; // from config()
    private String MODULE_ID; // from config()
    private String WEBROOT; // from config()
    
    //debug - these may come from user commands
    private ArrayList<String> FEEDPLAYERS; // optional from config()
    private ArrayList<String> ZONEMANAGERS; // optional from config()

    private String ZONE_ADDRESS; // optional from config()
    private String ZONE_FEED; // optional from config()
    private String FEEDPLAYER_ADDRESS; // optional from config()

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    // Vertx event bus
    private EventBus eb = null;

    // data structure to hold socket info of each connected user
    private SockInfo sock_info;
    
    @Override
    public void start(Future<Void> fut) throws Exception
    {

    // Get src/main/conf/tfc_server.conf config values for module
    if (!get_config())
        {
            System.err.println("Rita: problem loading config");
            vertx.close();
            return;
        }

    System.out.println("Rita starting as "+MODULE_NAME+"."+MODULE_ID);

    // initialize object to hold socket connection data for each connected session
    sock_info = new SockInfo(MODULE_ID);
    
    eb = vertx.eventBus();

    //debug also need to spawn modules based on messages from client browser
    // get test config options for FeedPlayers from conf file given to Rita
    for (int i=0; i<FEEDPLAYERS.size(); i++)
        {
            final String feedplayer_id = FEEDPLAYERS.get(i);
            //debug -- also should get start commands from eb.zonemanager.X
            DeploymentOptions feedplayer_options = new DeploymentOptions();
            JsonObject conf = new JsonObject();
            if (EB_SYSTEM_STATUS != null)
                {
                    conf.put("eb.system_status", EB_SYSTEM_STATUS);
                }
            if (EB_MANAGER != null)
                {
                    conf.put("eb.manager", EB_MANAGER);
                }
            if (FEEDPLAYER_ADDRESS != null)
                {
                    conf.put("feedplayer.address", FEEDPLAYER_ADDRESS);
                }
            feedplayer_options.setConfig(conf);
            vertx.deployVerticle("service:uk.ac.cam.tfc_server.feedplayer."+feedplayer_id,
                                 feedplayer_options,
                                 res -> {
                    if (res.succeeded()) {
                        System.out.println("Rita"+MODULE_ID+": FeedPlayer "+feedplayer_id+ "started");
                    } else {
                        System.err.println("Rita"+MODULE_ID+": failed to start FeedPlayer " + feedplayer_id);
                        fut.fail(res.cause());
                    }
                });
        }
    
    //debug currently only spawning zonemanagers at startup - should support browser client messages
    for (int i=0; i<ZONEMANAGERS.size(); i++)
        {
            final String zonemanager_id = ZONEMANAGERS.get(i);
            //debug -- also should get start commands from eb.zonemanager.X
            DeploymentOptions zonemanager_options = new DeploymentOptions();
            JsonObject conf = new JsonObject();
            if (EB_SYSTEM_STATUS != null)
                {
                    conf.put("eb.system_status", EB_SYSTEM_STATUS);
                }
            if (EB_MANAGER != null)
                {
                    conf.put("eb.manager", EB_MANAGER);
                }
            if (ZONE_ADDRESS != null)
                {
                    conf.put("zonemanager.zone.address", ZONE_ADDRESS);
                }
            if (ZONE_FEED != null)
                {
                    // note if FeedPlayers are also started, this will usually be
                    // the same as FEEDPLAYER_ADDRESS so the Zones listen to the
                    // FeedPlayers
                    conf.put("zonemanager.zone.feed", ZONE_FEED);
                }
            zonemanager_options.setConfig(conf);
            vertx.deployVerticle("service:uk.ac.cam.tfc_server.zonemanager."+zonemanager_id,
                                 zonemanager_options,
                                 res -> {
                    if (res.succeeded()) {
                        System.out.println("Rita"+MODULE_ID+": ZoneManager "+zonemanager_id+ "started");
                    } else {
                        System.err.println("Rita"+MODULE_ID+": failed to start ZoneManager " + zonemanager_id);
                        fut.fail(res.cause());
                    }
                });
        }
    
    // send periodic "system_status" messages
    vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
      eb.publish(EB_SYSTEM_STATUS,
                 "{ \"module_name\": \""+MODULE_NAME+"\"," +
                   "\"module_id\": \""+MODULE_ID+"\"," +
                   "\"status\": \"UP\"," +
                   "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                   "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                 "}" );
      });

    // *************************************************************************************
    // *************************************************************************************
    // *********** Start Rita web server (incl Socket and EventBus Bridge)      ************
    // *************************************************************************************
    // *************************************************************************************
    HttpServer http_server = vertx.createHttpServer();

    Router router = Router.router(vertx);

    // *********************************
    // create handler for browser socket 
    // *********************************

    SockJSHandlerOptions sock_options = new SockJSHandlerOptions().setHeartbeatInterval(2000);

    SockJSHandler sock_handler = SockJSHandler.create(vertx, sock_options);

    sock_handler.socketHandler( sock -> {
            // Rita received new socket connection
            System.out.println("Rita."+MODULE_ID+": sock connection received with "+sock.writeHandlerID());
            
            // Assign a handler funtion to receive data if send
            sock.handler( buf -> {
               System.out.println("Rita."+MODULE_ID+": sock received '"+buf+"'");
               sock_info.add(sock, buf);
                });

            sock.endHandler( (Void v) -> {
                    System.out.println("Rita."+MODULE_ID+": sock closed "+sock.writeHandlerID());
                    //debug! we're not removing closed sockets from sock_info
                    //sock_info.remove(sock.webSession().id());
                    sock.close();
                });
      });

    router.route("/ws/*").handler(sock_handler);

    // ********************************
    // create handler for embedded page
    // ********************************

    router.route("/home").handler( routingContext -> {

        HttpServerResponse response = routingContext.response();
        response.putHeader("content-type", "text/html");

        response.end("<h1>TFC Rita</h1><p>Vertx-Web!</p>");
    });

    // **********************************
    // create handler for eventbus bridge
    // **********************************

    SockJSHandler ebHandler = SockJSHandler.create(vertx);

    //debug rita_in/out EB names should be in config()
    PermittedOptions inbound_permitted = new PermittedOptions().setAddress("rita_in");
    BridgeOptions bridge_options = new BridgeOptions();
    // add outbound address for user messages
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("rita_out") );
    // add outbound address for feed messages
    bridge_options.addOutboundPermitted( new PermittedOptions().setAddress("rita_feed") );

    bridge_options.addInboundPermitted(inbound_permitted);

    ebHandler.bridge(bridge_options);

    router.route("/eb/*").handler(ebHandler);

    // ********************************
    // create handler for static pages
    // ********************************

    StaticHandler static_handler = StaticHandler.create();
    static_handler.setWebRoot(WEBROOT);
    static_handler.setCachingEnabled(false);
    router.route(HttpMethod.GET, "/*").handler( static_handler );

    // ********************************
    // connect router to http_server
    // ********************************

    http_server.requestHandler(router::accept).listen(HTTP_PORT);


    // *************************************
    // Set up handlers for eventbus messages    
    // *************************************
    
    //debug hardcoded to "rita_in" should be in config()
    // create listener for eventbus 'console_in' messages
    eb.consumer("rita_in", message -> {
          System.out.println("Rita_in: "+message.body());
      });

    //debug !! wrong to hardcode the feedplayer eventbus address
    //debug also hardcoded "rita_out"
    // create listener for eventbus FeedPlayer messages
    // and send them to the browser via rita_out
    if (FEEDPLAYER_ADDRESS != null)
        {
            eb.consumer(FEEDPLAYER_ADDRESS, message -> {
                    //debug! this rita is currently publishing messages to ALL http clients
                    eb.publish("rita_feed", message.body());
                    //eb.send("rita_out", "feed received from "+FEEDPLAYER_ADDRESS);
              });
        }
    else if (ZONE_FEED != null)
        {
            eb.consumer(ZONE_FEED, message -> {
                    eb.publish("rita_feed", message.body());
                    //eb.send("rita_out", "feed received from "+FEEDPLAYER_ADDRESS);
              });
        }

    // Subscribe to the messages coming from the Zones
    //debug we're only simply sending the messages to the browser to appear in log window
    if (ZONE_ADDRESS != null)
        {
            eb.consumer(ZONE_ADDRESS, message -> {
                    eb.publish("rita_out", message.body());
                    send_user_messages(message.body().toString());
                });
        }
                
    } // end start()

    // For a given Zone completion message
    // if the zone matches the zone_id in a user subscription
    // then forward the message on that socket
    private void send_user_messages(String msg)
    {
        //debug we're using a single hardcoded socket ref
        //debug will need to iterate through sockets in sock_info
        //debug not yet handling socket close

        JsonObject msg_jo = new JsonObject(msg);
        String msg_zone_id= msg_jo.getString("module_id");
                
        // for each client socket entry in sock_info
        //   if sock_data is not null
        //     for each zone_id in subscription on that socket
        //       if zone_id == zone_id in Zone msg
        //         then forward the message on this socket
        for (String UUID: sock_info.keys())
            {
                SockData sock_data = sock_info.get(UUID);
                
                if (sock_data != null)
                    {
                        for (String zone_id: sock_data.zone_ids)
                            {
                                if (zone_id.equals(msg_zone_id))
                                    {
                                        sock_data.sock.write(Buffer.buffer(msg));
                                    }
                            }
                    }
            }
    }
    
    // Load initialization global constants defining this module from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        // module.name e.g. "rita"
        // module.id e.g. "A"
        // eb.system_status - String eventbus address for system status messages
        // eb.manager - evenbus address to subscribe to for system management messages

        MODULE_NAME = config().getString("module.name"); // "rita"
        if (MODULE_NAME==null)
            {
                System.err.println("Rita: no module.name in config()");
                return false;
            }
        
        MODULE_ID = config().getString("module.id"); // A, B, ...
        if (MODULE_ID==null)
            {
                System.err.println("Rita: no module.id in config()");
                return false;
            }

        // common system status reporting address, e.g. for UP messages
        // picked up by Console
        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS==null)
            {
                System.err.println("Rita: no eb.system_status in config()");
                return false;
            }

        // system control address - commands are broadcast on this
        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER==null)
            {
                System.err.println("Rita: no eb.manager in config()");
                return false;
            }

        // eventbus address for this Rita to publish its messages to
        RITA_ADDRESS = config().getString(MODULE_NAME+".address");

        // port for user browser access to this Rita
        HTTP_PORT = config().getInteger(MODULE_NAME+".http.port");

        // where the built-in webserver will find static files
        WEBROOT = config().getString(MODULE_NAME+".webroot");
        //debug we should properly test for bad config

        // get list of FeedPlayers to start on startup
        FEEDPLAYERS = new ArrayList<String>();
        JsonArray feedplayer_list = config().getJsonArray(MODULE_NAME+".feedplayers");
        if (feedplayer_list != null)
            {
                for (int i=0; i<feedplayer_list.size(); i++)
                    {
                        FEEDPLAYERS.add(feedplayer_list.getString(i));
                    }
            }

        // get list of ZoneManager id's to start on startup
        ZONEMANAGERS = new ArrayList<String>();
        JsonArray zonemanager_list = config().getJsonArray(MODULE_NAME+".zonemanagers");
        if (zonemanager_list != null)
            {
                for (int i=0; i<zonemanager_list.size(); i++)
                    {
                        ZONEMANAGERS.add(zonemanager_list.getString(i));
                    }
            }

        // the eventbus address for the Zones to publish their messages to
        ZONE_ADDRESS = config().getString(MODULE_NAME+".zone.address");

        // the eventbus address for the Zones to subscribe to
        ZONE_FEED = config().getString(MODULE_NAME+".zone.feed");

        // note if we start FeedPlayers, ZONE_FEED will typically be FEEDPLAYER_ADDRESS
        FEEDPLAYER_ADDRESS = config().getString(MODULE_NAME+".feedplayer.address");
        
        return true;
    }

    
} // end class Rita

// Data for each socket connection
// session_id is in sock.webSession().id()
class SockData {
        public SockJSSocket sock;
        public ArrayList<String> zone_ids;
}

// Object to store data for all current socket connections
class SockInfo {

    private Hashtable<String,SockData> sock_table;

    // for error messages we will include MODULE_ID from Rita class
    private String MODULE_ID;

    // initialize new SockInfo object
    SockInfo (String rita_module_id) {
        MODULE_ID = rita_module_id;
        sock_table = new Hashtable<String,SockData>();
    }

    // Add new connection to known list, with zone_ids in buf
    public void add(SockJSSocket sock, Buffer buf)
    {
        if (sock == null)
            {
                System.err.println("Rita."+MODULE_ID+": SockInfo.add() called with sock==null");
                return;
            }

        JsonObject connect_jo = new JsonObject(buf.toString());
        // create new entry for sock_data
        SockData entry = new SockData();
        entry.sock = sock;
        
        entry.zone_ids = new ArrayList<String>();

        JsonArray zones_ja = connect_jo.getJsonArray("zone_ids");
        for (int i=0; i<zones_ja.size(); i++)
            {
                entry.zone_ids.add(zones_ja.getString(i));
            }
        System.out.println("Rita."+MODULE_ID+": SockInfo.add "+connect_jo.getString("UUID")+ " " +entry.zone_ids.toString());
        // push this entry onto the array
        //debug sock_table entry hardcoded to 'foo' - should use UUID
        sock_table.put(connect_jo.getString("UUID"),entry);
    }

    public SockData get(String UUID)
    {
        // retrieve data for current socket, if it exists
        SockData sock_data = sock_table.get(UUID);
        
        if (sock_data != null)
            {
                return sock_data;
            }
        return null;
    }

    public void remove(String UUID)
    {
        SockData sock_data = sock_table.remove(UUID);
        if (sock_data == null)
            {
                System.err.println("Rita."+MODULE_ID+": SockInfo.remove non-existent session_id "+UUID);
            }
    }

    public Set<String> keys()
    {
        return sock_table.keySet();
    }
}
