from __future__ import annotations

from typing import Optional

from PyQt5 import QtCore, QtWidgets

from ..call_manager import CallManager
from ..models import PendingCallInfo, PeerInfo
from ..utils import is_valid_host, normalize_host
from .call_window import CallWindow
from .incoming_call_dialog import IncomingCallDialog
from .settings_dialog import SettingsDialog


class MainWindow(QtWidgets.QMainWindow):
    def __init__(self, manager: CallManager) -> None:
        super().__init__()
        self.manager = manager
        self.selected_peer: Optional[PeerInfo] = None
        self._call_window: Optional[CallWindow] = None
        self._incoming_dialog: Optional[IncomingCallDialog] = None

        self.setWindowTitle("LocalCall PC (PyQt5)")
        self.resize(760, 600)

        central = QtWidgets.QWidget()
        self.setCentralWidget(central)
        layout = QtWidgets.QVBoxLayout(central)
        layout.setSpacing(10)

        top = QtWidgets.QHBoxLayout()
        self.lbl_local_ip = QtWidgets.QLabel("Ваш IP: —")
        self.lbl_local_ip.setStyleSheet("font-size: 16px; font-weight: 600;")
        self.btn_settings = QtWidgets.QPushButton("Настройки")
        top.addWidget(self.lbl_local_ip)
        top.addStretch(1)
        top.addWidget(self.btn_settings)
        layout.addLayout(top)

        direct_box = QtWidgets.QGroupBox("Подключение по IP/хосту")
        direct_layout = QtWidgets.QHBoxLayout(direct_box)
        self.et_host = QtWidgets.QLineEdit()
        self.et_host.setPlaceholderText("Например: 192.168.1.42 или my-server.com")
        self.btn_connect_host = QtWidgets.QPushButton("Позвонить")
        direct_layout.addWidget(self.et_host, 1)
        direct_layout.addWidget(self.btn_connect_host)
        layout.addWidget(direct_box)

        self.lbl_peers_title = QtWidgets.QLabel("Устройства в сети")
        self.lbl_peers_title.setStyleSheet("font-size: 14px; font-weight: 600;")
        layout.addWidget(self.lbl_peers_title)

        self.list_peers = QtWidgets.QListWidget()
        self.list_peers.setSelectionMode(QtWidgets.QAbstractItemView.SingleSelection)
        layout.addWidget(self.list_peers, 1)

        controls = QtWidgets.QHBoxLayout()
        self.btn_scan = QtWidgets.QPushButton("Сканировать")
        self.btn_connect_peer = QtWidgets.QPushButton("Подключиться")
        self.btn_connect_peer.setEnabled(False)
        controls.addWidget(self.btn_scan)
        controls.addWidget(self.btn_connect_peer)
        layout.addLayout(controls)

        self.status_bar = QtWidgets.QStatusBar()
        self.setStatusBar(self.status_bar)

        self.btn_settings.clicked.connect(self._open_settings)
        self.btn_scan.clicked.connect(self.manager.start_manual_discovery)
        self.btn_connect_peer.clicked.connect(self._connect_selected_peer)
        self.btn_connect_host.clicked.connect(self._connect_by_host)
        self.list_peers.itemSelectionChanged.connect(self._on_peer_selected)

        self.manager.peers_changed.connect(self._render_peers)
        self.manager.local_ip_changed.connect(self._on_local_ip)
        self.manager.status_message.connect(self._status)
        self.manager.call_started.connect(self._on_call_started)
        self.manager.call_ended.connect(self._on_call_ended)
        self.manager.incoming_call.connect(self._on_incoming_call)

    def _open_settings(self) -> None:
        dialog = SettingsDialog(self.manager.get_current_settings(), self)
        if dialog.exec_() == QtWidgets.QDialog.Accepted and dialog.result_settings is not None:
            self.manager.apply_settings(dialog.result_settings, save=True)

    def _connect_selected_peer(self) -> None:
        if not self.selected_peer:
            return
        self.manager.connect_to_peer(self.selected_peer)
        self._status("Подключение...")

    def _connect_by_host(self) -> None:
        host = normalize_host(self.et_host.text())
        if not host:
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Введите IP или хост")
            return
        if not is_valid_host(host):
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Укажите корректный IP или хост")
            return
        self.et_host.setText(host)
        self.manager.connect_to_host(host)
        self._status("Подключение...")

    def _on_peer_selected(self) -> None:
        item = self.list_peers.currentItem()
        if item is None:
            self.selected_peer = None
            self.btn_connect_peer.setEnabled(False)
            return
        peer = item.data(QtCore.Qt.UserRole)
        self.selected_peer = peer
        self.btn_connect_peer.setEnabled(peer is not None)

    def _render_peers(self, peers_obj) -> None:
        peers: list[PeerInfo] = peers_obj or []
        selected_key = self.selected_peer.key if self.selected_peer else None

        self.list_peers.clear()
        self.selected_peer = None

        for peer in peers:
            mode = "server" if peer.via_server else "lan"
            text = f"{peer.name}  [{mode}]  {peer.ip}:{peer.port}"
            item = QtWidgets.QListWidgetItem(text)
            item.setData(QtCore.Qt.UserRole, peer)
            self.list_peers.addItem(item)
            if selected_key and peer.key == selected_key:
                self.list_peers.setCurrentItem(item)
                self.selected_peer = peer

        self.btn_connect_peer.setEnabled(self.selected_peer is not None)
        if not peers:
            self.list_peers.addItem("Поиск устройств...")

    def _on_local_ip(self, value: str) -> None:
        self.lbl_local_ip.setText(f"Ваш IP: {value}")

    def _status(self, text: str) -> None:
        self.status_bar.showMessage(text, 5000)

    def _on_call_started(self, remote_label: str) -> None:
        if self._call_window is None:
            self._call_window = CallWindow(self.manager, remote_label, self._open_settings, self)
        else:
            self._call_window.remote_label = remote_label
            self._call_window.lbl_remote.setText(remote_label)
        self._call_window.show()
        self._call_window.raise_()
        self._call_window.activateWindow()

    def _on_call_ended(self) -> None:
        self.btn_connect_peer.setEnabled(self.selected_peer is not None)
        self.btn_connect_host.setEnabled(True)
        if self._incoming_dialog is not None and self._incoming_dialog.isVisible():
            self._incoming_dialog.done(0)
            self._incoming_dialog = None

    def _on_incoming_call(self, pending_obj) -> None:
        pending: PendingCallInfo = pending_obj
        if self._incoming_dialog is not None and self._incoming_dialog.isVisible():
            return
        self._incoming_dialog = IncomingCallDialog(pending, self)
        result = self._incoming_dialog.exec_()
        accepted = self._incoming_dialog.accepted_call
        self._incoming_dialog = None
        if result == QtWidgets.QDialog.Accepted and accepted:
            self.manager.accept_incoming_call()
        else:
            self.manager.reject_incoming_call()
