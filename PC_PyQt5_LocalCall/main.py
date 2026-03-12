from __future__ import annotations

import logging
import sys
from pathlib import Path

from PyQt5 import QtWidgets

from localcall_pc.call_manager import CallManager
from localcall_pc.settings_store import SettingsStore
from localcall_pc.ui.main_window import MainWindow


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%H:%M:%S",
    )


def main() -> int:
    configure_logging()

    app = QtWidgets.QApplication(sys.argv)
    app.setApplicationName("LocalCall PC")

    base_dir = Path(__file__).resolve().parent
    settings_path = base_dir / "data" / "settings.json"

    manager = CallManager(SettingsStore(settings_path))
    manager.start()

    window = MainWindow(manager)
    window.show()

    app.aboutToQuit.connect(manager.shutdown)
    return app.exec_()


if __name__ == "__main__":
    raise SystemExit(main())

