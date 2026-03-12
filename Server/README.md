# LocalCall Relay Server

## Start

1. Edit `config.json`.
2. Run from this folder:

```bash
python server.py
```

## What It Does

- Keeps a live peer registry for Android clients.
- Routes call signaling through TCP.
- Relays UDP audio packets between two clients (proxy mode for different networks/NAT).

## Config

- `host`: TCP bind IP (`0.0.0.0` for all interfaces)
- `port`: TCP signaling/registry port used by Android clients
- `relay_udp_host`: UDP relay bind IP
- `relay_udp_port`: UDP relay port (open in firewall)
- `relay_host_for_clients`: external/public host for clients; leave empty to let app use configured server IP
- `enable_upnp`: try automatic UPnP NAT port mapping on startup
- `upnp_discovery_timeout_sec`: UPnP gateway discovery timeout
- `upnp_lease_duration_sec`: mapping lease in seconds (`0` = permanent, if router supports)
- `upnp_description`: NAT mapping label visible in router UI
- `upnp_map_tcp`: map TCP signaling port (`port`) via UPnP
- `upnp_map_udp`: map UDP relay port (`relay_udp_port`) via UPnP
- `client_timeout_sec`: stale peer timeout
- `session_timeout_sec`: stale call session timeout
- `socket_timeout_sec`: socket read timeout
- `log_level`: `DEBUG` / `INFO` / `WARNING`

## Notes

- Open both TCP `port` and UDP `relay_udp_port` in firewall/NAT.
- UPnP works only if your router supports IGD and UPnP is enabled in router settings.
- In Android settings set the same server IP and TCP port.
- If server IP in app settings is empty, app stays in LAN direct mode (old broadcast discovery).
