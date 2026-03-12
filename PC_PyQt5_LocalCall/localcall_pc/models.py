from __future__ import annotations

from dataclasses import dataclass, field
import platform
import uuid


CALL_PORT = 45679
SIGNALING_PORT = 45680
LEGACY_DISCOVERY_PORT = 45678
PING_DISCOVERY_PORT = 45677
SERVER_DEFAULT_PORT = 45700
PEER_PING_INTERVAL_SEC = 2.0


@dataclass
class PeerInfo:
    name: str
    ip: str
    port: int = CALL_PORT
    id: str = ""
    via_server: bool = False

    @property
    def key(self) -> str:
        if self.via_server:
            return f"srv:{self.id or self.ip}"
        return f"ip:{self.ip}"


@dataclass
class RelaySessionInfo:
    target_id: str
    session_id: str
    relay_host: str
    relay_port: int
    relay_token: str


@dataclass
class IncomingCallEvent:
    session_id: str
    caller_id: str
    caller_name: str
    relay_host: str
    relay_port: int
    relay_token: str


@dataclass
class ServerSessionEvent:
    event_type: str
    session_id: str
    reason: str = ""


@dataclass
class PendingCallInfo:
    remote_label: str
    remote_ip: str
    remote_port: int
    relay_session_id: str = ""
    relay_token: str = ""
    peer_id: str = ""


@dataclass
class AppSettings:
    device_name: str = field(default_factory=lambda: platform.node() or "PC")
    mic_source: str = "system"  # "system" | "bt"
    mic_bt_addr: str = ""
    spk_output: str = "earpiece"  # "earpiece" | "speaker" | "bt"
    spk_bt_addr: str = ""
    server_ip: str = ""
    server_port: int = SERVER_DEFAULT_PORT
    client_id: str = field(default_factory=lambda: f"pc-{uuid.uuid4()}")
    audio_quality: str = "high"
    aec_enabled: bool = True
    ns_enabled: bool = True
    agc_enabled: bool = True
    mic_gain: int = 80
    spk_gain: int = 80
    custom_ns_enabled: bool = True
    auto_discovery: bool = True
    background_service: bool = True
    auto_accept_calls: bool = False
    upnp_enabled: bool = True

    @classmethod
    def from_dict(cls, data: dict) -> "AppSettings":
        base = cls()
        for key in (
            "device_name",
            "mic_source",
            "mic_bt_addr",
            "spk_output",
            "spk_bt_addr",
            "server_ip",
            "server_port",
            "client_id",
            "audio_quality",
            "aec_enabled",
            "ns_enabled",
            "agc_enabled",
            "mic_gain",
            "spk_gain",
            "custom_ns_enabled",
            "auto_discovery",
            "background_service",
            "auto_accept_calls",
            "upnp_enabled",
        ):
            if key in data:
                setattr(base, key, data[key])
        base.server_port = _clamp_port(base.server_port, SERVER_DEFAULT_PORT)
        base.mic_gain = _clamp_percent(base.mic_gain)
        base.spk_gain = _clamp_percent(base.spk_gain)
        if not str(base.client_id).strip():
            base.client_id = f"pc-{uuid.uuid4()}"
        return base

    def to_dict(self) -> dict:
        return {
            "device_name": self.device_name,
            "mic_source": self.mic_source,
            "mic_bt_addr": self.mic_bt_addr,
            "spk_output": self.spk_output,
            "spk_bt_addr": self.spk_bt_addr,
            "server_ip": self.server_ip,
            "server_port": _clamp_port(self.server_port, SERVER_DEFAULT_PORT),
            "client_id": self.client_id,
            "audio_quality": self.audio_quality,
            "aec_enabled": bool(self.aec_enabled),
            "ns_enabled": bool(self.ns_enabled),
            "agc_enabled": bool(self.agc_enabled),
            "mic_gain": _clamp_percent(self.mic_gain),
            "spk_gain": _clamp_percent(self.spk_gain),
            "custom_ns_enabled": bool(self.custom_ns_enabled),
            "auto_discovery": bool(self.auto_discovery),
            "background_service": bool(self.background_service),
            "auto_accept_calls": bool(self.auto_accept_calls),
            "upnp_enabled": bool(self.upnp_enabled),
        }


def _clamp_port(value: int, default: int) -> int:
    try:
        port = int(value)
    except Exception:
        return default
    if 1 <= port <= 65535:
        return port
    return default


def _clamp_percent(value: int) -> int:
    try:
        value_int = int(value)
    except Exception:
        return 80
    return max(0, min(100, value_int))
