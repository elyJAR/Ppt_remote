"""Shared pytest fixtures for the desktop bridge tests."""

from __future__ import annotations

import sys
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Ensure `desktop_bridge/` is on sys.path so bare imports work.
# main.py uses `from powerpoint_controller import ...` without a package prefix.
# ---------------------------------------------------------------------------
BRIDGE_DIR = Path(__file__).parent.parent
if str(BRIDGE_DIR) not in sys.path:
    sys.path.insert(0, str(BRIDGE_DIR))


# ---------------------------------------------------------------------------
# Autouse: prevent every test from hitting real netsh / socket calls that
# live inside the /api/health and /api/network/status route handlers.
# main.py binds the name via `from network_detector import get_network_type`,
# so we patch the reference that lives in the `main` module namespace.
# ---------------------------------------------------------------------------
@pytest.fixture(autouse=True)
def _patch_network_detector():
    """Prevent real netsh / socket calls during every test."""
    from network_detector import NetworkType

    with patch("main.get_network_type", return_value=NetworkType.WIFI):
        yield


# ---------------------------------------------------------------------------
# Prevent the lifespan from binding real UDP sockets (discovery responder).
# Without this, each TestClient context-manager enter/exit would spin up a
# daemon thread that tries to bind port 8788, which is noisy and fragile in
# CI environments.
# ---------------------------------------------------------------------------
@pytest.fixture(autouse=True)
def _patch_discovery_responder():
    """Stub out the UDP discovery responder so tests stay hermetic."""
    with (
        patch("main.discovery_responder.start"),
        patch("main.discovery_responder.stop"),
    ):
        yield


# ---------------------------------------------------------------------------
# Replace the real PowerPointController instance with a MagicMock.
# Route handlers access `controller` as a module-level global in main.py,
# so patching `main.controller` is sufficient.
# ---------------------------------------------------------------------------
@pytest.fixture()
def mock_controller():
    """Return a MagicMock that replaces main.controller for the test."""
    with patch("main.controller") as mock:
        yield mock


# ---------------------------------------------------------------------------
# TestClient — depends on mock_controller so routes never touch real COM.
# ---------------------------------------------------------------------------
@pytest.fixture()
def client(mock_controller):
    """
    Yield a Starlette TestClient wrapping the FastAPI app.

    The controller is already mocked via the `mock_controller` fixture.
    The lifespan (discovery responder) is suppressed by the autouse fixture
    above, so no UDP sockets are opened.
    """
    from fastapi.testclient import TestClient
    from main import app

    with TestClient(app, raise_server_exceptions=True) as c:
        yield c


# ---------------------------------------------------------------------------
# Sample domain objects
# ---------------------------------------------------------------------------
@pytest.fixture()
def sample_presentation():
    """Return a PresentationInfo that is NOT in slideshow mode."""
    from powerpoint_controller import PresentationInfo

    return PresentationInfo(
        id=r"C:\Slides\demo.pptx",
        name="demo.pptx",
        path=r"C:\Slides\demo.pptx",
        in_slideshow=False,
        current_slide=None,
        total_slides=10,
    )


@pytest.fixture()
def sample_slideshow_presentation():
    """Return a PresentationInfo that IS in slideshow mode (slide 3 of 10)."""
    from powerpoint_controller import PresentationInfo

    return PresentationInfo(
        id=r"C:\Slides\demo.pptx",
        name="demo.pptx",
        path=r"C:\Slides\demo.pptx",
        in_slideshow=True,
        current_slide=3,
        total_slides=10,
    )
