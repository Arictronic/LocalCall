from __future__ import annotations

import time
from typing import Callable

from PyQt5 import QtCore, QtWidgets

from ..call_manager import CallManager


class CallWindow(QtWidgets.QWidget):
    def __init__(
        self,
        manager: CallManager,
        remote_label: str,
        open_settings: Callable[[], None],
        parent=None,
    ) -> None:
        super().__init__(parent)
        self.manager = manager
        self.remote_label = remote_label
        self.open_settings = open_settings
        self.setWindowTitle("Звонок")
        self.setMinimumSize(480, 360)

        self.lbl_remote = QtWidgets.QLabel(remote_label)
        self.lbl_remote.setAlignment(QtCore.Qt.AlignCenter)
        self.lbl_remote.setStyleSheet("font-size: 24px; font-weight: 700;")

        self.lbl_state = QtWidgets.QLabel("Звонок активен")
        self.lbl_state.setAlignment(QtCore.Qt.AlignCenter)
        self.lbl_state.setStyleSheet("font-size: 14px; color: #666;")

        self.lbl_quality = QtWidgets.QLabel("Качество: отлично")
        self.lbl_quality.setAlignment(QtCore.Qt.AlignCenter)
        self.lbl_quality.setStyleSheet("font-size: 13px; color: #4caf50;")

        self.lbl_timer = QtWidgets.QLabel("00:00")
        self.lbl_timer.setAlignment(QtCore.Qt.AlignCenter)
        self.lbl_timer.setStyleSheet("font-size: 42px; font-family: Consolas, monospace; font-weight: 700;")

        self.btn_settings = QtWidgets.QPushButton("Настройки")
        self.btn_mute = QtWidgets.QPushButton("Выкл. микрофон")
        self.btn_end = QtWidgets.QPushButton("Завершить")
        self.btn_end.setStyleSheet("background: #d64545; color: white; padding: 10px;")

        top = QtWidgets.QHBoxLayout()
        top.addStretch(1)
        top.addWidget(self.btn_settings)

        controls = QtWidgets.QHBoxLayout()
        controls.addWidget(self.btn_mute)
        controls.addWidget(self.btn_end)

        root = QtWidgets.QVBoxLayout(self)
        root.addLayout(top)
        root.addStretch(1)
        root.addWidget(self.lbl_remote)
        root.addWidget(self.lbl_state)
        root.addWidget(self.lbl_quality)
        root.addSpacing(12)
        root.addWidget(self.lbl_timer)
        root.addStretch(1)
        root.addLayout(controls)

        self.btn_settings.clicked.connect(self.open_settings)
        self.btn_mute.clicked.connect(self._toggle_mute)
        self.btn_end.clicked.connect(self._end_call)

        self._timer = QtCore.QTimer(self)
        self._timer.setInterval(500)
        self._timer.timeout.connect(self._refresh_timer)
        self._timer.start()

        self._quality_timer = QtCore.QTimer(self)
        self._quality_timer.setInterval(1000)
        self._quality_timer.timeout.connect(self._refresh_quality)
        self._quality_timer.start()

        self.manager.call_ended.connect(self._on_call_ended)

    def _toggle_mute(self) -> None:
        muted = self.manager.toggle_mute()
        if muted:
            self.btn_mute.setText("Включить микрофон")
            self.btn_mute.setStyleSheet("background: #888; color: white; padding: 10px;")
        else:
            self.btn_mute.setText("Выкл. микрофон")
            self.btn_mute.setStyleSheet("")

    def _end_call(self) -> None:
        self.manager.end_call(send_signal_to_remote=True)

    def _refresh_timer(self) -> None:
        started = self.manager.call_start_time
        if started <= 0:
            self.lbl_timer.setText("00:00")
            return
        elapsed = int(time.time() - started)
        s = elapsed % 60
        m = (elapsed // 60) % 60
        h = elapsed // 3600
        if h > 0:
            self.lbl_timer.setText(f"{h:02d}:{m:02d}:{s:02d}")
        else:
            self.lbl_timer.setText(f"{m:02d}:{s:02d}")

    def _refresh_quality(self) -> None:
        quality = self.manager.get_connection_quality()
        if quality == 3:
            self.lbl_quality.setText("Качество: отлично")
            self.lbl_quality.setStyleSheet("font-size: 13px; color: #4caf50;")
        elif quality == 2:
            self.lbl_quality.setText("Качество: хорошо")
            self.lbl_quality.setStyleSheet("font-size: 13px; color: #8bc34a;")
        elif quality == 1:
            self.lbl_quality.setText("Качество: средне")
            self.lbl_quality.setStyleSheet("font-size: 13px; color: #ffc107;")
        else:
            self.lbl_quality.setText("Качество: плохо")
            self.lbl_quality.setStyleSheet("font-size: 13px; color: #f44336;")

    def _on_call_ended(self) -> None:
        self._timer.stop()
        self._quality_timer.stop()
        self.close()

    def closeEvent(self, event) -> None:
        if self.manager.is_call_active:
            self.hide()
            event.ignore()
            return
        super().closeEvent(event)

