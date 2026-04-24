"""Windows network detection utilities."""
from __future__ import annotations

import socket
import subprocess
import logging
from enum import Enum


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
                    # Find the interface name (usually a few lines before)
                    for j in range(max(0, i - 5), i):
                        if "Interface" in lines[j]:
                            return lines[j].split(":")[-1].strip()
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
            "wifi direct",
            "miracast",
            "moto hotspot",
            "verizon",
            "at&t",
            "t-mobile"
        ]
        
        interface_lower = interface_name.lower()
        
        # Check common patterns
        for indicator in hotspot_indicators:
            if indicator in interface_lower:
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
                lines = result.stdout.lower()
                if "hotspot" in lines or "tether" in lines or "mobile" in lines:
                    # Verify it matches this interface
                    if interface_lower in lines.lower():
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

