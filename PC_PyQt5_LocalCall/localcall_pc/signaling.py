from __future__ import annotations

import logging
import socket
import threading
from typing import Callable, Optional

from .models import CALL_PORT, SIGNALING_PORT, RelaySessionInfo


LOGGER = logging.getLogger(__name__)


class SignalingServer:
    SOCKET_TIMEOUT_SEC = 5.0

    def __init__(
        self,
        on_incoming_call: Callable[[str, int], None],
        on_remote_end: Callable[[str], None],
        on_remote_busy: Callable[[str], None],
    ) -> None:
        self._on_incoming_call = on_incoming_call
        self._on_remote_end = on_remote_end
        self._on_remote_busy = on_remote_busy

        self._stop_event = threading.Event()
        self._server_thread: Optional[threading.Thread] = None
        self._server_socket: Optional[socket.socket] = None

        self._relay_host = ""
        self._relay_port = 45700
        self._relay_client_id = ""
        self._relay_lock = threading.Lock()
        self._pending_relay_sessions: dict[str, RelaySessionInfo] = {}

    def start(self) -> None:
        if self._server_thread and self._server_thread.is_alive():
            return
        self._stop_event.clear()
        self._server_thread = threading.Thread(target=self._accept_loop, name="signaling-server", daemon=True)
        self._server_thread.start()

    def stop(self) -> None:
        self._stop_event.set()
        if self._server_socket:
            try:
                self._server_socket.close()
            except OSError:
                pass
        if self._server_thread and self._server_thread.is_alive():
            self._server_thread.join(timeout=1.5)
        self._server_thread = None
        self._server_socket = None

    def configure_relay(self, host: str, port: int, client_id: str) -> None:
        self._relay_host = host.strip()
        self._relay_port = int(port)
        self._relay_client_id = client_id.strip()

    def is_relay_available(self) -> bool:
        return bool(
            self._relay_host
            and self._relay_client_id
            and 1 <= int(self._relay_port) <= 65535
        )

    def take_pending_relay_session(self, target_id: str) -> Optional[RelaySessionInfo]:
        with self._relay_lock:
            return self._pending_relay_sessions.pop(target_id, None)

    def send_call_request(self, target: str, prefer_relay: bool = False) -> Optional[int]:
        if prefer_relay and self.is_relay_available():
            relay_port = self._send_relay_call_request(target)
            if relay_port is not None:
                return relay_port
        return self._send_direct_call_request(target)

    def send_bye(self, remote_ip: str) -> None:
        self._send_oneway(remote_ip, SIGNALING_PORT, "BYE\n")

    def send_busy(self, remote_ip: str) -> None:
        self._send_oneway(remote_ip, SIGNALING_PORT, "BUSY\n")

    def send_relay_reject(self, session_id: str, reason: str = "REJECTED") -> bool:
        return self._send_relay_session_command("REJECT2", session_id, reason)

    def send_relay_bye(self, session_id: str, reason: str = "ENDED") -> bool:
        return self._send_relay_session_command("BYE2", session_id, reason)

    def _accept_loop(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._server_socket = sock
        try:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", SIGNALING_PORT))
            sock.listen(16)
            sock.settimeout(1.0)
            while not self._stop_event.is_set():
                try:
                    client, addr = sock.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                threading.Thread(
                    target=self._handle_client,
                    args=(client, addr[0]),
                    name="signaling-client",
                    daemon=True,
                ).start()
        except OSError as exc:
            LOGGER.warning("Signaling server failed: %s", exc)
        finally:
            try:
                sock.close()
            except OSError:
                pass

    def _handle_client(self, client: socket.socket, remote_ip: str) -> None:
        try:
            client.settimeout(self.SOCKET_TIMEOUT_SEC)
            line = client.recv(4096).decode("utf-8", errors="ignore").strip()
            if not line:
                return

            if line.startswith("CALL"):
                remote_audio_port = self._parse_call_port(line)
                response = f"ACCEPT {CALL_PORT}\n".encode("utf-8")
                client.sendall(response)
                self._on_incoming_call(remote_ip, remote_audio_port)
                return

            if line.startswith("BYE"):
                self._on_remote_end(remote_ip)
                return

            if line.startswith("BUSY"):
                self._on_remote_busy(remote_ip)
                return
        except OSError:
            return
        finally:
            try:
                client.close()
            except OSError:
                pass

    @staticmethod
    def _parse_call_port(line: str) -> int:
        parts = line.split()
        if len(parts) >= 2:
            try:
                port = int(parts[1])
                if 1 <= port <= 65535:
                    return port
            except Exception:
                return CALL_PORT
        return CALL_PORT

    def _send_direct_call_request(self, remote_ip: str) -> Optional[int]:
        try:
            with socket.create_connection((remote_ip, SIGNALING_PORT), timeout=self.SOCKET_TIMEOUT_SEC) as sock:
                sock.settimeout(self.SOCKET_TIMEOUT_SEC)
                sock.sendall(f"CALL {CALL_PORT}\n".encode("utf-8"))
                resp = sock.recv(1024).decode("utf-8", errors="ignore").strip()
                if resp.startswith("ACCEPT"):
                    parts = resp.split()
                    if len(parts) > 1:
                        try:
                            port = int(parts[1])
                            if 1 <= port <= 65535:
                                return port
                        except Exception:
                            return CALL_PORT
                    return CALL_PORT
                return None
        except OSError:
            return None

    def _send_relay_call_request(self, target_peer_id: str) -> Optional[int]:
        host = self._relay_host
        port = self._relay_port
        client_id = self._relay_client_id
        if not host or not client_id:
            return None

        try:
            with socket.create_connection((host, port), timeout=self.SOCKET_TIMEOUT_SEC) as sock:
                sock.settimeout(self.SOCKET_TIMEOUT_SEC)
                sock.sendall(f"CALL2|{client_id}|{target_peer_id}\n".encode("utf-8"))
                lines = self._read_lines_until_end(sock)
                for line in lines:
                    parts = line.split("|")
                    if len(parts) >= 5 and parts[0] == "SESSION":
                        relay_port = int(parts[3])
                        session = RelaySessionInfo(
                            target_id=target_peer_id,
                            session_id=parts[1],
                            relay_host=(parts[2] or host).strip(),
                            relay_port=relay_port,
                            relay_token=parts[4],
                        )
                        with self._relay_lock:
                            self._pending_relay_sessions[target_peer_id] = session
                        return relay_port
                    if parts and parts[0] == "ERROR":
                        LOGGER.warning("Relay call rejected: %s", line)
                return None
        except OSError:
            return None

    def _send_relay_session_command(self, command: str, session_id: str, reason: str) -> bool:
        host = self._relay_host
        port = self._relay_port
        client_id = self._relay_client_id
        if not host or not client_id or not session_id:
            return False
        try:
            with socket.create_connection((host, port), timeout=self.SOCKET_TIMEOUT_SEC) as sock:
                sock.settimeout(self.SOCKET_TIMEOUT_SEC)
                line = f"{command}|{client_id}|{session_id}|{reason}\n"
                sock.sendall(line.encode("utf-8"))
                lines = self._read_lines_until_end(sock)
                return any(item == "OK" for item in lines)
        except OSError:
            return False

    @staticmethod
    def _send_oneway(host: str, port: int, payload: str) -> None:
        try:
            with socket.create_connection((host, port), timeout=3.0) as sock:
                sock.settimeout(3.0)
                sock.sendall(payload.encode("utf-8"))
        except OSError:
            return

    @staticmethod
    def _read_lines_until_end(sock: socket.socket) -> list[str]:
        lines: list[str] = []
        try:
            file_obj = sock.makefile("r", encoding="utf-8", errors="ignore", newline="\n")
            for raw in file_obj:
                line = raw.strip()
                if not line:
                    continue
                if line == "END":
                    break
                lines.append(line)
        except OSError:
            return lines
        return lines
