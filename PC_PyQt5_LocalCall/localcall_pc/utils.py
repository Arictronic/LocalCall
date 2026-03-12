from __future__ import annotations

import ipaddress
import re
import socket
from typing import Optional, Tuple
from urllib.parse import urlparse


DOMAIN_RE = re.compile(
    r"^(?=.{1,253}$)(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\.(?!-)[A-Za-z0-9-]{1,63}(?<!-))*$"
)


def parse_server_endpoint(raw: str) -> Tuple[str, Optional[int]]:
    text = raw.strip()
    if not text:
        return "", None
    normalized = text if "://" in text else f"tcp://{text}"
    try:
        parsed = urlparse(normalized)
        host = (parsed.hostname or "").strip()
        port = parsed.port if parsed.port and 1 <= parsed.port <= 65535 else None
        return host, port
    except Exception:
        endpoint = text.split("/", 1)[0].strip()
        if endpoint.count(":") == 1:
            host, port_raw = endpoint.split(":", 1)
            if port_raw.isdigit():
                port = int(port_raw)
                return host.strip("[] ").strip(), port if 1 <= port <= 65535 else None
        return endpoint.strip("[] ").strip(), None


def normalize_host(raw: str) -> str:
    host, _ = parse_server_endpoint(raw)
    return host


def is_valid_host(host: str, allow_blank: bool = False) -> bool:
    host = host.strip()
    if not host:
        return allow_blank
    if host.lower() == "localhost":
        return True
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return bool(DOMAIN_RE.match(host))


def get_local_ip() -> Optional[str]:
    for target in ("8.8.8.8", "1.1.1.1"):
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            sock.connect((target, 80))
            ip = sock.getsockname()[0]
            if ip:
                return ip
        except OSError:
            pass
        finally:
            sock.close()
    try:
        ip = socket.gethostbyname(socket.gethostname())
        if ip and not ip.startswith("127."):
            return ip
    except OSError:
        return None
    return None


def guess_broadcast(local_ip: Optional[str]) -> str:
    if not local_ip:
        return "255.255.255.255"
    try:
        iface = ipaddress.ip_interface(f"{local_ip}/24")
        return str(iface.network.broadcast_address)
    except Exception:
        return "255.255.255.255"


def iter_subnet_hosts(local_ip: Optional[str], max_hosts: int = 64):
    if not local_ip:
        return []
    try:
        network = ipaddress.ip_network(f"{local_ip}/24", strict=False)
        hosts = [str(h) for h in network.hosts() if str(h) != local_ip]
        return hosts[:max_hosts]
    except Exception:
        return []

