"""Unit tests for network_detector.py.

All subprocess and socket calls are mocked so no real network I/O occurs.
The autouse ``_patch_network_detector`` fixture in conftest.py only patches
``main.get_network_type``; the tests here call ``network_detector.get_network_type``
directly, so the real implementation is exercised with its helpers mocked.
"""

from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, call, patch

import pytest

# ---------------------------------------------------------------------------
# Ensure the bridge directory is on sys.path (mirrors conftest.py logic so
# this file can also be executed standalone via `pytest tests/test_network_detector.py`).
# ---------------------------------------------------------------------------
BRIDGE_DIR = Path(__file__).parent.parent
if str(BRIDGE_DIR) not in sys.path:
    sys.path.insert(0, str(BRIDGE_DIR))

from network_detector import (
    NetworkType,
    _get_active_interface,
    _get_interface_type,
    _is_providing_hotspot,
    _is_using_hotspot_connection,
    get_network_type,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_proc(stdout: str, returncode: int = 0) -> MagicMock:
    """Return a mock object that looks like a completed subprocess.CompletedProcess."""
    proc = MagicMock()
    proc.stdout = stdout
    proc.returncode = returncode
    return proc


# ===========================================================================
# get_network_type  — top-level branching logic
# ===========================================================================


class TestGetNetworkType:
    """Test every branch in the get_network_type() decision tree."""

    @patch("network_detector._is_providing_hotspot", return_value=True)
    def test_providing_hotspot_wins_first(self, _mock):
        assert get_network_type() == NetworkType.HOTSPOT_PROVIDING

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value=None)
    def test_no_active_interface_returns_unknown(self, _iface, _hotspot):
        assert get_network_type() == NetworkType.UNKNOWN

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value="Wi-Fi")
    @patch("network_detector._is_using_hotspot_connection", return_value=True)
    def test_using_hotspot_detected(self, _using, _iface, _providing):
        assert get_network_type() == NetworkType.HOTSPOT_USING

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value="Wi-Fi")
    @patch("network_detector._is_using_hotspot_connection", return_value=False)
    @patch("network_detector._get_interface_type", return_value="Wireless")
    def test_wireless_interface_returns_wifi(self, _itype, _using, _iface, _providing):
        assert get_network_type() == NetworkType.WIFI

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value="Ethernet")
    @patch("network_detector._is_using_hotspot_connection", return_value=False)
    @patch("network_detector._get_interface_type", return_value="Ethernet")
    def test_ethernet_interface_returns_ethernet(
        self, _itype, _using, _iface, _providing
    ):
        assert get_network_type() == NetworkType.ETHERNET

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value="Some Adapter")
    @patch("network_detector._is_using_hotspot_connection", return_value=False)
    @patch("network_detector._get_interface_type", return_value="Unknown")
    def test_unknown_interface_type_returns_unknown(
        self, _itype, _using, _iface, _providing
    ):
        assert get_network_type() == NetworkType.UNKNOWN

    @patch(
        "network_detector._is_providing_hotspot",
        side_effect=Exception("unexpected error"),
    )
    def test_top_level_exception_returns_unknown(self, _mock):
        """Any unhandled exception must be swallowed and return UNKNOWN."""
        assert get_network_type() == NetworkType.UNKNOWN

    @patch("network_detector._is_providing_hotspot", return_value=False)
    @patch("network_detector._get_active_interface", return_value="Wi-Fi")
    @patch(
        "network_detector._is_using_hotspot_connection", side_effect=Exception("boom")
    )
    def test_inner_exception_returns_unknown(self, _using, _iface, _providing):
        assert get_network_type() == NetworkType.UNKNOWN

    def test_returns_network_type_enum_instance(self):
        """Return value must always be a NetworkType enum member."""
        with (
            patch("network_detector._is_providing_hotspot", return_value=False),
            patch("network_detector._get_active_interface", return_value=None),
        ):
            result = get_network_type()
        assert isinstance(result, NetworkType)


# ===========================================================================
# NetworkType enum
# ===========================================================================


class TestNetworkTypeEnum:
    """Verify enum values are stable strings (used in API responses)."""

    def test_wifi_value(self):
        assert NetworkType.WIFI.value == "wifi"

    def test_hotspot_using_value(self):
        assert NetworkType.HOTSPOT_USING.value == "hotspot_using"

    def test_hotspot_providing_value(self):
        assert NetworkType.HOTSPOT_PROVIDING.value == "hotspot_providing"

    def test_ethernet_value(self):
        assert NetworkType.ETHERNET.value == "ethernet"

    def test_unknown_value(self):
        assert NetworkType.UNKNOWN.value == "unknown"

    def test_all_five_members_exist(self):
        names = {m.name for m in NetworkType}
        assert names == {
            "WIFI",
            "HOTSPOT_USING",
            "HOTSPOT_PROVIDING",
            "ETHERNET",
            "UNKNOWN",
        }


# ===========================================================================
# _is_providing_hotspot
# ===========================================================================


class TestIsProvidingHotspot:
    """
    _is_providing_hotspot makes up to two subprocess.run calls:
      1. netsh wlan show hostednetwork  — looks for "enabled" or "running"
      2. netsh int ipv4 show interfaces — looks for "mobile" or "hotspot"
    """

    @patch("subprocess.run")
    def test_status_running_returns_true(self, mock_run):
        mock_run.return_value = _make_proc("hosted network    Status : Running")
        assert _is_providing_hotspot() is True

    @patch("subprocess.run")
    def test_status_running_case_insensitive(self, mock_run):
        # Lower-cased comparison is used internally
        mock_run.return_value = _make_proc("HOSTED NETWORK    STATUS : RUNNING")
        assert _is_providing_hotspot() is True

    @patch("subprocess.run")
    def test_hosted_network_enabled_returns_true(self, mock_run):
        mock_run.return_value = _make_proc("Hosted network is enabled\nSome other text")
        assert _is_providing_hotspot() is True

    @patch("subprocess.run")
    def test_not_started_returns_false(self, mock_run):
        # Neither "enabled" nor "running" appear alongside the right keywords
        mock_run.return_value = _make_proc("Hosted network    Status : Not started")
        assert _is_providing_hotspot() is False

    @patch("subprocess.run")
    def test_nonzero_returncode_skips_first_block(self, mock_run):
        # returncode != 0 → the first if-block is skipped entirely; second
        # call also returns a neutral output → False
        mock_run.return_value = _make_proc("some output", returncode=1)
        assert _is_providing_hotspot() is False

    @patch("subprocess.run")
    def test_mobile_keyword_in_second_call_returns_true(self, mock_run):
        """Second netsh call with 'mobile' in stdout triggers True."""
        # First call: returncode=0 but no hotspot keywords in first check
        # Second call: contains "mobile"
        mock_run.side_effect = [
            _make_proc("hosted network    Status : Stopped"),  # first call
            _make_proc("  10  Connected  Dedicated  Mobile Broadband"),  # second call
        ]
        assert _is_providing_hotspot() is True

    @patch("subprocess.run")
    def test_hotspot_keyword_in_second_call_returns_true(self, mock_run):
        mock_run.side_effect = [
            _make_proc("hosted network    Status : Stopped"),
            _make_proc("Wi-Fi Hotspot Adapter   connected"),
        ]
        assert _is_providing_hotspot() is True

    @patch("subprocess.run", side_effect=Exception("subprocess blew up"))
    def test_subprocess_exception_returns_false(self, _mock):
        """Exception in subprocess must be caught and return False."""
        assert _is_providing_hotspot() is False


# ===========================================================================
# _get_active_interface
# ===========================================================================


class TestGetActiveInterface:
    """_get_active_interface uses a socket connect then netsh output."""

    @patch("socket.socket")
    @patch("subprocess.run")
    def test_returns_interface_name_when_found(self, mock_run, mock_socket_cls):
        # Socket yields the local IP 192.168.1.50
        sock_instance = MagicMock()
        sock_instance.getsockname.return_value = ("192.168.1.50", 0)
        mock_socket_cls.return_value = sock_instance

        # The function searches for "Interface" (capital I) in the lines that
        # precede the one containing the IP address.
        netsh_output = (
            "Interface Name: Wi-Fi\n"
            "   IP Address:                           192.168.1.50\n"
        )
        mock_run.return_value = _make_proc(netsh_output)

        result = _get_active_interface()
        assert result == "Wi-Fi"

    @patch("socket.socket")
    @patch("subprocess.run")
    def test_returns_none_when_ip_not_in_output(self, mock_run, mock_socket_cls):
        sock_instance = MagicMock()
        sock_instance.getsockname.return_value = ("10.0.0.99", 0)
        mock_socket_cls.return_value = sock_instance

        mock_run.return_value = _make_proc(
            'Interface  "Ethernet"\n   IP Address: 192.168.0.1\n'
        )

        result = _get_active_interface()
        assert result is None

    @patch("socket.socket")
    def test_socket_exception_returns_none(self, mock_socket_cls):
        sock_instance = MagicMock()
        sock_instance.connect.side_effect = OSError("network unreachable")
        mock_socket_cls.return_value = sock_instance

        result = _get_active_interface()
        assert result is None

    @patch("socket.socket")
    @patch("subprocess.run", side_effect=Exception("netsh failed"))
    def test_subprocess_exception_returns_none(self, _run, mock_socket_cls):
        sock_instance = MagicMock()
        sock_instance.getsockname.return_value = ("10.0.0.1", 0)
        mock_socket_cls.return_value = sock_instance

        result = _get_active_interface()
        assert result is None


# ===========================================================================
# _is_using_hotspot_connection
# ===========================================================================


class TestIsUsingHotspotConnection:
    """_is_using_hotspot_connection checks the interface name against known patterns."""

    @pytest.mark.parametrize(
        "iface_name",
        [
            "Mobile Broadband",
            "iPhone Hotspot",
            "Android Tether",
            "Moto Hotspot Adapter",
            "AT&T LTE",
            "T-Mobile Data",
            "Verizon Wireless",
            "WiFi Direct Connection",  # "wifi direct" indicator matches (no hyphen)
            "Virtual Network Adapter",
        ],
    )
    @patch("subprocess.run")
    def test_hotspot_keywords_in_name_return_true(self, mock_run, iface_name):
        # subprocess not needed when name matches — still stub it to be safe
        mock_run.return_value = _make_proc("")
        assert _is_using_hotspot_connection(iface_name) is True

    @patch("subprocess.run")
    def test_regular_wifi_name_returns_false(self, mock_run):
        mock_run.return_value = _make_proc("Wi-Fi   Connected   Dedicated   Wi-Fi")
        assert _is_using_hotspot_connection("Wi-Fi") is False

    @patch("subprocess.run")
    def test_ethernet_name_returns_false(self, mock_run):
        mock_run.return_value = _make_proc(
            "Ethernet   Connected   Dedicated   Ethernet"
        )
        assert _is_using_hotspot_connection("Ethernet") is False

    @patch("subprocess.run")
    def test_hotspot_in_subprocess_output_returns_true(self, mock_run):
        """If the subprocess output for the interface mentions hotspot, return True."""
        output = "wi-fi   connected   dedicated   wi-fi\nhotspot adapter found\n"
        mock_run.return_value = _make_proc(output)
        # "wi-fi" matches the interface name; "hotspot" is in lines
        assert _is_using_hotspot_connection("wi-fi") is True

    @patch("subprocess.run", side_effect=Exception("subprocess error"))
    def test_exception_returns_false(self, _mock):
        assert _is_using_hotspot_connection("SomeAdapter") is False


# ===========================================================================
# _get_interface_type
# ===========================================================================


class TestGetInterfaceType:
    """_get_interface_type inspects netsh interface show interface output."""

    @patch("subprocess.run")
    def test_wireless_keyword_returns_wireless(self, mock_run):
        mock_run.return_value = _make_proc(
            "Admin State  State   Type       Interface Name\n"
            "Enabled      Connected   Wireless   Wi-Fi\n"
        )
        result = _get_interface_type("Wi-Fi")
        assert result == "Wireless"

    @patch("subprocess.run")
    def test_wifi_keyword_returns_wireless(self, mock_run):
        mock_run.return_value = _make_proc(
            "Enabled      Connected   Wifi       MyWifiAdapter\n"
        )
        result = _get_interface_type("MyWifiAdapter")
        assert result == "Wireless"

    @patch("subprocess.run")
    def test_ethernet_keyword_returns_ethernet(self, mock_run):
        mock_run.return_value = _make_proc(
            "Admin State  State   Type       Interface Name\n"
            "Enabled      Connected   Ethernet   Ethernet\n"
        )
        result = _get_interface_type("Ethernet")
        assert result == "Ethernet"

    @patch("subprocess.run")
    def test_interface_not_in_output_returns_unknown(self, mock_run):
        mock_run.return_value = _make_proc(
            "Enabled      Connected   Ethernet   Ethernet\n"
        )
        result = _get_interface_type("Wi-Fi")
        assert result == "Unknown"

    @patch("subprocess.run")
    def test_nonzero_returncode_returns_unknown(self, mock_run):
        mock_run.return_value = _make_proc("some output", returncode=1)
        result = _get_interface_type("Wi-Fi")
        assert result == "Unknown"

    @patch("subprocess.run", side_effect=Exception("netsh unavailable"))
    def test_subprocess_exception_returns_unknown(self, _mock):
        result = _get_interface_type("Wi-Fi")
        assert result == "Unknown"
