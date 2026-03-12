from __future__ import annotations

import logging
import socket
import threading
import time
from collections import deque
from typing import Optional

try:
    import numpy as np
except Exception:  # pragma: no cover - optional dependency
    np = None

try:
    import sounddevice as sd
except Exception:  # pragma: no cover - optional dependency
    sd = None

from .models import CALL_PORT


LOGGER = logging.getLogger(__name__)


def query_audio_devices() -> list[dict]:
    if sd is None:
        return []
    try:
        devices = sd.query_devices()
        result = []
        for idx, item in enumerate(devices):
            row = {
                "index": idx,
                "name": item.get("name", f"Device {idx}"),
                "input_channels": int(item.get("max_input_channels", 0)),
                "output_channels": int(item.get("max_output_channels", 0)),
            }
            result.append(row)
        return result
    except Exception:
        return []


class AudioEngine:
    SAMPLE_RATE = 24000
    CALL_PORT = CALL_PORT
    PACKET_SIZE = 1440
    JITTER_BUFFER_SIZE = 5
    RELAY_HELLO_PREFIX = "LCHELLO"

    def __init__(self) -> None:
        self.is_muted = False
        self.remote_ip = ""
        self.remote_port = self.CALL_PORT
        self.local_port = self.CALL_PORT
        self.relay_session_id: Optional[str] = None
        self.relay_token: Optional[str] = None

        self.use_mic_bluetooth = False
        self.selected_mic_bluetooth_address: Optional[str] = None
        self.use_spk_bluetooth = False
        self.selected_spk_bluetooth_address: Optional[str] = None
        self.use_speaker = False
        self.mic_gain = 80
        self.speaker_gain = 80
        self.custom_noise_suppression = True

        self.packets_sent = 0
        self.packets_received = 0
        self.packets_lost = 0

        self._send_socket: Optional[socket.socket] = None
        self._recv_socket: Optional[socket.socket] = None
        self._socket_lock = threading.Lock()

        self._recv_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()

        self._input_stream = None
        self._output_stream = None
        self._jitter_buffer: deque[bytes] = deque(maxlen=self.JITTER_BUFFER_SIZE)
        self._noise_floor = 120.0

    def update_audio_settings(
        self,
        use_mic_bluetooth: bool,
        use_spk_bluetooth: bool,
        use_speaker: bool,
        mic_gain: Optional[int] = None,
        speaker_gain: Optional[int] = None,
        custom_noise_suppression: Optional[bool] = None,
    ) -> None:
        self.use_mic_bluetooth = bool(use_mic_bluetooth)
        self.use_spk_bluetooth = bool(use_spk_bluetooth)
        self.use_speaker = bool(use_speaker)
        if mic_gain is not None:
            self.mic_gain = max(0, min(100, int(mic_gain)))
        if speaker_gain is not None:
            self.speaker_gain = max(0, min(100, int(speaker_gain)))
        if custom_noise_suppression is not None:
            self.custom_noise_suppression = bool(custom_noise_suppression)

    def start_call(
        self,
        remote_ip: str,
        remote_port: int,
        local_port: int,
        relay_session_id: Optional[str] = None,
        relay_token: Optional[str] = None,
    ) -> None:
        self.stop_call()

        self.remote_ip = remote_ip
        self.remote_port = int(remote_port)
        self.local_port = int(local_port)
        self.relay_session_id = relay_session_id
        self.relay_token = relay_token

        self.packets_sent = 0
        self.packets_received = 0
        self.packets_lost = 0
        self._jitter_buffer.clear()
        self._noise_floor = 120.0

        with self._socket_lock:
            if self._is_relay_mode():
                shared = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                shared.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                shared.bind(("", self.local_port))
                shared.settimeout(0.1)
                self._send_socket = shared
                self._recv_socket = shared
            else:
                self._send_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self._recv_socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self._recv_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                self._recv_socket.bind(("", self.local_port))
                self._recv_socket.settimeout(0.1)

        self._stop_event.clear()
        self._recv_thread = threading.Thread(target=self._receive_loop, name="audio-recv", daemon=True)
        self._recv_thread.start()

        self._start_streams()
        if self._is_relay_mode():
            self._send_relay_hello_packets()

        LOGGER.info(
            "Audio started remote=%s:%s relay=%s",
            self.remote_ip,
            self.remote_port,
            self._is_relay_mode(),
        )

    def stop_call(self) -> None:
        self._stop_event.set()
        if self._recv_thread and self._recv_thread.is_alive():
            self._recv_thread.join(timeout=1.0)
        self._recv_thread = None

        self._stop_streams()

        with self._socket_lock:
            if self._recv_socket:
                try:
                    self._recv_socket.close()
                except OSError:
                    pass
            if self._send_socket and self._send_socket is not self._recv_socket:
                try:
                    self._send_socket.close()
                except OSError:
                    pass
            self._recv_socket = None
            self._send_socket = None

        self._jitter_buffer.clear()
        self.relay_session_id = None
        self.relay_token = None

    def get_connection_quality(self) -> int:
        total = self.packets_received + self.packets_lost
        if total <= 0:
            return 3
        ratio = self.packets_lost / float(total)
        if ratio < 0.02:
            return 3
        if ratio < 0.05:
            return 2
        if ratio < 0.15:
            return 1
        return 0

    def get_connection_stats(self) -> str:
        total = self.packets_received + self.packets_lost
        loss = int((self.packets_lost * 100 / total) if total else 0)
        return f"Sent: {self.packets_sent} | Recv: {self.packets_received} | Lost: {self.packets_lost} ({loss}%)"

    def is_audio_active(self) -> bool:
        return self._input_stream is not None and self._output_stream is not None

    def _is_relay_mode(self) -> bool:
        return bool(self.relay_session_id and self.relay_token)

    def _start_streams(self) -> None:
        if sd is None:
            LOGGER.warning("sounddevice is not installed; call audio is disabled")
            return
        if np is None:
            LOGGER.warning("numpy is not installed; call audio is disabled")
            return

        frames_per_packet = self.PACKET_SIZE // 2
        in_device = _parse_device_key(self.selected_mic_bluetooth_address) if self.use_mic_bluetooth else None
        out_device = _parse_device_key(self.selected_spk_bluetooth_address) if self.use_spk_bluetooth else None

        try:
            self._input_stream = sd.RawInputStream(
                samplerate=self.SAMPLE_RATE,
                channels=1,
                dtype="int16",
                blocksize=frames_per_packet,
                device=in_device,
                callback=self._on_input,
            )
            self._output_stream = sd.RawOutputStream(
                samplerate=self.SAMPLE_RATE,
                channels=1,
                dtype="int16",
                blocksize=frames_per_packet,
                device=out_device,
                callback=self._on_output,
            )
            self._input_stream.start()
            self._output_stream.start()
            LOGGER.info("Audio devices selected: input=%s output=%s", in_device, out_device)
        except Exception as exc:
            LOGGER.warning("Audio stream init failed (selected devices): %s", exc)
            self._stop_streams()
            # Fallback to system default devices to keep calls functional.
            try:
                self._input_stream = sd.RawInputStream(
                    samplerate=self.SAMPLE_RATE,
                    channels=1,
                    dtype="int16",
                    blocksize=frames_per_packet,
                    device=None,
                    callback=self._on_input,
                )
                self._output_stream = sd.RawOutputStream(
                    samplerate=self.SAMPLE_RATE,
                    channels=1,
                    dtype="int16",
                    blocksize=frames_per_packet,
                    device=None,
                    callback=self._on_output,
                )
                self._input_stream.start()
                self._output_stream.start()
                LOGGER.info("Audio fallback selected: input=None output=None")
            except Exception as fallback_exc:
                LOGGER.warning("Audio stream fallback failed: %s", fallback_exc)
                self._stop_streams()

    def _stop_streams(self) -> None:
        for stream in (self._input_stream, self._output_stream):
            if stream is None:
                continue
            try:
                stream.stop()
            except Exception:
                pass
            try:
                stream.close()
            except Exception:
                pass
        self._input_stream = None
        self._output_stream = None

    def _on_input(self, indata, frames, timing, status) -> None:
        try:
            if self._stop_event.is_set() or self.is_muted:
                return
            if np is None:
                return
            # `indata` can be backed by a read-only buffer; copy for in-place DSP.
            pcm = np.frombuffer(bytes(indata), dtype=np.int16).copy()
            if pcm.size == 0:
                return

            if self.mic_gain != 100:
                gain = self.mic_gain / 100.0
                pcm = np.clip((pcm.astype(np.float32) * gain), -32768, 32767).astype(np.int16)

            if self.custom_noise_suppression:
                # Soft noise gate to avoid "robotic" artifacts from hard zeroing.
                abs_pcm = np.abs(pcm.astype(np.int32))
                frame_level = float(abs_pcm.mean()) if abs_pcm.size else 0.0
                self._noise_floor = (self._noise_floor * 0.97) + (min(frame_level, 1200.0) * 0.03)
                threshold = max(80.0, self._noise_floor * 1.8)
                mask = abs_pcm < threshold
                if mask.any():
                    pcm[mask] = (pcm[mask].astype(np.int32) * 0.35).astype(np.int16)

            payload = pcm.tobytes()
            with self._socket_lock:
                sock = self._send_socket
            if sock is None:
                return
            try:
                sock.sendto(payload, (self.remote_ip, self.remote_port))
                self.packets_sent += 1
            except OSError:
                self.packets_lost += 1
        except Exception as exc:
            self.packets_lost += 1
            LOGGER.debug("Audio input callback error: %s", exc)

    def _on_output(self, outdata, frames, timing, status) -> None:
        if np is None:
            outdata[:] = b"\x00" * len(outdata)
            return
        if len(self._jitter_buffer) >= 2:
            payload = self._jitter_buffer.popleft()
        else:
            payload = b"\x00" * (frames * 2)

        pcm = np.frombuffer(payload, dtype=np.int16)
        if self.speaker_gain != 100 and pcm.size > 0:
            gain = self.speaker_gain / 100.0
            pcm = np.clip((pcm.astype(np.float32) * gain), -32768, 32767).astype(np.int16)
            payload = pcm.tobytes()

        expected = len(outdata)
        if len(payload) < expected:
            payload = payload + (b"\x00" * (expected - len(payload)))
        elif len(payload) > expected:
            payload = payload[:expected]
        outdata[:] = payload

    def _receive_loop(self) -> None:
        timeout_streak = 0
        while not self._stop_event.is_set():
            with self._socket_lock:
                sock = self._recv_socket
            if sock is None:
                break
            try:
                data, _ = sock.recvfrom(self.PACKET_SIZE * 2)
            except socket.timeout:
                timeout_streak += 1
                if timeout_streak >= 4:
                    self.packets_lost += 1
                    timeout_streak = 0
                continue
            except OSError:
                break

            if not data:
                continue
            timeout_streak = 0
            self._jitter_buffer.append(data)
            self.packets_received += 1

    def _send_relay_hello_packets(self) -> None:
        if not self.relay_session_id or not self.relay_token:
            return
        payload = (
            f"{self.RELAY_HELLO_PREFIX}|{self.relay_session_id}|{self.relay_token}"
        ).encode("utf-8")

        def _send_loop() -> None:
            with self._socket_lock:
                sock = self._send_socket
            if sock is None:
                return
            for _ in range(8):
                try:
                    sock.sendto(payload, (self.remote_ip, self.remote_port))
                except OSError:
                    pass
                time.sleep(0.15)

        threading.Thread(target=_send_loop, name="relay-hello", daemon=True).start()


def _parse_device_key(raw: Optional[str]) -> Optional[int]:
    if not raw:
        return None
    if not raw.startswith("sd:"):
        return None
    try:
        return int(raw.split(":", 1)[1])
    except Exception:
        return None
