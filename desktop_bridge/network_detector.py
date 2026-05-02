"""Windows network detection utilities."""
from __future__ import annotations

import socket
import subprocess
import logging
from enum import Enum


def get_lan_ip() -> str:
    """
    Get the primary local network IP address of this machine.
    
    Returns:
        str: The IP address (e.g. "192.168.1.5") or "127.0.0.1" as fallback.
    """
    try:
        # Standard trick to get primary interface IP
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        # Doesn't actually send a packet, just picks the local IP that WOULD 
        # be used to route to this global address.
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except Exception:
        return "127.0.0.1"


class NetworkType(Enum):
    """Enum for network types."""
    WIFI = "wifi"
    HOTSPOT_USING = "hotspot_using"  # Using someone's hotspot
    HOTSPOT_PROVIDING = "hotspot_providing"  # Providing hotspot to others
    ETHERNET = "ethernet"
    UNKNOWN = "unknown"


def get_network_type() -> NetworkType:
    """
    Detect the current network type on Windows.
    
    Returns:
        NetworkType: The detected network type
    """
    try:
        # Check if this PC is providing hotspot first
        if _is_providing_hotspot():
            return NetworkType.HOTSPOT_PROVIDING
        
        # Get the active network interface and its type
        active_interface = _get_active_interface()
        if active_interface is None:
            return NetworkType.UNKNOWN
        
        # Check if it's a hotspot we're using
        if _is_using_hotspot_connection(active_interface):
            return NetworkType.HOTSPOT_USING
        
        interface_type = _get_interface_type(active_interface)
        
        if interface_type == "Wireless":
            return NetworkType.WIFI
        elif interface_type == "Ethernet":
            return NetworkType.ETHERNET
        else:
            return NetworkType.UNKNOWN
    except Exception as e:
        logging.warning(f"Error detecting network type: {e}")
        return NetworkType.UNKNOWN


def _is_providing_hotspot() -> bool:
    """Check if this PC is providing a mobile hotspot."""
    try:
        # Check if Mobile Hotspot is enabled on Windows 10+
        result = subprocess.run(
            ["netsh", "wlan", "show", "hostednetwork"],
            capture_output=True,
            text=True,
            timeout=5
        )
        
        if result.returncode == 0:
            output_lower = result.stdout.lower()
            # Look for "hosted network is enabled" or similar indicators
            if "hosted network" in output_lower and "enabled" in output_lower:
                return True
            # Also check for active status
            if "status" in output_lower and "running" in output_lower:
                return True
        
        # Alternative: check internet sharing via settings (requires more permissions)
        try:
            result = subprocess.run(
                ["netsh", "int", "ipv4", "show", "interfaces"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0:
                # Look for tethering-related interfaces
                if "mobile" in result.stdout.lower() or "hotspot" in result.stdout.lower():
                    return True
        except Exception:
            pass
        
        return False
    except Exception as e:
        logging.warning(f"Error checking if providing hotspot: {e}")
        return False


def _get_active_interface() -> str | None:
    """Get the name of the active network interface."""
    try:
        # Get the default gateway to determine active interface
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()

        # Get interface info using netsh
        result = subprocess.run(
            ["netsh", "interface", "ip", "show", "address"],
            capture_output=True,
            text=True,
            timeout=5
        )

        if result.returncode == 0:
            lines = result.stdout.split("\n")
            for i, line in enumerate(lines):
                if ip in line:
                    # Search backwards for the interface name header.
                    # Real netsh output uses "Configuration for interface" (lowercase i)
                    # so we match case-insensitively.
                    for j in range(max(0, i - 5), i):
                        if "interface" in lines[j].lower():
                            # Extract the name from: 'Configuration for interface "Wi-Fi"'
                            # or the older: 'Interface Name: Wi-Fi'
                            part = lines[j].split(":")[-1].strip().strip('"')
                            if part:
                                return part
        return None
    except Exception as e:
        logging.warning(f"Error getting active interface: {e}")
        return None


def _is_using_hotspot_connection(interface_name: str) -> bool:
    """Check if connected to a phone's hotspot."""
    try:
        hotspot_indicators = [
            "mobile",
            "hotspot",
            "tether",
            "adapter for",
            "virtual",
            "wifidirect",   # normalised: no hyphen/space — matches "Wi-Fi Direct", "WiFi Direct", etc.
            "miracast",
            "moto hotspot",
            "verizon",
            "at&t",
            "t-mobile"
        ]

        # Normalise: lowercase, strip hyphens and spaces for robust matching
        def _norm(s: str) -> str:
            return s.lower().replace("-", "").replace(" ", "")

        interface_norm = _norm(interface_name)

        for indicator in hotspot_indicators:
            if _norm(indicator) in interface_norm:
                return True

        # Check interface description for hotspot patterns
        try:
            result = subprocess.run(
                ["netsh", "interface", "show", "interface"],
                capture_output=True,
                text=True,
                timeout=5
            )

            if result.returncode == 0:
                output_norm = _norm(result.stdout)
                if interface_norm in output_norm:
                    # Find the line(s) that mention this interface and check for hotspot keywords
                    for line in result.stdout.splitlines():
                        if interface_name.lower() in line.lower():
                            line_norm = _norm(line)
                            for indicator in hotspot_indicators:
                                if _norm(indicator) in line_norm:
                                    return True
        except Exception:
            pass

        return False
    except Exception as e:
        logging.warning(f"Error checking hotspot connection: {e}")
        return False


def _get_interface_type(interface_name: str) -> str:
    """Get the type of the network interface."""
    try:
        result = subprocess.run(
            ["netsh", "interface", "show", "interface"],
            capture_output=True,
            text=True,
            timeout=5
        )
        
        if result.returncode == 0:
            lines = result.stdout.split("\n")
            for i, line in enumerate(lines):
                if interface_name in line:
                    # Check the full output for interface type info
                    if "wireless" in line.lower() or "wifi" in line.lower():
                        return "Wireless"
                    elif "ethernet" in line.lower():
                        return "Ethernet"
        
        return "Unknown"
    except Exception as e:
        logging.warning(f"Error getting interface type: {e}")
        return "Unknown"

