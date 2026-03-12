#!/usr/bin/env python3
"""LocalCall server: peer registry + relay signaling + UDP audio proxy."""

from __future__ import annotations

import argparse
import ipaddress
import json
import logging
import secrets
import socket
import socketserver
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Union


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_CONFIG_PATH = BASE_DIR / "config.json"
DEFAULT_CALL_PORT = 45679
DEFAULT_RELAY_UDP_PORT = 45701
DEFAULT_SESSION_TIMEOUT_SEC = 120


@dataclass
class Peer:
    client_id: str
    name: str
    ip: str
    call_port: int
    last_seen: float


@dataclass
class RelaySession:
    session_id: str
    caller_id: str
    callee_id: str
    caller_token: str
    callee_token: str
    created_at: float
    last_seen: float
    caller_endpoint: Optional[Tuple[str, int]] = None
    callee_endpoint: Optional[Tuple[str, int]] = None


@dataclass
class ServerConfig:
    host: str
    port: int
    client_timeout_sec: int
    socket_timeout_sec: int
    log_level: str
    log_file: str
    relay_udp_host: str
    relay_udp_port: int
    relay_host_for_clients: str
    session_timeout_sec: int
    enable_upnp: bool
    upnp_discovery_timeout_sec: int
    upnp_lease_duration_sec: int
    upnp_description: str
    upnp_map_tcp: bool
    upnp_map_udp: bool


@dataclass
class CallResult:
    session_id: str
    relay_host: str
    relay_port: int
    caller_token: str
    error: Optional[str] = None


class PeerRegistry:
    def __init__(self, timeout_sec: int, session_timeout_sec: int) -> None:
        self.timeout_sec = timeout_sec
        self.session_timeout_sec = session_timeout_sec
        self._lock = threading.Lock()
        self._peers: Dict[str, Peer] = {}
        self._sessions: Dict[str, RelaySession] = {}
        self._events: Dict[str, List[str]] = {}
        self._endpoint_to_session: Dict[Tuple[str, int], Tuple[str, str]] = {}

    # ------------------------------------------------------------------
    # Peers and events
    # ------------------------------------------------------------------

    def register_legacy(self, name: str, ip: str, call_port: int) -> None:
        now = time.time()
        client_id = f"legacy:{ip}"
        peer = Peer(client_id=client_id, name=name, ip=ip, call_port=call_port, last_seen=now)
        with self._lock:
            self._cleanup_locked(now)
            self._peers[client_id] = peer

    def register_v2(self, client_id: str, name: str, ip: str, call_port: int) -> None:
        now = time.time()
        safe_client_id = sanitize_client_id(client_id)
        peer = Peer(
            client_id=safe_client_id,
            name=name,
            ip=ip,
            call_port=call_port,
            last_seen=now,
        )
        with self._lock:
            self._cleanup_locked(now)
            self._peers[safe_client_id] = peer

    def list_peers_legacy(self, exclude_ip: str) -> List[Peer]:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            return sorted(
                [peer for peer in self._peers.values() if peer.ip != exclude_ip],
                key=lambda p: (p.name.lower(), p.ip),
            )

    def list_peers_v2(self, exclude_client_id: str) -> List[Peer]:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            return sorted(
                [peer for cid, peer in self._peers.items() if cid != exclude_client_id],
                key=lambda p: (p.name.lower(), p.client_id),
            )

    def pop_events(self, client_id: str) -> List[str]:
        with self._lock:
            events = self._events.get(client_id, [])
            self._events[client_id] = []
            return events

    # ------------------------------------------------------------------
    # Signaling sessions
    # ------------------------------------------------------------------

    def create_call(
        self,
        caller_id: str,
        callee_id: str,
        relay_host: str,
        relay_port: int,
    ) -> CallResult:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            caller = self._peers.get(caller_id)
            callee = self._peers.get(callee_id)

            if caller is None or callee is None:
                return CallResult("", "", 0, "", error="NOT_FOUND")
            if caller_id == callee_id:
                return CallResult("", "", 0, "", error="SELF")

            if self._client_in_active_session_locked(caller_id) or self._client_in_active_session_locked(
                callee_id
            ):
                return CallResult("", "", 0, "", error="BUSY")

            session_id = secrets.token_hex(8)
            caller_token = secrets.token_hex(16)
            callee_token = secrets.token_hex(16)
            session = RelaySession(
                session_id=session_id,
                caller_id=caller_id,
                callee_id=callee_id,
                caller_token=caller_token,
                callee_token=callee_token,
                created_at=now,
                last_seen=now,
            )
            self._sessions[session_id] = session

            self._queue_event_locked(
                callee_id,
                "EVENT|INCOMING|"
                f"{session_id}|{caller_id}|{escape_field(caller.name)}|"
                f"{escape_field(relay_host)}|{relay_port}|{callee_token}",
            )

            return CallResult(
                session_id=session_id,
                relay_host=relay_host,
                relay_port=relay_port,
                caller_token=caller_token,
            )

    def reject_call(self, client_id: str, session_id: str, reason: str) -> bool:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            session = self._sessions.get(session_id)
            if session is None:
                return False
            if client_id not in (session.caller_id, session.callee_id):
                return False

            other_id = session.caller_id if client_id == session.callee_id else session.callee_id
            safe_reason = sanitize_reason(reason or "REJECTED")
            self._queue_event_locked(other_id, f"EVENT|REJECTED|{session_id}|{safe_reason}")
            self._remove_session_locked(session_id)
            return True

    def end_call(self, client_id: str, session_id: str, reason: str) -> bool:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            session = self._sessions.get(session_id)
            if session is None:
                return False
            if client_id not in (session.caller_id, session.callee_id):
                return False

            other_id = session.caller_id if client_id == session.callee_id else session.callee_id
            safe_reason = sanitize_reason(reason or "ENDED")
            self._queue_event_locked(other_id, f"EVENT|ENDED|{session_id}|{safe_reason}")
            self._remove_session_locked(session_id)
            return True

    # ------------------------------------------------------------------
    # UDP relay mapping
    # ------------------------------------------------------------------

    def bind_udp_endpoint(
        self, session_id: str, token: str, endpoint: Tuple[str, int]
    ) -> bool:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            session = self._sessions.get(session_id)
            if session is None:
                return False

            side: Optional[str] = None
            if token == session.caller_token:
                side = "caller"
                if session.caller_endpoint and session.caller_endpoint in self._endpoint_to_session:
                    self._endpoint_to_session.pop(session.caller_endpoint, None)
                session.caller_endpoint = endpoint
            elif token == session.callee_token:
                side = "callee"
                if session.callee_endpoint and session.callee_endpoint in self._endpoint_to_session:
                    self._endpoint_to_session.pop(session.callee_endpoint, None)
                session.callee_endpoint = endpoint
            else:
                return False

            session.last_seen = now
            self._endpoint_to_session[endpoint] = (session_id, side)
            return True

    def resolve_udp_target(
        self, source: Tuple[str, int]
    ) -> Optional[Tuple[str, int]]:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            mapping = self._endpoint_to_session.get(source)
            if mapping is None:
                return None
            session_id, side = mapping
            session = self._sessions.get(session_id)
            if session is None:
                self._endpoint_to_session.pop(source, None)
                return None

            session.last_seen = now
            if side == "caller":
                return session.callee_endpoint
            return session.caller_endpoint

    def try_autobind_udp_endpoint(
        self, endpoint: Tuple[str, int]
    ) -> Optional[Tuple[str, str]]:
        """Fallback binding for clients that do not send LCHELLO."""
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            existing = self._endpoint_to_session.get(endpoint)
            if existing is not None:
                return existing

            source_ip, _ = endpoint
            matches: List[Tuple[str, RelaySession, str]] = []
            for session_id, session in self._sessions.items():
                caller_peer = self._peers.get(session.caller_id)
                callee_peer = self._peers.get(session.callee_id)

                if (
                    session.caller_endpoint is None
                    and caller_peer is not None
                    and caller_peer.ip == source_ip
                ):
                    matches.append((session_id, session, "caller"))
                if (
                    session.callee_endpoint is None
                    and callee_peer is not None
                    and callee_peer.ip == source_ip
                ):
                    matches.append((session_id, session, "callee"))

            if len(matches) != 1:
                return None

            session_id, session, side = matches[0]
            if side == "caller":
                if session.caller_endpoint and session.caller_endpoint in self._endpoint_to_session:
                    self._endpoint_to_session.pop(session.caller_endpoint, None)
                session.caller_endpoint = endpoint
            else:
                if session.callee_endpoint and session.callee_endpoint in self._endpoint_to_session:
                    self._endpoint_to_session.pop(session.callee_endpoint, None)
                session.callee_endpoint = endpoint

            session.last_seen = now
            self._endpoint_to_session[endpoint] = (session_id, side)
            return (session_id, side)

    # ------------------------------------------------------------------
    # Internal helpers
    # ------------------------------------------------------------------

    def _client_in_active_session_locked(self, client_id: str) -> bool:
        return any(
            s.caller_id == client_id or s.callee_id == client_id
            for s in self._sessions.values()
        )

    def _queue_event_locked(self, client_id: str, line: str) -> None:
        if client_id not in self._events:
            self._events[client_id] = []
        self._events[client_id].append(line)

    def _remove_session_locked(self, session_id: str) -> None:
        session = self._sessions.pop(session_id, None)
        if session is None:
            return
        if session.caller_endpoint is not None:
            self._endpoint_to_session.pop(session.caller_endpoint, None)
        if session.callee_endpoint is not None:
            self._endpoint_to_session.pop(session.callee_endpoint, None)

    def _cleanup_locked(self, now: float) -> None:
        stale_clients = [
            client_id
            for client_id, peer in self._peers.items()
            if now - peer.last_seen > self.timeout_sec
        ]
        if stale_clients:
            for client_id in stale_clients:
                self._peers.pop(client_id, None)
                self._events.pop(client_id, None)

            stale_client_set = set(stale_clients)
            stale_sessions = [
                session_id
                for session_id, session in self._sessions.items()
                if session.caller_id in stale_client_set or session.callee_id in stale_client_set
            ]
            for session_id in stale_sessions:
                session = self._sessions.get(session_id)
                if session is not None:
                    if session.caller_id not in stale_client_set:
                        self._queue_event_locked(
                            session.caller_id, f"EVENT|ENDED|{session_id}|DISCONNECTED"
                        )
                    if session.callee_id not in stale_client_set:
                        self._queue_event_locked(
                            session.callee_id, f"EVENT|ENDED|{session_id}|DISCONNECTED"
                        )
                self._remove_session_locked(session_id)

        stale_sessions_timeout = [
            session_id
            for session_id, session in self._sessions.items()
            if now - session.last_seen > self.session_timeout_sec
        ]
        for session_id in stale_sessions_timeout:
            session = self._sessions.get(session_id)
            if session is not None:
                self._queue_event_locked(session.caller_id, f"EVENT|ENDED|{session_id}|TIMEOUT")
                self._queue_event_locked(session.callee_id, f"EVENT|ENDED|{session_id}|TIMEOUT")
            self._remove_session_locked(session_id)


@dataclass
class UpnpMapping:
    protocol: str
    external_port: int
    internal_port: int
    description: str


class UpnpPortMapper:
    _SSDP_ADDR = ("239.255.255.250", 1900)
    _SERVICE_TYPES = (
        "urn:schemas-upnp-org:service:WANIPConnection:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
        "urn:schemas-upnp-org:service:WANPPPConnection:1",
    )

    def __init__(self, config: ServerConfig):
        self.config = config
        self.control_url: Optional[str] = None
        self.service_type: Optional[str] = None
        self.local_ip: Optional[str] = None
        self.mappings: List[UpnpMapping] = []

    def setup(self, tcp_port: int, udp_port: int) -> None:
        if not self.config.enable_upnp:
            return

        if not self._discover_gateway():
            logging.warning("UPnP: no IGD gateway found, skipping automatic NAT mapping")
            return

        self.local_ip = detect_local_ip()
        if not self.local_ip:
            logging.warning("UPnP: unable to determine local IP, skipping mappings")
            return

        if self.config.upnp_map_tcp:
            self._add_mapping("TCP", tcp_port, tcp_port)
        if self.config.upnp_map_udp:
            self._add_mapping("UDP", udp_port, udp_port)

        if self.mappings:
            external_ip = self.get_external_ip()
            if external_ip:
                logging.info("UPnP: external IP detected: %s", external_ip)

    def teardown(self) -> None:
        if not self.control_url or not self.service_type:
            return

        for mapping in list(self.mappings):
            try:
                ok = self._delete_port_mapping(mapping.protocol, mapping.external_port)
                if ok:
                    logging.info(
                        "UPnP: removed mapping %s %s -> %s",
                        mapping.protocol,
                        mapping.external_port,
                        mapping.internal_port,
                    )
            except Exception:
                continue
        self.mappings.clear()

    def _discover_gateway(self) -> bool:
        timeout = max(1, int(self.config.upnp_discovery_timeout_sec))
        responses: List[str] = []

        for service_type in self._SERVICE_TYPES:
            request = (
                "M-SEARCH * HTTP/1.1\r\n"
                f"HOST: {self._SSDP_ADDR[0]}:{self._SSDP_ADDR[1]}\r\n"
                'MAN: "ssdp:discover"\r\n'
                "MX: 2\r\n"
                f"ST: {service_type}\r\n"
                "\r\n"
            ).encode("utf-8")

            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            sock.settimeout(timeout)
            try:
                sock.sendto(request, self._SSDP_ADDR)
                start = time.time()
                while time.time() - start < timeout:
                    try:
                        data, _ = sock.recvfrom(4096)
                    except socket.timeout:
                        break
                    text = data.decode("utf-8", errors="ignore")
                    responses.append(text)
            except OSError:
                continue
            finally:
                sock.close()

        locations = []
        for raw in responses:
            headers = parse_ssdp_headers(raw)
            location = headers.get("location")
            if location and location not in locations:
                locations.append(location)

        for location in locations:
            discovered = self._discover_control_url(location)
            if discovered is None:
                continue
            self.control_url, self.service_type = discovered
            logging.info("UPnP: discovered gateway control URL %s", self.control_url)
            return True

        return False

    def _discover_control_url(self, location: str) -> Optional[Tuple[str, str]]:
        try:
            with urllib.request.urlopen(location, timeout=self.config.socket_timeout_sec) as resp:
                xml_data = resp.read()
        except Exception:
            return None

        try:
            root = ET.fromstring(xml_data)
        except ET.ParseError:
            return None

        for service in root.findall(".//{*}service"):
            service_type = service.findtext("{*}serviceType", "").strip()
            if service_type not in self._SERVICE_TYPES:
                continue
            control_url = service.findtext("{*}controlURL", "").strip()
            if not control_url:
                continue
            absolute_control_url = urllib.parse.urljoin(location, control_url)
            return absolute_control_url, service_type

        return None

    def _add_mapping(self, protocol: str, external_port: int, internal_port: int) -> None:
        if not self.control_url or not self.service_type or not self.local_ip:
            return

        lease = max(0, int(self.config.upnp_lease_duration_sec))
        description = f"{self.config.upnp_description}-{protocol}:{external_port}"[:64]
        ok = self._soap_action(
            "AddPortMapping",
            {
                "NewRemoteHost": "",
                "NewExternalPort": str(external_port),
                "NewProtocol": protocol,
                "NewInternalPort": str(internal_port),
                "NewInternalClient": self.local_ip,
                "NewEnabled": "1",
                "NewPortMappingDescription": description,
                "NewLeaseDuration": str(lease),
            },
        )
        if ok:
            self.mappings.append(
                UpnpMapping(
                    protocol=protocol,
                    external_port=external_port,
                    internal_port=internal_port,
                    description=description,
                )
            )
            logging.info("UPnP: mapped %s %s -> %s", protocol, external_port, internal_port)
        else:
            logging.warning("UPnP: failed to map %s %s", protocol, external_port)

    def _delete_port_mapping(self, protocol: str, external_port: int) -> bool:
        return self._soap_action(
            "DeletePortMapping",
            {
                "NewRemoteHost": "",
                "NewExternalPort": str(external_port),
                "NewProtocol": protocol,
            },
        )

    def get_external_ip(self) -> Optional[str]:
        response = self._soap_action(
            "GetExternalIPAddress",
            {},
            return_xml=True,
        )
        if response is None:
            return None
        for elem in response.iter():
            if elem.tag.endswith("NewExternalIPAddress"):
                value = (elem.text or "").strip()
                if value:
                    return value
        return None

    def _soap_action(
        self,
        action: str,
        args: Dict[str, str],
        return_xml: bool = False,
    ) -> Union[Optional[ET.Element], bool]:
        if not self.control_url or not self.service_type:
            return None if return_xml else False

        args_xml = "".join(f"<{key}>{escape_xml(value)}</{key}>" for key, value in args.items())
        body = (
            '<?xml version="1.0"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
            's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
            "<s:Body>"
            f"<u:{action} xmlns:u=\"{self.service_type}\">{args_xml}</u:{action}>"
            "</s:Body>"
            "</s:Envelope>"
        ).encode("utf-8")

        request = urllib.request.Request(
            self.control_url,
            data=body,
            headers={
                "Content-Type": 'text/xml; charset="utf-8"',
                "SOAPAction": f'"{self.service_type}#{action}"',
            },
            method="POST",
        )

        try:
            with urllib.request.urlopen(request, timeout=self.config.socket_timeout_sec) as resp:
                response_data = resp.read()
        except Exception:
            return None if return_xml else False

        if return_xml:
            try:
                return ET.fromstring(response_data)
            except ET.ParseError:
                return None
        return True


class UdpRelay:
    HELLO_PREFIX = b"LCHELLO|"

    def __init__(self, host: str, port: int, registry: PeerRegistry, socket_timeout_sec: int):
        self.host = host
        self.port = port
        self.registry = registry
        self.socket_timeout_sec = socket_timeout_sec
        self._sock: Optional[socket.socket] = None
        self._thread: Optional[threading.Thread] = None
        self._stop = threading.Event()

    def start(self) -> None:
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self._sock.bind((self.host, self.port))
        self._sock.settimeout(max(0.2, float(self.socket_timeout_sec)))
        self._thread = threading.Thread(target=self._loop, name="udp-relay", daemon=True)
        self._thread.start()
        logging.info("UDP relay listening on %s:%s", self.host, self.port)

    def stop(self) -> None:
        self._stop.set()
        if self._sock:
            try:
                self._sock.close()
            except OSError:
                pass
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.0)

    def _loop(self) -> None:
        sock = self._sock
        if sock is None:
            return
        while not self._stop.is_set():
            try:
                data, source = sock.recvfrom(4096)
            except socket.timeout:
                continue
            except OSError:
                break

            if not data:
                continue

            if data.startswith(self.HELLO_PREFIX):
                self._handle_hello(data, source)
                continue

            target = self.registry.resolve_udp_target(source)
            if target is None:
                auto_bound = self.registry.try_autobind_udp_endpoint(source)
                if auto_bound is not None:
                    session_id, side = auto_bound
                    logging.debug(
                        "UDP endpoint auto-bound: session=%s side=%s source=%s",
                        session_id,
                        side,
                        source,
                    )
                    target = self.registry.resolve_udp_target(source)
            if target is None:
                continue
            try:
                sock.sendto(data, target)
            except OSError:
                continue

    def _handle_hello(self, data: bytes, source: Tuple[str, int]) -> None:
        try:
            text = data.decode("utf-8", errors="ignore").strip()
            parts = text.split("|")
            if len(parts) < 3:
                return
            session_id = parts[1]
            token = parts[2]
        except Exception:
            return

        ok = self.registry.bind_udp_endpoint(session_id, token, source)
        if ok:
            logging.debug("UDP endpoint bound: session=%s source=%s", session_id, source)
        else:
            logging.debug("UDP endpoint rejected: session=%s source=%s", session_id, source)


class ThreadedTCPServer(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True


class LocalCallHandler(socketserver.StreamRequestHandler):
    server: "LocalCallServer"

    def handle(self) -> None:
        self.connection.settimeout(self.server.config.socket_timeout_sec)

        try:
            raw_line = self.rfile.readline(8192)
        except Exception as exc:
            logging.debug("Read failed from %s: %s", self.client_address, exc)
            return

        if not raw_line:
            return

        line = raw_line.decode("utf-8", errors="replace").strip()
        cmd, payload = parse_command(line)
        logging.debug("TCP %s cmd=%s", self.client_address, cmd)

        if cmd == "REGISTER2":
            self._handle_register_v2(payload)
            return
        if cmd == "CALL2":
            self._handle_call_v2(payload)
            return
        if cmd == "REJECT2":
            self._handle_reject_v2(payload)
            return
        if cmd == "BYE2":
            self._handle_bye_v2(payload)
            return
        if cmd == "REGISTER":
            self._handle_register_legacy(payload)
            return

        self._write_line("ERROR|UNKNOWN_COMMAND")
        self._write_line("END")

    # ------------------------------------------------------------------
    # Legacy mode
    # ------------------------------------------------------------------

    def _handle_register_legacy(self, payload: List[str]) -> None:
        if len(payload) < 3:
            self._write_line("END")
            return

        raw_name, advertised_ip, raw_call_port = payload[0], payload[1], payload[2]

        name = sanitize_name(raw_name)
        call_port = parse_port(raw_call_port, DEFAULT_CALL_PORT)
        fallback_ip = self.client_address[0]
        ip = normalize_ip(advertised_ip, fallback_ip)

        self.server.registry.register_legacy(name=name, ip=ip, call_port=call_port)
        peers = self.server.registry.list_peers_legacy(exclude_ip=ip)

        for peer in peers:
            self._write_line(f"PEER|{peer.name}|{peer.ip}|{peer.call_port}")
        self._write_line("END")

    # ------------------------------------------------------------------
    # Relay mode
    # ------------------------------------------------------------------

    def _handle_register_v2(self, payload: List[str]) -> None:
        if len(payload) < 3:
            self._write_line("ERROR|BAD_REGISTER")
            self._write_line("END")
            return

        client_id = sanitize_client_id(payload[0])
        name = sanitize_name(payload[1])
        call_port = parse_port(payload[2], DEFAULT_CALL_PORT)

        observed_ip = self.client_address[0]
        self.server.registry.register_v2(
            client_id=client_id,
            name=name,
            ip=observed_ip,
            call_port=call_port,
        )
        logging.debug(
            "REGISTER2 client_id=%s ip=%s call_port=%s name=%s",
            client_id,
            observed_ip,
            call_port,
            name,
        )

        peers = self.server.registry.list_peers_v2(exclude_client_id=client_id)
        for peer in peers:
            # New format for updated app.
            self._write_line(
                f"PEER2|{peer.client_id}|{peer.name}|{peer.ip}|{peer.call_port}"
            )
            # Legacy-compatible line.
            self._write_line(f"PEER|{peer.name}|{peer.ip}|{peer.call_port}")

        for event in self.server.registry.pop_events(client_id):
            self._write_line(event)

        self._write_line("END")

    def _handle_call_v2(self, payload: List[str]) -> None:
        if len(payload) < 2:
            self._write_line("ERROR|BAD_CALL")
            self._write_line("END")
            return

        caller_id = sanitize_client_id(payload[0])
        callee_id = sanitize_client_id(payload[1])
        result = self.server.registry.create_call(
            caller_id=caller_id,
            callee_id=callee_id,
            relay_host=self.server.relay_host_for_clients,
            relay_port=self.server.config.relay_udp_port,
        )

        if result.error:
            logging.info("CALL2 failed caller=%s callee=%s error=%s", caller_id, callee_id, result.error)
            self._write_line(f"ERROR|{result.error}")
            self._write_line("END")
            return

        logging.info(
            "CALL2 session=%s caller=%s callee=%s relay=%s:%s",
            result.session_id,
            caller_id,
            callee_id,
            result.relay_host or "-",
            result.relay_port,
        )
        self._write_line(
            f"SESSION|{result.session_id}|{result.relay_host}|{result.relay_port}|{result.caller_token}"
        )
        self._write_line("END")

    def _handle_reject_v2(self, payload: List[str]) -> None:
        if len(payload) < 2:
            self._write_line("ERROR|BAD_REJECT")
            self._write_line("END")
            return

        client_id = sanitize_client_id(payload[0])
        session_id = sanitize_session_id(payload[1])
        reason = payload[2] if len(payload) > 2 else "REJECTED"
        ok = self.server.registry.reject_call(client_id, session_id, reason)
        self._write_line("OK" if ok else "ERROR|NOT_FOUND")
        self._write_line("END")

    def _handle_bye_v2(self, payload: List[str]) -> None:
        if len(payload) < 2:
            self._write_line("ERROR|BAD_BYE")
            self._write_line("END")
            return

        client_id = sanitize_client_id(payload[0])
        session_id = sanitize_session_id(payload[1])
        reason = payload[2] if len(payload) > 2 else "ENDED"
        ok = self.server.registry.end_call(client_id, session_id, reason)
        self._write_line("OK" if ok else "ERROR|NOT_FOUND")
        self._write_line("END")

    def _write_line(self, value: str) -> None:
        self.wfile.write((value + "\n").encode("utf-8"))


class LocalCallServer(ThreadedTCPServer):
    def __init__(self, server_address: Tuple[str, int], config: ServerConfig):
        super().__init__(server_address, LocalCallHandler)
        self.config = config
        self.registry = PeerRegistry(
            timeout_sec=config.client_timeout_sec,
            session_timeout_sec=config.session_timeout_sec,
        )
        self.relay_host_for_clients = config.relay_host_for_clients.strip()
        self.udp_relay = UdpRelay(
            host=config.relay_udp_host,
            port=config.relay_udp_port,
            registry=self.registry,
            socket_timeout_sec=config.socket_timeout_sec,
        )


def load_config(path: Path) -> ServerConfig:
    data = json.loads(path.read_text(encoding="utf-8-sig"))
    host = str(data.get("host", "0.0.0.0"))
    port = parse_port(str(data.get("port", 45700)), 45700)
    timeout = int(data.get("client_timeout_sec", 12))
    socket_timeout = int(data.get("socket_timeout_sec", 5))
    log_level = str(data.get("log_level", "INFO")).upper()
    log_file = str(data.get("log_file", "")).strip()
    relay_udp_host = str(data.get("relay_udp_host", host))
    relay_udp_port = parse_port(str(data.get("relay_udp_port", DEFAULT_RELAY_UDP_PORT)), DEFAULT_RELAY_UDP_PORT)
    relay_host_for_clients = str(data.get("relay_host_for_clients", "")).strip()
    session_timeout = int(data.get("session_timeout_sec", DEFAULT_SESSION_TIMEOUT_SEC))
    enable_upnp = parse_bool(data.get("enable_upnp", False), False)
    upnp_discovery_timeout_sec = int(data.get("upnp_discovery_timeout_sec", 3))
    upnp_lease_duration_sec = int(data.get("upnp_lease_duration_sec", 0))
    upnp_description = str(data.get("upnp_description", "LocalCallRelay")).strip() or "LocalCallRelay"
    upnp_map_tcp = parse_bool(data.get("upnp_map_tcp", True), True)
    upnp_map_udp = parse_bool(data.get("upnp_map_udp", True), True)

    if timeout < 2:
        timeout = 2
    if socket_timeout < 1:
        socket_timeout = 1
    if session_timeout < 10:
        session_timeout = 10
    if upnp_discovery_timeout_sec < 1:
        upnp_discovery_timeout_sec = 1
    if upnp_lease_duration_sec < 0:
        upnp_lease_duration_sec = 0

    return ServerConfig(
        host=host,
        port=port,
        client_timeout_sec=timeout,
        socket_timeout_sec=socket_timeout,
        log_level=log_level,
        log_file=log_file,
        relay_udp_host=relay_udp_host,
        relay_udp_port=relay_udp_port,
        relay_host_for_clients=relay_host_for_clients,
        session_timeout_sec=session_timeout,
        enable_upnp=enable_upnp,
        upnp_discovery_timeout_sec=upnp_discovery_timeout_sec,
        upnp_lease_duration_sec=upnp_lease_duration_sec,
        upnp_description=upnp_description,
        upnp_map_tcp=upnp_map_tcp,
        upnp_map_udp=upnp_map_udp,
    )


def parse_command(line: str) -> Tuple[str, List[str]]:
    parts = line.split("|")
    if not parts:
        return "", []
    return parts[0].upper(), parts[1:]


def parse_ssdp_headers(raw_response: str) -> Dict[str, str]:
    headers: Dict[str, str] = {}
    for line in raw_response.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        headers[key.strip().lower()] = value.strip()
    return headers


def detect_local_ip() -> Optional[str]:
    test_targets = ("8.8.8.8", "1.1.1.1")
    for target in test_targets:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect((target, 80))
            ip = sock.getsockname()[0]
            if ip:
                return ip
        except OSError:
            continue
        finally:
            sock.close()

    try:
        hostname_ip = socket.gethostbyname(socket.gethostname())
        if hostname_ip and not hostname_ip.startswith("127."):
            return hostname_ip
    except OSError:
        return None
    return None


def parse_bool(raw: object, default: bool) -> bool:
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return bool(raw)
    if isinstance(raw, str):
        value = raw.strip().lower()
        if value in {"1", "true", "yes", "on"}:
            return True
        if value in {"0", "false", "no", "off"}:
            return False
    return default


def escape_xml(value: str) -> str:
    return (
        str(value)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace('"', "&quot;")
        .replace("'", "&apos;")
    )


def sanitize_name(name: str) -> str:
    cleaned = name.replace("|", " ").replace("\n", " ").strip()
    return cleaned or "Unknown"


def sanitize_client_id(client_id: str) -> str:
    cleaned = "".join(ch for ch in client_id if ch.isalnum() or ch in ("-", "_", ".")).strip()
    return cleaned or "unknown"


def sanitize_session_id(session_id: str) -> str:
    cleaned = "".join(ch for ch in session_id if ch.isalnum()).strip()
    return cleaned


def sanitize_reason(reason: str) -> str:
    cleaned = reason.replace("|", "_").replace("\n", " ").strip()
    return cleaned[:64] if cleaned else "ENDED"


def escape_field(value: str) -> str:
    return value.replace("|", " ").replace("\n", " ").strip()


def parse_port(raw_port: str, default: int) -> int:
    try:
        value = int(raw_port)
    except ValueError:
        return default
    if 1 <= value <= 65535:
        return value
    return default


def normalize_ip(advertised_ip: str, fallback_ip: str) -> str:
    advertised_obj = None
    fallback_obj = None
    try:
        advertised_obj = ipaddress.ip_address(advertised_ip)
    except ValueError:
        advertised_obj = None

    try:
        fallback_obj = ipaddress.ip_address(fallback_ip)
    except ValueError:
        fallback_obj = None

    if fallback_obj is None and advertised_obj is None:
        return fallback_ip or advertised_ip
    if fallback_obj is None:
        return str(advertised_obj)
    if advertised_obj is None:
        return str(fallback_obj)

    if fallback_obj.is_global:
        return str(fallback_obj)
    if advertised_obj.is_global:
        return str(advertised_obj)
    return str(fallback_obj)


def setup_logging(level: str, log_file: str = "") -> None:
    numeric_level = getattr(logging, level.upper(), logging.INFO)
    handlers = [logging.StreamHandler()]
    if log_file:
        try:
            handlers.append(logging.FileHandler(log_file, encoding="utf-8"))
        except Exception:
            pass
    root = logging.getLogger()
    if root.handlers:
        root.handlers.clear()
    logging.basicConfig(
        level=numeric_level,
        format="%(asctime)s [%(levelname)s] %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=handlers,
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="LocalCall relay server")
    parser.add_argument(
        "--config",
        type=Path,
        default=DEFAULT_CONFIG_PATH,
        help="Path to config.json",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    config_path = args.config.resolve()
    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {config_path}")

    config = load_config(config_path)
    setup_logging(config.log_level, config.log_file)

    server = LocalCallServer((config.host, config.port), config)
    server.udp_relay.start()
    upnp_mapper = UpnpPortMapper(config)
    upnp_mapper.setup(tcp_port=config.port, udp_port=config.relay_udp_port)

    if not server.relay_host_for_clients:
        external_ip = upnp_mapper.get_external_ip() if config.enable_upnp else None
        if external_ip:
            server.relay_host_for_clients = external_ip
            logging.info("Relay host for clients set to external IP %s", external_ip)
        else:
            logging.warning(
                "relay_host_for_clients is empty; clients will use their configured server IP"
            )

    logging.info("LocalCall TCP server started on %s:%s", config.host, config.port)
    logging.info("LocalCall UDP relay started on %s:%s", config.relay_udp_host, config.relay_udp_port)
    logging.info("Client timeout: %ss, session timeout: %ss", config.client_timeout_sec, config.session_timeout_sec)

    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        logging.info("Stopping server...")
    except Exception:
        logging.exception("Server stopped due to unexpected error")
    finally:
        server.udp_relay.stop()
        upnp_mapper.teardown()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
