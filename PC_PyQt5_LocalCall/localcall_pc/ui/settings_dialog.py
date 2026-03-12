from __future__ import annotations

from typing import Optional

from PyQt5 import QtCore, QtWidgets

from ..audio_engine import query_audio_devices
from ..models import AppSettings, SERVER_DEFAULT_PORT
from ..utils import is_valid_host, parse_server_endpoint


class SettingsDialog(QtWidgets.QDialog):
    def __init__(self, settings: AppSettings, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Настройки")
        self.setMinimumSize(640, 720)
        self._settings = AppSettings.from_dict(settings.to_dict())
        self.result_settings: Optional[AppSettings] = None

        root = QtWidgets.QVBoxLayout(self)

        scroll = QtWidgets.QScrollArea()
        scroll.setWidgetResizable(True)
        container = QtWidgets.QWidget()
        form = QtWidgets.QVBoxLayout(container)
        form.setSpacing(12)

        form.addWidget(self._build_general_group())
        form.addWidget(self._build_audio_group())
        form.addWidget(self._build_network_group())
        form.addWidget(self._build_gain_group())
        form.addWidget(self._build_bg_group())
        form.addStretch(1)
        scroll.setWidget(container)
        root.addWidget(scroll)

        buttons = QtWidgets.QDialogButtonBox(
            QtWidgets.QDialogButtonBox.Save | QtWidgets.QDialogButtonBox.Cancel
        )
        buttons.accepted.connect(self._save)
        buttons.rejected.connect(self.reject)
        root.addWidget(buttons)

        self._load_values()
        self._refresh_bt_visibility()

    def _build_general_group(self) -> QtWidgets.QGroupBox:
        box = QtWidgets.QGroupBox("Общие")
        layout = QtWidgets.QFormLayout(box)

        self.et_device_name = QtWidgets.QLineEdit()
        self.et_server_ip = QtWidgets.QLineEdit()
        self.et_server_port = QtWidgets.QLineEdit()
        self.et_server_port.setPlaceholderText(str(SERVER_DEFAULT_PORT))

        layout.addRow("Имя устройства:", self.et_device_name)
        layout.addRow("IP/домен сервера:", self.et_server_ip)
        layout.addRow("Порт сервера:", self.et_server_port)
        return box

    def _build_audio_group(self) -> QtWidgets.QGroupBox:
        box = QtWidgets.QGroupBox("Аудио")
        layout = QtWidgets.QFormLayout(box)

        self.cb_mic_type = QtWidgets.QComboBox()
        self.cb_mic_type.addItems(
            [
                "Системный микрофон",
                "Bluetooth-гарнитура (микрофон)",
            ]
        )
        self.cb_mic_bt = QtWidgets.QComboBox()

        self.cb_spk_type = QtWidgets.QComboBox()
        self.cb_spk_type.addItems(
            [
                "Наушник (у уха)",
                "Динамик (громкая связь)",
                "Bluetooth-гарнитура (динамик)",
            ]
        )
        self.cb_spk_bt = QtWidgets.QComboBox()

        self.switch_custom_ns = QtWidgets.QCheckBox("Улучшенное шумоподавление")
        self.switch_custom_ns.setChecked(True)

        layout.addRow("Источник микрофона:", self.cb_mic_type)
        layout.addRow("Bluetooth-микрофон:", self.cb_mic_bt)
        layout.addRow("Выход звука:", self.cb_spk_type)
        layout.addRow("Bluetooth-динамик:", self.cb_spk_bt)
        layout.addRow(self.switch_custom_ns)

        self.cb_mic_type.currentIndexChanged.connect(self._refresh_bt_visibility)
        self.cb_spk_type.currentIndexChanged.connect(self._refresh_bt_visibility)
        self._populate_bt_devices()
        return box

    def _build_network_group(self) -> QtWidgets.QGroupBox:
        box = QtWidgets.QGroupBox("Сеть")
        layout = QtWidgets.QVBoxLayout(box)

        self.switch_auto_discovery = QtWidgets.QCheckBox("Автопоиск устройств")
        self.switch_background_service = QtWidgets.QCheckBox("Работа в фоне")
        self.switch_auto_accept = QtWidgets.QCheckBox("Автопринятие звонков")
        self.switch_upnp = QtWidgets.QCheckBox("UPnP (клиентская попытка проброса)")

        layout.addWidget(self.switch_auto_discovery)
        layout.addWidget(self.switch_background_service)
        layout.addWidget(self.switch_auto_accept)
        layout.addWidget(self.switch_upnp)
        return box

    def _build_gain_group(self) -> QtWidgets.QGroupBox:
        box = QtWidgets.QGroupBox("Громкость")
        layout = QtWidgets.QGridLayout(box)

        self.slider_mic = QtWidgets.QSlider(QtCore.Qt.Horizontal)
        self.slider_mic.setRange(0, 100)
        self.slider_mic.setSingleStep(5)
        self.lbl_mic = QtWidgets.QLabel("80%")

        self.slider_spk = QtWidgets.QSlider(QtCore.Qt.Horizontal)
        self.slider_spk.setRange(0, 100)
        self.slider_spk.setSingleStep(5)
        self.lbl_spk = QtWidgets.QLabel("80%")

        layout.addWidget(QtWidgets.QLabel("Микрофон"), 0, 0)
        layout.addWidget(self.slider_mic, 0, 1)
        layout.addWidget(self.lbl_mic, 0, 2)
        layout.addWidget(QtWidgets.QLabel("Динамик"), 1, 0)
        layout.addWidget(self.slider_spk, 1, 1)
        layout.addWidget(self.lbl_spk, 1, 2)

        self.slider_mic.valueChanged.connect(lambda v: self.lbl_mic.setText(f"{v}%"))
        self.slider_spk.valueChanged.connect(lambda v: self.lbl_spk.setText(f"{v}%"))
        return box

    def _build_bg_group(self) -> QtWidgets.QGroupBox:
        box = QtWidgets.QGroupBox("Справка")
        layout = QtWidgets.QVBoxLayout(box)
        text = QtWidgets.QLabel(
            "Настройки совместимы с Android LocalCall:\n"
            "- discovery: UDP 45677/45678\n"
            "- signaling: TCP 45680\n"
            "- audio: UDP 45679\n"
            "- relay server: REGISTER2/CALL2/SESSION/EVENT"
        )
        text.setStyleSheet("color:#555;")
        layout.addWidget(text)
        return box

    def _populate_bt_devices(self) -> None:
        self.cb_mic_bt.clear()
        self.cb_spk_bt.clear()
        self._mic_options: list[tuple[str, str]] = []
        self._spk_options: list[tuple[str, str]] = []

        mic_bt: list[tuple[str, str]] = []
        mic_other: list[tuple[str, str]] = []
        spk_bt_high: list[tuple[str, str]] = []
        spk_bt_low: list[tuple[str, str]] = []
        spk_other: list[tuple[str, str]] = []

        for device in query_audio_devices():
            name = device["name"]
            key = f"sd:{device['index']}"
            is_bt = self._is_bluetooth_like(name)
            if device["input_channels"] > 0:
                (mic_bt if is_bt else mic_other).append((name, key))
            if device["output_channels"] > 0:
                if is_bt:
                    if self._is_handsfree_profile(name):
                        spk_bt_low.append((f"{name} (hands-free, низкое качество)", key))
                    else:
                        spk_bt_high.append((name, key))
                else:
                    spk_other.append((name, key))

        self._mic_options = mic_bt + mic_other
        self._spk_options = spk_bt_high + spk_bt_low + spk_other

        if not self._mic_options:
            self._mic_options = [("Нет активных Bluetooth-микрофонов", "")]
        if not self._spk_options:
            self._spk_options = [("Нет активных Bluetooth-устройств вывода", "")]

        for label, key in self._mic_options:
            self.cb_mic_bt.addItem(label, key)
        for label, key in self._spk_options:
            self.cb_spk_bt.addItem(label, key)

    @staticmethod
    def _is_bluetooth_like(name: str) -> bool:
        text = name.lower()
        keywords = (
            "bluetooth",
            "hands-free",
            "headset",
            "airpods",
            "buds",
            "a2dp",
            "sco",
            "bt",
        )
        return any(k in text for k in keywords)

    @staticmethod
    def _is_handsfree_profile(name: str) -> bool:
        text = name.lower()
        keywords = ("hands-free", "handsfree", "hfp", "sco")
        return any(k in text for k in keywords)

    def _refresh_bt_visibility(self) -> None:
        mic_bt = self.cb_mic_type.currentIndex() == 1
        spk_bt = self.cb_spk_type.currentIndex() == 2
        self.cb_mic_bt.setVisible(mic_bt)
        self.cb_spk_bt.setVisible(spk_bt)

    def _load_values(self) -> None:
        s = self._settings
        self.et_device_name.setText(s.device_name)
        self.et_server_ip.setText(s.server_ip)
        self.et_server_port.setText(str(s.server_port))

        self.cb_mic_type.setCurrentIndex(1 if s.mic_source == "bt" else 0)
        self.cb_spk_type.setCurrentIndex(1 if s.spk_output == "speaker" else 2 if s.spk_output == "bt" else 0)
        self._select_combo_by_data(self.cb_mic_bt, s.mic_bt_addr)
        self._select_combo_by_data(self.cb_spk_bt, s.spk_bt_addr)

        self.switch_custom_ns.setChecked(s.custom_ns_enabled)
        self.switch_auto_discovery.setChecked(s.auto_discovery)
        self.switch_background_service.setChecked(s.background_service)
        self.switch_auto_accept.setChecked(s.auto_accept_calls)
        self.switch_upnp.setChecked(s.upnp_enabled)

        self.slider_mic.setValue(int(s.mic_gain))
        self.slider_spk.setValue(int(s.spk_gain))
        self.lbl_mic.setText(f"{int(s.mic_gain)}%")
        self.lbl_spk.setText(f"{int(s.spk_gain)}%")

    @staticmethod
    def _select_combo_by_data(combo: QtWidgets.QComboBox, value: str) -> None:
        if not value:
            return
        for i in range(combo.count()):
            if combo.itemData(i) == value:
                combo.setCurrentIndex(i)
                return

    def _save(self) -> None:
        endpoint_host, endpoint_port = parse_server_endpoint(self.et_server_ip.text())
        raw_port = self.et_server_port.text().strip()
        if raw_port:
            try:
                server_port = int(raw_port)
            except ValueError:
                QtWidgets.QMessageBox.warning(self, "Ошибка", "Порт сервера должен быть числом")
                return
        else:
            server_port = endpoint_port or SERVER_DEFAULT_PORT

        if not (1 <= server_port <= 65535):
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Укажите корректный порт (1..65535)")
            return

        if not is_valid_host(endpoint_host, allow_blank=True):
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Укажите корректный IP или домен сервера")
            return

        mic_source = "bt" if self.cb_mic_type.currentIndex() == 1 else "system"
        spk_output = {0: "earpiece", 1: "speaker", 2: "bt"}.get(self.cb_spk_type.currentIndex(), "earpiece")
        mic_bt_addr = self.cb_mic_bt.currentData() if mic_source == "bt" else ""
        spk_bt_addr = self.cb_spk_bt.currentData() if spk_output == "bt" else ""

        if mic_source == "bt" and not mic_bt_addr:
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Нет активного Bluetooth-микрофона")
            return
        if spk_output == "bt" and not spk_bt_addr:
            QtWidgets.QMessageBox.warning(self, "Ошибка", "Нет активного Bluetooth-устройства вывода")
            return

        updated = AppSettings.from_dict(self._settings.to_dict())
        updated.device_name = self.et_device_name.text().strip() or updated.device_name
        updated.server_ip = endpoint_host
        updated.server_port = server_port
        updated.mic_source = mic_source
        updated.mic_bt_addr = mic_bt_addr or ""
        updated.spk_output = spk_output
        updated.spk_bt_addr = spk_bt_addr or ""
        updated.custom_ns_enabled = self.switch_custom_ns.isChecked()
        updated.auto_discovery = self.switch_auto_discovery.isChecked()
        updated.background_service = self.switch_background_service.isChecked()
        updated.auto_accept_calls = self.switch_auto_accept.isChecked()
        updated.upnp_enabled = self.switch_upnp.isChecked()
        updated.mic_gain = int(self.slider_mic.value())
        updated.spk_gain = int(self.slider_spk.value())

        self.result_settings = updated
        self.accept()
