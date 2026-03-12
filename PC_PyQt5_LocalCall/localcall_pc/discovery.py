from __future__ import annotations

import logging
import socket
import threading
import time
from dataclasses import dataclass
from typing import Callable, Dict, Optional

from .models import (
    CALL_PORT,
    LEGACY_DISCOVERY_PORT,
    PING_DISCOVERY_PORT,
    PEER_PING_INTERVAL_SEC,
    IncomingCallEvent,
    PeerInfo,
    ServerSessionEvent,
)
from .utils import get_local_ip, guess_broadcast, iter_subnet_hosts


LOGGER = logging.getLogger(__name__)


@dataclass
class _PeerState:
    peer: PeerInfo
    last_seen: float


class LegacyPeerDiscovery:
    MAGIC = "LOCALCALL_PEER"
    PEER_TIMEOUT_SEC = 8.0

    def __init__(self, on_change: Callable[[], None]) -> None:
        self._on_change = on_change
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._manual_scan_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._peers: Dict[str, _PeerState] = {}
        self.device_name = "PC"
        self.call_port = CALL_PORT

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="legacy-discovery", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self._thread = None
        with self._lock:
            self._peers.clear()
        self._on_change()

    def request_scan(self) -> None:
        self._manual_scan_event.set()

    def update_config(self, device_name: str, call_port: int) -> None:
        self.device_name = device_name
        self.call_port = call_port
        self.request_scan()

    def get_known_peers(self) -> list[PeerInfo]:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            return [entry.peer for entry in self._peers.values()]

    def _run(self) -> None:
        tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            tx.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            tx.settimeout(0.2)
            rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            rx.bind(("", LEGACY_DISCOVERY_PORT))
            rx.settimeout(0.25)

            next_broadcast = 0.0
            while not self._stop_event.is_set():
                now = time.time()
                force_scan = self._manual_scan_event.is_set()
                if force_scan or now >= next_broadcast:
                    self._send_broadcast(tx)
                    if force_scan:
                        self._manual_unicast_scan(tx)
                    self._manual_scan_event.clear()
                    next_broadcast = now + PEER_PING_INTERVAL_SEC

                self._read_packet(rx)
                self._cleanup(now)
        except OSError as exc:
            LOGGER.warning("Legacy discovery stopped: %s", exc)
        finally:
            tx.close()
            rx.close()

    def _send_broadcast(self, tx: socket.socket) -> None:
        local_ip = get_local_ip() or ""
        payload = f"{self.MAGIC}|{self.device_name}|{local_ip}|{self.call_port}".encode("utf-8")
        addresses = {guess_broadcast(local_ip), "255.255.255.255"}
        for addr in addresses:
            try:
                tx.sendto(payload, (addr, LEGACY_DISCOVERY_PORT))
            except OSError:
                continue

    def _manual_unicast_scan(self, tx: socket.socket) -> None:
        local_ip = get_local_ip()
        if not local_ip:
            return
        payload = f"{self.MAGIC}|{self.device_name}|{local_ip}|{self.call_port}".encode("utf-8")
        for host in iter_subnet_hosts(local_ip, max_hosts=64):
            try:
                tx.sendto(payload, (host, LEGACY_DISCOVERY_PORT))
            except OSError:
                continue

    def _read_packet(self, rx: socket.socket) -> None:
        try:
            data, (sender_ip, _) = rx.recvfrom(1024)
        except socket.timeout:
            return
        except OSError:
            return

        try:
            text = data.decode("utf-8", errors="ignore").strip()
        except Exception:
            return
        if not text.startswith(self.MAGIC):
            return

        parts = text.split("|")
        if len(parts) < 4:
            return

        name = parts[1].strip() or "Unknown"
        advertised_ip = parts[2].strip() or sender_ip
        try:
            port = int(parts[3])
        except Exception:
            port = CALL_PORT

        local_ip = get_local_ip()
        if advertised_ip == local_ip or sender_ip == local_ip:
            return

        peer = PeerInfo(name=name, ip=advertised_ip, port=port, id=advertised_ip, via_server=False)
        changed = False
        with self._lock:
            existing = self._peers.get(peer.key)
            self._peers[peer.key] = _PeerState(peer=peer, last_seen=time.time())
            changed = existing is None
        if changed:
            self._on_change()

    def _cleanup(self, now: float) -> None:
        changed = False
        with self._lock:
            changed = self._cleanup_locked(now)
        if changed:
            self._on_change()

    def _cleanup_locked(self, now: float) -> bool:
        stale = [
            key
            for key, item in self._peers.items()
            if (now - item.last_seen) > self.PEER_TIMEOUT_SEC
        ]
        for key in stale:
            self._peers.pop(key, None)
        return bool(stale)


class PingPongDiscovery:
    PING_PREFIX = "PING"
    PONG_PREFIX = "PONG"
    PEER_TIMEOUT_SEC = 6.0

    def __init__(self, on_change: Callable[[], None]) -> None:
        self._on_change = on_change
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._manual_scan_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._peers: Dict[str, _PeerState] = {}
        self.device_name = "PC"
        self.call_port = CALL_PORT

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="pingpong-discovery", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self._thread = None
        with self._lock:
            self._peers.clear()
        self._on_change()

    def request_scan(self) -> None:
        self._manual_scan_event.set()

    def update_config(self, device_name: str, call_port: int) -> None:
        self.device_name = device_name
        self.call_port = call_port
        self.request_scan()

    def get_known_peers(self) -> list[PeerInfo]:
        now = time.time()
        with self._lock:
            self._cleanup_locked(now)
            return [entry.peer for entry in self._peers.values()]

    def _run(self) -> None:
        tx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        rx = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            tx.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            tx.settimeout(0.2)
            rx.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            rx.bind(("", PING_DISCOVERY_PORT))
            rx.settimeout(0.25)

            next_ping = 0.0
            while not self._stop_event.is_set():
                now = time.time()
                force_scan = self._manual_scan_event.is_set()
                if force_scan or now >= next_ping:
                    self._send_ping(tx)
                    if force_scan:
                        self._manual_unicast_scan(tx)
                    self._manual_scan_event.clear()
                    next_ping = now + PEER_PING_INTERVAL_SEC

                self._read_packet(tx, rx)
                self._cleanup(now)
        except OSError as exc:
            LOGGER.warning("Ping/Pong discovery stopped: %s", exc)
        finally:
            tx.close()
            rx.close()

    def _send_ping(self, tx: socket.socket) -> None:
        msg = f"{self.PING_PREFIX} {self.device_name} {self.call_port}".encode("utf-8")
        local_ip = get_local_ip() or ""
        addresses = {guess_broadcast(local_ip), "255.255.255.255"}
        for addr in addresses:
            try:
                tx.sendto(msg, (addr, PING_DISCOVERY_PORT))
            except OSError:
                continue

    def _manual_unicast_scan(self, tx: socket.socket) -> None:
        local_ip = get_local_ip()
        if not local_ip:
            return
        msg = f"{self.PING_PREFIX} {self.device_name} {self.call_port}".encode("utf-8")
        for host in iter_subnet_hosts(local_ip, max_hosts=64):
            try:
                tx.sendto(msg, (host, PING_DISCOVERY_PORT))
            except OSError:
                continue

    def _read_packet(self, tx: socket.socket, rx: socket.socket) -> None:
        try:
            data, (sender_ip, sender_port) = rx.recvfrom(1024)
        except socket.timeout:
            return
        except OSError:
            return

        text = data.decode("utf-8", errors="ignore").strip()
        parts = text.split()
        if len(parts) < 3:
            return

        local_ip = get_local_ip()
        if sender_ip == local_ip:
            return

        prefix, name, port_str = parts[0], parts[1], parts[2]
        try:
            port = int(port_str)
        except Exception:
            port = CALL_PORT

        if prefix == self.PING_PREFIX:
            reply = f"{self.PONG_PREFIX} {self.device_name} {self.call_port}".encode("utf-8")
            try:
                tx.sendto(reply, (sender_ip, sender_port))
            except OSError:
                pass
            self._touch_peer(name=name, ip=sender_ip, port=port)
        elif prefix == self.PONG_PREFIX:
            self._touch_peer(name=name, ip=sender_ip, port=port)

    def _touch_peer(self, name: str, ip: str, port: int) -> None:
        peer = PeerInfo(name=name or "Unknown", ip=ip, port=port, id=ip, via_server=False)
        changed = False
        with self._lock:
            existed = peer.key in self._peers
            self._peers[peer.key] = _PeerState(peer=peer, last_seen=time.time())
            changed = not existed
        if changed:
            self._on_change()

    def _cleanup(self, now: float) -> None:
        changed = False
        with self._lock:
            changed = self._cleanup_locked(now)
        if changed:
            self._on_change()

    def _cleanup_locked(self, now: float) -> bool:
        stale = [
            key
            for key, item in self._peers.items()
            if (now - item.last_seen) > self.PEER_TIMEOUT_SEC
        ]
        for key in stale:
            self._peers.pop(key, None)
        return bool(stale)


class ServerPeerDiscovery:
    POLL_INTERVAL_SEC = 2.0

    def __init__(
        self,
        on_change: Callable[[], None],
        on_incoming: Callable[[IncomingCallEvent], None],
        on_session_event: Callable[[ServerSessionEvent], None],
        on_error: Optional[Callable[[str], None]] = None,
    ) -> None:
        self._on_change = on_change
        self._on_incoming = on_incoming
        self._on_session_event = on_session_event
        self._on_error = on_error
        self._lock = threading.Lock()
        self._stop_event = threading.Event()
        self._refresh_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._peers: Dict[str, PeerInfo] = {}

        self.device_name = "PC"
        self.call_port = CALL_PORT
        self.server_ip = ""
        self.server_port = 45700
        self.client_id = ""
        self._last_error_ts = 0.0

    def update_config(
        self,
        device_name: str,
        call_port: int,
        server_ip: str,
        server_port: int,
        client_id: str,
    ) -> None:
        self.device_name = device_name
        self.call_port = call_port
        self.server_ip = server_ip.strip()
        self.server_port = int(server_port)
        self.client_id = client_id.strip()
        self.request_refresh()

    def request_refresh(self) -> None:
        self._refresh_event.set()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._run, name="server-discovery", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        self._refresh_event.set()
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=1.5)
        self._thread = None
        with self._lock:
            had = bool(self._peers)
            self._peers.clear()
        if had:
            self._on_change()

    def get_known_peers(self) -> list[PeerInfo]:
        with self._lock:
            return list(self._peers.values())

    def _run(self) -> None:
        while not self._stop_event.is_set():
            if self._is_configured():
                peers = self._fetch_peers()
                if peers is not None:
                    self._replace_peers(peers)
            else:
                self._replace_peers([])

            self._refresh_event.wait(timeout=self.POLL_INTERVAL_SEC)
            self._refresh_event.clear()

    def _is_configured(self) -> bool:
        return bool(self.server_ip and self.client_id and (1 <= self.server_port <= 65535))

    def _fetch_peers(self) -> Optional[list[PeerInfo]]:
        peers: list[PeerInfo] = []
        has_peer_v2 = False
        try:
            with socket.create_connection((self.server_ip, self.server_port), timeout=3.0) as sock:
                sock.settimeout(3.0)
                line = f"REGISTER2|{self.client_id}|{self._sanitize(self.device_name)}|{self.call_port}\n"
                sock.sendall(line.encode("utf-8"))
                file_obj = sock.makefile("r", encoding="utf-8", errors="ignore", newline="\n")
                for raw in file_obj:
                    item = raw.strip()
                    if not item or item == "END":
                        break
                    if item.startswith("PEER2|"):
                        peer = self._parse_peer2(item)
                        if peer:
                            peers.append(peer)
                            has_peer_v2 = True
                        continue
                    if item.startswith("PEER|") and not has_peer_v2:
                        peer = self._parse_peer_legacy(item)
                        if peer:
                            peers.append(peer)
                        continue
                    if item.startswith("EVENT|"):
                        self._handle_event_line(item)
        except OSError as exc:
            LOGGER.warning("Server discovery poll failed: %s", exc)
            now = time.time()
            if self._on_error and (now - self._last_error_ts > 5.0):
                self._last_error_ts = now
                self._on_error(f"Нет связи с сервером {self.server_ip}:{self.server_port}")
            return None
        return peers

    def _parse_peer2(self, line: str) -> Optional[PeerInfo]:
        parts = line.split("|")
        if len(parts) < 5:
            return None
        peer_id, name, ip = parts[1], parts[2], parts[3]
        try:
            port = int(parts[4])
        except Exception:
            port = CALL_PORT
        if not peer_id or not ip or peer_id == self.client_id:
            return None
        return PeerInfo(name=name or peer_id, ip=ip, port=port, id=peer_id, via_server=True)

    def _parse_peer_legacy(self, line: str) -> Optional[PeerInfo]:
        parts = line.split("|")
        if len(parts) < 4:
            return None
        name, ip = parts[1], parts[2]
        try:
            port = int(parts[3])
        except Exception:
            port = CALL_PORT
        if not ip:
            return None
        return PeerInfo(name=name or ip, ip=ip, port=port, id=ip, via_server=True)

    def _handle_event_line(self, line: str) -> None:
        parts = line.split("|")
        if len(parts) < 3:
            return
        event_type = parts[1]
        if event_type == "INCOMING" and len(parts) >= 8:
            try:
                relay_port = int(parts[6])
            except Exception:
                return
            event = IncomingCallEvent(
                session_id=parts[2],
                caller_id=parts[3],
                caller_name=parts[4] or parts[3],
                relay_host=parts[5] or self.server_ip,
                relay_port=relay_port,
                relay_token=parts[7],
            )
            if event.session_id and event.caller_id and event.relay_token:
                self._on_incoming(event)
            return

        if event_type in ("REJECTED", "ENDED"):
            event = ServerSessionEvent(
                event_type=event_type,
                session_id=parts[2],
                reason=parts[3] if len(parts) > 3 else "",
            )
            if event.session_id:
                self._on_session_event(event)

    def _replace_peers(self, peers: list[PeerInfo]) -> None:
        fresh = {peer.key: peer for peer in peers}
        changed = False
        with self._lock:
            if set(fresh.keys()) != set(self._peers.keys()):
                changed = True
            else:
                for key, peer in fresh.items():
                    old = self._peers.get(key)
                    if old is None or old.name != peer.name or old.ip != peer.ip or old.port != peer.port:
                        changed = True
                        break
            self._peers = fresh
        if changed:
            self._on_change()

    @staticmethod
    def _sanitize(raw: str) -> str:
        return raw.replace("|", " ").replace("\n", " ").strip() or "PC"
