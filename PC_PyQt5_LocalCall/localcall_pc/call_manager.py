from __future__ import annotations

import logging
import threading
import time
from typing import Optional

from PyQt5 import QtCore

from .audio_engine import AudioEngine
from .discovery import LegacyPeerDiscovery, PingPongDiscovery, ServerPeerDiscovery
from .models import (
    AppSettings,
    CALL_PORT,
    PendingCallInfo,
    PeerInfo,
    ServerSessionEvent,
)
from .settings_store import SettingsStore
from .signaling import SignalingServer
from .utils import get_local_ip, is_valid_host, parse_server_endpoint


LOGGER = logging.getLogger(__name__)


class CallManager(QtCore.QObject):
    peers_changed = QtCore.pyqtSignal(object)
    local_ip_changed = QtCore.pyqtSignal(str)
    incoming_call = QtCore.pyqtSignal(object)
    call_started = QtCore.pyqtSignal(str)
    call_ended = QtCore.pyqtSignal()
    status_message = QtCore.pyqtSignal(str)

    def __init__(self, settings_store: SettingsStore) -> None:
        super().__init__()
        self._settings_store = settings_store
        self.settings = self._settings_store.load()
        self._lock = threading.RLock()

        self.audio_engine = AudioEngine()
        self.signaling_server = SignalingServer(
            on_incoming_call=self._handle_direct_incoming_call,
            on_remote_end=self._handle_remote_end,
            on_remote_busy=self._handle_remote_busy,
        )
        self.legacy_discovery = LegacyPeerDiscovery(self._on_peers_hint)
        self.ping_discovery = PingPongDiscovery(self._on_peers_hint)
        self.server_discovery = ServerPeerDiscovery(
            on_change=self._on_peers_hint,
            on_incoming=self._handle_server_incoming_call,
            on_session_event=self._handle_server_session_event,
            on_error=lambda message: self.status_message.emit(message),
        )

        self.is_call_active = False
        self.is_muted = False
        self.call_start_time = 0.0
        self.current_remote_ip = ""
        self.current_remote_port = CALL_PORT
        self.current_remote_label = ""
        self.current_relay_session_id = ""
        self.current_relay_token = ""
        self.current_peer_id = ""

        self.pending_call: Optional[PendingCallInfo] = None

        self._state_timer = QtCore.QTimer(self)
        self._state_timer.setInterval(1000)
        self._state_timer.timeout.connect(self._emit_runtime_state)

    def start(self) -> None:
        self.signaling_server.start()
        self.apply_settings(self.settings, save=False)
        self._state_timer.start()
        self.status_message.emit("Сервис LocalCall запущен")

    def shutdown(self) -> None:
        self._state_timer.stop()
        self.end_call(send_signal_to_remote=False)
        self.legacy_discovery.stop()
        self.ping_discovery.stop()
        self.server_discovery.stop()
        self.signaling_server.stop()

    def apply_settings(self, settings: AppSettings, save: bool = True) -> None:
        parsed_host, parsed_port = parse_server_endpoint(settings.server_ip)
        normalized_server_ip = parsed_host
        normalized_server_port = settings.server_port
        if parsed_port is not None:
            normalized_server_port = parsed_port

        with self._lock:
            self.settings = settings
            self.settings.server_ip = normalized_server_ip
            self.settings.server_port = normalized_server_port
            self._apply_audio_settings_locked()
            self.signaling_server.configure_relay(
                self.settings.server_ip,
                self.settings.server_port,
                self.settings.client_id,
            )
            self.legacy_discovery.update_config(settings.device_name, CALL_PORT)
            self.ping_discovery.update_config(settings.device_name, CALL_PORT)
            self.server_discovery.update_config(
                device_name=settings.device_name,
                call_port=CALL_PORT,
                server_ip=self.settings.server_ip,
                server_port=self.settings.server_port,
                client_id=self.settings.client_id,
            )

            should_run_local = settings.auto_discovery and settings.background_service
            if should_run_local:
                self.legacy_discovery.start()
                self.ping_discovery.start()
            else:
                self.legacy_discovery.stop()
                self.ping_discovery.stop()

            if (
                self.settings.server_ip
                and is_valid_host(self.settings.server_ip)
                and 1 <= self.settings.server_port <= 65535
            ):
                self.server_discovery.start()
            else:
                self.server_discovery.stop()

        if save:
            self._settings_store.save(self.settings)

        self._emit_peers()
        self._emit_runtime_state()
        self.status_message.emit("Настройки применены")

    def get_current_settings(self) -> AppSettings:
        with self._lock:
            return AppSettings.from_dict(self.settings.to_dict())

    def get_known_peers(self) -> list[PeerInfo]:
        peers_map: dict[str, PeerInfo] = {}

        for peer in self.legacy_discovery.get_known_peers():
            peers_map[peer.key] = peer
        for peer in self.ping_discovery.get_known_peers():
            peers_map.setdefault(peer.key, peer)
        for peer in self.server_discovery.get_known_peers():
            peers_map[peer.key] = peer
        return sorted(peers_map.values(), key=lambda p: (not p.via_server, p.name.lower(), p.ip))

    def start_manual_discovery(self) -> None:
        self.legacy_discovery.request_scan()
        self.ping_discovery.request_scan()
        self.server_discovery.request_refresh()
        self.status_message.emit("Сканирование запущено")

    def connect_to_peer(self, peer: PeerInfo) -> None:
        target = peer.id if peer.via_server else peer.ip
        threading.Thread(
            target=self._connect_worker,
            kwargs={
                "target": target,
                "display_label": peer.name or peer.ip,
                "prefer_relay": peer.via_server,
                "fallback_remote_ip": peer.ip,
                "peer_id": peer.id,
            },
            daemon=True,
        ).start()

    def connect_to_host(self, host: str) -> None:
        threading.Thread(
            target=self._connect_worker,
            kwargs={
                "target": host,
                "display_label": host,
                "prefer_relay": False,
                "fallback_remote_ip": host,
                "peer_id": host,
            },
            daemon=True,
        ).start()

    def accept_incoming_call(self) -> None:
        with self._lock:
            pending = self.pending_call
            if pending is None:
                return
            self.pending_call = None
        self._begin_call(
            remote_ip=pending.remote_ip,
            remote_port=pending.remote_port,
            remote_label=pending.remote_label,
            relay_session_id=pending.relay_session_id or None,
            relay_token=pending.relay_token or None,
            peer_id=pending.peer_id or None,
        )

    def reject_incoming_call(self) -> None:
        with self._lock:
            pending = self.pending_call
            self.pending_call = None
        if pending is None:
            return

        if pending.relay_session_id:
            self.signaling_server.send_relay_reject(
                pending.relay_session_id,
                reason="REJECTED",
            )
        else:
            self.signaling_server.send_busy(pending.remote_ip)
        self.call_ended.emit()
        self.status_message.emit("Входящий звонок отклонен")

    def end_call(self, send_signal_to_remote: bool = True) -> None:
        with self._lock:
            current_remote_ip = self.current_remote_ip
            current_relay_session = self.current_relay_session_id
            had_pending = self.pending_call is not None
            had_active = self.is_call_active

            self.pending_call = None
            self.is_call_active = False
            self.is_muted = False
            self.audio_engine.is_muted = False
            self.call_start_time = 0.0
            self.current_remote_ip = ""
            self.current_remote_port = CALL_PORT
            self.current_remote_label = ""
            self.current_relay_session_id = ""
            self.current_relay_token = ""
            self.current_peer_id = ""

        if not had_active and not had_pending:
            return

        if send_signal_to_remote:
            if current_relay_session:
                self.signaling_server.send_relay_bye(current_relay_session, reason="ENDED")
            elif current_remote_ip:
                self.signaling_server.send_bye(current_remote_ip)

        self.audio_engine.stop_call()
        self.call_ended.emit()
        self.status_message.emit("Звонок завершен")

    def toggle_mute(self) -> bool:
        with self._lock:
            self.is_muted = not self.is_muted
            self.audio_engine.is_muted = self.is_muted
            return self.is_muted

    def get_connection_quality(self) -> int:
        return self.audio_engine.get_connection_quality()

    def _connect_worker(
        self,
        target: str,
        display_label: str,
        prefer_relay: bool,
        fallback_remote_ip: str,
        peer_id: str,
    ) -> None:
        with self._lock:
            if self.is_call_active:
                self.status_message.emit("Сначала завершите текущий звонок")
                return

        remote_audio_port = self.signaling_server.send_call_request(target, prefer_relay=prefer_relay)
        if remote_audio_port is None:
            self.status_message.emit("Устройство не отвечает")
            return

        if prefer_relay:
            relay = self.signaling_server.take_pending_relay_session(target)
            if relay is not None:
                self._begin_call(
                    remote_ip=relay.relay_host,
                    remote_port=relay.relay_port,
                    remote_label=display_label,
                    relay_session_id=relay.session_id,
                    relay_token=relay.relay_token,
                    peer_id=peer_id or target,
                )
                return

        self._begin_call(
            remote_ip=fallback_remote_ip,
            remote_port=remote_audio_port,
            remote_label=display_label,
            peer_id=peer_id or fallback_remote_ip,
        )

    def _begin_call(
        self,
        remote_ip: str,
        remote_port: int,
        remote_label: str,
        relay_session_id: Optional[str] = None,
        relay_token: Optional[str] = None,
        peer_id: Optional[str] = None,
    ) -> None:
        with self._lock:
            if self.is_call_active:
                return
            self.pending_call = None
            self.is_call_active = True
            self.is_muted = False
            self.call_start_time = time.time()
            self.current_remote_ip = remote_ip
            self.current_remote_port = int(remote_port)
            self.current_remote_label = remote_label or remote_ip
            self.current_relay_session_id = relay_session_id or ""
            self.current_relay_token = relay_token or ""
            self.current_peer_id = peer_id or ""
            self.audio_engine.is_muted = False
            self._apply_audio_settings_locked()

        self.audio_engine.start_call(
            remote_ip=remote_ip,
            remote_port=int(remote_port),
            local_port=CALL_PORT,
            relay_session_id=relay_session_id,
            relay_token=relay_token,
        )
        if not self.audio_engine.is_audio_active():
            self.status_message.emit("Ошибка аудио: не удалось открыть устройства ввода/вывода")
        self.call_started.emit(self.current_remote_label)
        self.status_message.emit(f"Звонок с {self.current_remote_label}")

    def _apply_audio_settings_locked(self) -> None:
        mic_src = self.settings.mic_source
        spk_out = self.settings.spk_output
        mic_bt_addr = self.settings.mic_bt_addr or None
        spk_bt_addr = self.settings.spk_bt_addr or None

        use_mic_bt = mic_src == "bt" and bool(mic_bt_addr)
        use_spk_bt = spk_out == "bt" and bool(spk_bt_addr)
        use_speaker = spk_out == "speaker"

        self.audio_engine.selected_mic_bluetooth_address = mic_bt_addr
        self.audio_engine.selected_spk_bluetooth_address = spk_bt_addr
        self.audio_engine.update_audio_settings(
            use_mic_bluetooth=use_mic_bt,
            use_spk_bluetooth=use_spk_bt,
            use_speaker=use_speaker,
            mic_gain=self.settings.mic_gain,
            speaker_gain=self.settings.spk_gain,
            custom_noise_suppression=self.settings.custom_ns_enabled,
        )

    def _handle_direct_incoming_call(self, remote_ip: str, remote_port: int) -> None:
        with self._lock:
            busy = self.is_call_active
            auto_accept = self.settings.auto_accept_calls

        if busy:
            self.signaling_server.send_busy(remote_ip)
            return

        if auto_accept:
            self._begin_call(remote_ip, remote_port, remote_ip)
            return

        pending = PendingCallInfo(
            remote_label=remote_ip,
            remote_ip=remote_ip,
            remote_port=remote_port,
        )
        with self._lock:
            self.pending_call = pending
        self.incoming_call.emit(pending)
        self.status_message.emit(f"Входящий звонок: {remote_ip}")

    def _handle_server_incoming_call(self, event) -> None:
        with self._lock:
            busy = self.is_call_active
            auto_accept = self.settings.auto_accept_calls

        if busy:
            self.signaling_server.send_relay_reject(event.session_id, reason="BUSY")
            return

        label = event.caller_name or event.caller_id
        if auto_accept:
            self._begin_call(
                remote_ip=event.relay_host,
                remote_port=event.relay_port,
                remote_label=label,
                relay_session_id=event.session_id,
                relay_token=event.relay_token,
                peer_id=event.caller_id,
            )
            return

        pending = PendingCallInfo(
            remote_label=label,
            remote_ip=event.relay_host,
            remote_port=event.relay_port,
            relay_session_id=event.session_id,
            relay_token=event.relay_token,
            peer_id=event.caller_id,
        )
        with self._lock:
            self.pending_call = pending
        self.incoming_call.emit(pending)
        self.status_message.emit(f"Входящий звонок: {label}")

    def _handle_server_session_event(self, event: ServerSessionEvent) -> None:
        with self._lock:
            pending = self.pending_call
            current_session = self.current_relay_session_id
            active = self.is_call_active

        if pending and pending.relay_session_id == event.session_id:
            with self._lock:
                self.pending_call = None
            self.call_ended.emit()
            self.status_message.emit("Входящий звонок отменен удаленной стороной")
            return

        if active and current_session and current_session == event.session_id:
            self.end_call(send_signal_to_remote=False)

    def _handle_remote_end(self, remote_ip: str) -> None:
        self.end_call(send_signal_to_remote=False)

    def _handle_remote_busy(self, remote_ip: str) -> None:
        self.end_call(send_signal_to_remote=False)
        self.status_message.emit("Собеседник занят")

    def _on_peers_hint(self) -> None:
        self._emit_peers()

    def _emit_peers(self) -> None:
        self.peers_changed.emit(self.get_known_peers())

    def _emit_runtime_state(self) -> None:
        local_ip = get_local_ip() or "—"
        self.local_ip_changed.emit(local_ip)
