# [Platform](https://github.com/ijl20/tfc_server) &gt; MsgRouter

MsgRouter is part of the Adaptive City Platform
supported by the Smart Cambridge programme.

## Overview

MsgRouter subscribes to an eventbus address, filters the messages received, and POSTs messages to
defined destination addresses.

MsgRouter contains within it a 'destinations' structure that contains the reference information for
each destination, e.g. the URL and an identifier.

## msgrouter.routers config fields

`source_address`: the eventbus address to be listened to for possible messages to be sent to destination.

`destination_id`: any string that will uniquely (within tfc_server) identify this destination.

`destination_type`: current values `feed_eventbus_msg` or `feed_eventbus_0`:

    `feed_eventbus_msg`: will send the eventbus record 'as-is', with an array of records in the 
    `request_data` property. This is by far the typical use.

    `feed_eventbus_0`: assumes the original data source produces *single* data records, which the
    eventbus source has still packaged into a `request_data` array property with a single element.
    This setting will unwrap the feedhandler envelope and just send `request_data[0]`.  Currently
    used for forwarding LoraWAN data.

`url`: the complete http destination address for the messages to be posted to.

`http_token_header`: optional, e.g. `X-Auth-Token` or `x-api-key`, as requested at time of setup
by client. Defaults to `X-Auth-Token`.

`http_token`: optional, a security key to be used to protect the recipient. So combined with the
above, MsgRouter will post messages to `url` with the header `http_token_header: http_token`.

## Sample MsgRouter service config files

### MsgRouter user to forward all messages from an eventbus address to multiple URLs
```
{
"main":    "uk.ac.cam.tfc_server.msgrouter.MsgRouter",
"options":
    { "config":
        {

        "module.name":           "msgrouter",
        "module.id":             "cloudamber.sirivm",

        "eb.system_status":      "tfc.system_status",
        "eb.console_out":        "tfc.console_out",
        "eb.manager":            "tfc.manager",

        "msgrouter.log_level":     2,

        "msgrouter.address": "tfc.msgrouter.cloudamber.sirivm",

        "msgrouter.routers":
            [
                {
                "source_address": "tfc.feedmaker.cloudamber.sirivm",
                "destination_id": "tfc-app3.feedmaker.eventbus",
                "destination_type": "feed_eventbus_msg",
                "url": "http://tfc-app3.cl.cam.ac.uk/feedmaker/eventbus/sirivm_json",
                "http_token_header": "X-Auth-Token",
                "http_token": "cam-test-siri"
                },
                {
                "source_address": "tfc.feedmaker.cloudamber.sirivm",
                "destination_id": "tfc-app4.feedmaker.eventbus",
                "destination_type": "feed_eventbus_msg",
                "url": "http://tfc-app4.cl.cam.ac.uk/feedmaker/eventbus/sirivm_json",
                "http_token": "cam-test-siri"
                }
            ]
        }
    }
}

```
### MsgRouter used for 'lorawan' sensors using database pre-load and dynamic sensor->destination lookup

```
{
    "main":    "uk.ac.cam.tfc_server.msgrouter.MsgRouter",
    "options":
        { "config":
            {

                "module.name":           "msgrouter",
                "module.id":             "A",

                "eb.system_status":      "tfc.system_status",
                "eb.console_out":        "tfc.console_out",
                "eb.manager":            "tfc.manager",

                "msgrouter.log_level":     1,

                "msgrouter.address": "tfc.msgrouter.A",

                "msgrouter.db.url":      "jdbc:postgresql:tfcserver",
                "msgrouter.db.user":     "tfcserver_r",
                "msgrouter.db.password": "bajoozle",
                "comment": "That password above is temporary...",

                "msgrouter.routers":
                    [
                        {
                        "source_address": "tfc.everynet_feed.A",
                        "source_filter": {
                                             "field": "sensor_type",
                                             "compare": "=",
                                             "value": "lorawan"
                                         }
                        }
                    ]
            }
        }
}
             
```

## sensor -> destinations update API via 'tfc.manager' eventbus messages

MsgRouter can receive messages from the eventbus to update its list of 'sensors' and 'destinations'.

Note in general these messages are generated by HttpMsg, which adds the "module_name", "module_id",
"to_module_name", "to_module_id" fields.  The actual POST to HttpMsg will contain the "method" and
"params" fields.

Note that there are other fields in the database csn_sensor->>info::JSONB column that can be included
in the various eventbus messages, but the notes below show those of interest to MsgRouter.

In the database table csn_sensor, it is possible for info->>destination_id and info->>destination_type to be missing,
but MsgRouter can ignore these entries (as MsgRouter only cares about sensors it is actually routing).

### Sample add_destination JSON message

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"add_destination",
  "params":{ "info": { "destination_id": "xyz",
                       "destination_type": "everynet_jsonrpc",
                       "http_token":"foo!bar",
                       "url": "http://localhost:8080/efgh"
                     }
           }
}
```

### remove_destination

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"remove_destination",
  "params":{ "info": { "destination_id": "xyz",
                       "destination_type": "everynet_jsonrpc"
                     }
           }
}
```

### Sample add_sensor JSON message

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"add_sensor",
  "params":{ "info": { "sensor_id": "abc",
                       "sensor_type": "lorawan",
                       "destination_id": "xyz",
                       "destination_type": "everynet_jsonrpc"
                     }
           }
}
```

### Remove sensor

```
{ "module_name":"httpmsg",
  "module_id":"test"
  "to_module_name":"msgrouter",
  "to_module_id":"test",
  "method":"remove_sensor",
  "params":{ "info": { "sensor_id": "abc",
                       "sensor_type": "lorawan"
                     }
           }
}
```

## Database tables

These database tables are written by tfc_web (Django) and also read by the tfc_server real-time
platform (Vertx) verticle MsgRouter.  MsgRouter will build in-memory HashMaps containing the contents of both the csn_sensor and
csn_destination tables. These HashMaps will be updated by subsequent EventBus messages generated by
tfc_web via the tfc_server verticle HttpMsg.

### csn_destination

* info->>(destination_type,destination_id) - this is the definitive key associated with the destination
* url - the http POST destination, e.g. http://foo.cam.ac.uk/sensordata/now
* http_token (optional) - an X-Auth-Token value to be put in the POST header

```
                                 Table "public.csn_destination"
  Column |  Type   |                          Modifiers                           | Storage  | Stats target | Description
 --------+---------+--------------------------------------------------------------+----------+--------------+-------------
  id     | integer | not null default nextval('csn_destination_id_seq'::regclass) | plain    |              |
  info   | jsonb   | not null                                                     | extended |              |
 Indexes:
     "csn_destination_pkey" PRIMARY KEY, btree (id)
     "idx_csn_destination_info_destination_id_and_type" UNIQUE, btree ((info ->> 'destination_id'::text), (info ->> 'destination_type'::text))
     "idx_csn_destination_info_destination_id" btree ((info ->> 'destination_id'::text))
     "idx_csn_destination_info_destination_type" btree ((info ->> 'destination_type'::text))
     "idx_csn_destination_info_user_id" btree ((info ->> 'user_id'::text))
```

### csn_sensor

Lists the defined sensors.

Note that MsgRouter assumes the following properties in the "info" column:
* sensor_id
* sensor_type             
* destination_id
* destination_type
```
                                 Table "public.csn_sensor"
  Column |  Type   |                        Modifiers                        | Storage  | Stats target | Description
 --------+---------+---------------------------------------------------------+----------+--------------+-------------
  id     | integer | not null default nextval('csn_sensor_id_seq'::regclass) | plain    |              |
  info   | jsonb   | not null                                                | extended |              |
 Indexes:
     "csn_sensor_pkey" PRIMARY KEY, btree (id)
     "idx_csn_sensor_info_sensor_id_and_type" UNIQUE, btree ((info ->> 'sensor_id'::text), (info ->> 'sensor_type'::text))
     "idx_csn_sensor_info_destination_id" btree ((info ->> 'destination_id'::text))
     "idx_csn_sensor_info_destination_type" btree ((info ->> 'destination_type'::text))
     "idx_csn_sensor_info_sensor_type" btree ((info ->> 'sensor_type'::text))
     "idx_csn_sensor_info_user_id" btree ((info ->> 'user_id'::text))             
```
