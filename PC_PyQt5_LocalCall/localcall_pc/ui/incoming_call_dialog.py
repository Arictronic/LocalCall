from __future__ import annotations

from PyQt5 import QtCore, QtWidgets

from ..models import PendingCallInfo


class IncomingCallDialog(QtWidgets.QDialog):
    def __init__(self, pending: PendingCallInfo, parent=None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Входящий звонок")
        self.setModal(True)
        self.setMinimumWidth(380)
        self.pending = pending
        self.accepted_call = False

        self.label = QtWidgets.QLabel(f"Входящий звонок от:\n{pending.remote_label}")
        self.label.setAlignment(QtCore.Qt.AlignCenter)
        self.label.setStyleSheet("font-size: 18px; font-weight: 600;")

        self.btn_accept = QtWidgets.QPushButton("Принять")
        self.btn_reject = QtWidgets.QPushButton("Отклонить")
        self.btn_accept.setStyleSheet("padding: 10px; background: #1f9d55; color: white;")
        self.btn_reject.setStyleSheet("padding: 10px; background: #d64545; color: white;")

        buttons = QtWidgets.QHBoxLayout()
        buttons.addWidget(self.btn_reject)
        buttons.addWidget(self.btn_accept)

        layout = QtWidgets.QVBoxLayout(self)
        layout.addWidget(self.label)
        layout.addSpacing(12)
        layout.addLayout(buttons)

        self.btn_accept.clicked.connect(self._accept_call)
        self.btn_reject.clicked.connect(self._reject_call)

    def _accept_call(self) -> None:
        self.accepted_call = True
        self.accept()

    def _reject_call(self) -> None:
        self.accepted_call = False
        self.reject()

