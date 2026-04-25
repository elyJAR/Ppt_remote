"""Integration tests for the FastAPI bridge endpoints.

All COM automation is mocked via the ``mock_controller`` and ``client``
fixtures defined in conftest.py — no PowerPoint installation is exercised.

The ``_patch_network_detector`` and ``_patch_discovery_responder`` autouse
fixtures (also in conftest.py) prevent real netsh/socket calls and UDP socket
binding for every test in this module.
"""

from __future__ import annotations

import urllib.parse
from unittest.mock import MagicMock, call, patch

import pytest
from powerpoint_controller import PowerPointControllerError

# ---------------------------------------------------------------------------
# Helpers / shared constants
# ---------------------------------------------------------------------------


def _encode(path: str) -> str:
    """Percent-encode a presentation path so it is safe to embed in a URL."""
    return urllib.parse.quote(path, safe="")


PPTX = r"C:\Slides\demo.pptx"
PPTX_ENC = _encode(PPTX)

PPTX2 = r"C:\Slides\other.pptx"
PPTX2_ENC = _encode(PPTX2)


# ===========================================================================
# GET /api/health
# ===========================================================================


class TestHealth:
    """The health endpoint must always return 200 and the expected schema."""

    def test_returns_200(self, client):
        r = client.get("/api/health")
        assert r.status_code == 200

    def test_response_schema(self, client):
        data = client.get("/api/health").json()
        assert data["status"] == "ok"
        assert "network_type" in data
        assert "is_hotspot" in data

    def test_network_type_is_wifi_from_mock(self, client):
        """Autouse fixture returns NetworkType.WIFI → value must be 'wifi'."""
        data = client.get("/api/health").json()
        assert data["network_type"] == "wifi"

    def test_is_hotspot_false_for_wifi(self, client):
        data = client.get("/api/health").json()
        assert data["is_hotspot"] is False

    def test_no_api_key_required(self, client):
        """Health must be accessible without the X-Api-Key header."""
        r = client.get("/api/health", headers={})
        assert r.status_code == 200

    def test_is_hotspot_true_when_providing(self, client):
        """When the network type is HOTSPOT_PROVIDING, is_hotspot must be True."""
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.HOTSPOT_PROVIDING):
            data = client.get("/api/health").json()
        assert data["is_hotspot"] is True
        assert data["network_type"] == "hotspot_providing"

    def test_is_hotspot_true_when_using(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.HOTSPOT_USING):
            data = client.get("/api/health").json()
        assert data["is_hotspot"] is True
        assert data["network_type"] == "hotspot_using"

    def test_is_hotspot_false_for_ethernet(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.ETHERNET):
            data = client.get("/api/health").json()
        assert data["is_hotspot"] is False
        assert data["network_type"] == "ethernet"


# ===========================================================================
# GET /api/network/status
# ===========================================================================


class TestNetworkStatus:
    """Network-status endpoint must expose type, hotspot flag, and an optional warning."""

    def test_returns_200(self, client):
        r = client.get("/api/network/status")
        assert r.status_code == 200

    def test_response_has_required_fields(self, client):
        data = client.get("/api/network/status").json()
        assert "network_type" in data
        assert "is_hotspot" in data
        assert "warning" in data

    def test_wifi_has_no_warning(self, client):
        """WIFI is a normal connection — warning must be null."""
        data = client.get("/api/network/status").json()
        assert data["network_type"] == "wifi"
        assert data["is_hotspot"] is False
        assert data["warning"] is None

    def test_ethernet_has_no_warning(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.ETHERNET):
            data = client.get("/api/network/status").json()
        assert data["network_type"] == "ethernet"
        assert data["is_hotspot"] is False
        assert data["warning"] is None

    def test_hotspot_providing_sets_warning(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.HOTSPOT_PROVIDING):
            data = client.get("/api/network/status").json()
        assert data["is_hotspot"] is True
        assert data["warning"] is not None
        assert isinstance(data["warning"], str)
        assert len(data["warning"]) > 0

    def test_hotspot_using_sets_warning(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.HOTSPOT_USING):
            data = client.get("/api/network/status").json()
        assert data["is_hotspot"] is True
        assert data["warning"] is not None

    def test_unknown_network_no_warning(self, client):
        from network_detector import NetworkType

        with patch("main.get_network_type", return_value=NetworkType.UNKNOWN):
            data = client.get("/api/network/status").json()
        assert data["network_type"] == "unknown"
        assert data["is_hotspot"] is False
        assert data["warning"] is None


# ===========================================================================
# GET /api/presentations
# ===========================================================================


class TestListPresentations:
    """List-presentations endpoint must serialise PresentationInfo objects."""

    def test_empty_list(self, client, mock_controller):
        mock_controller.list_presentations.return_value = []
        r = client.get("/api/presentations")
        assert r.status_code == 200
        assert r.json() == []

    def test_single_presentation_schema(
        self, client, mock_controller, sample_presentation
    ):
        mock_controller.list_presentations.return_value = [sample_presentation]
        data = client.get("/api/presentations").json()
        assert len(data) == 1
        item = data[0]
        assert item["id"] == PPTX
        assert item["name"] == "demo.pptx"
        assert item["path"] == PPTX
        assert item["in_slideshow"] is False
        assert item["current_slide"] is None
        assert item["total_slides"] == 10

    def test_slideshow_presentation_fields(
        self, client, mock_controller, sample_slideshow_presentation
    ):
        mock_controller.list_presentations.return_value = [
            sample_slideshow_presentation
        ]
        data = client.get("/api/presentations").json()
        assert len(data) == 1
        item = data[0]
        assert item["in_slideshow"] is True
        assert item["current_slide"] == 3
        assert item["total_slides"] == 10

    def test_multiple_presentations(
        self,
        client,
        mock_controller,
        sample_presentation,
        sample_slideshow_presentation,
    ):
        mock_controller.list_presentations.return_value = [
            sample_presentation,
            sample_slideshow_presentation,
        ]
        data = client.get("/api/presentations").json()
        assert len(data) == 2
        assert data[0]["in_slideshow"] is False
        assert data[1]["in_slideshow"] is True

    def test_controller_error_returns_400(self, client, mock_controller):
        mock_controller.list_presentations.side_effect = PowerPointControllerError(
            "PowerPoint is not running."
        )
        r = client.get("/api/presentations")
        assert r.status_code == 400
        assert "PowerPoint is not running." in r.json()["detail"]

    def test_controller_called_once(self, client, mock_controller):
        mock_controller.list_presentations.return_value = []
        client.get("/api/presentations")
        mock_controller.list_presentations.assert_called_once()


# ===========================================================================
# POST /api/presentations/{id}/start
# ===========================================================================


class TestStartSlideshow:
    def test_success_returns_ok(self, client, mock_controller):
        r = client.post(f"/api/presentations/{PPTX_ENC}/start")
        assert r.status_code == 200
        assert r.json() == {"ok": True}

    def test_correct_id_forwarded_to_controller(self, client, mock_controller):
        client.post(f"/api/presentations/{PPTX_ENC}/start")
        mock_controller.start_slideshow.assert_called_once_with(PPTX)

    def test_controller_error_returns_400(self, client, mock_controller):
        mock_controller.start_slideshow.side_effect = PowerPointControllerError(
            "Presentation not found."
        )
        r = client.post(f"/api/presentations/{PPTX_ENC}/start")
        assert r.status_code == 400
        assert "Presentation not found." in r.json()["detail"]

    def test_id_too_long_returns_400(self, client, mock_controller):
        """Presentation IDs longer than 512 chars must be rejected."""
        long_id = _encode("A" * 600)
        r = client.post(f"/api/presentations/{long_id}/start")
        assert r.status_code == 400
        # Controller must not have been called
        mock_controller.start_slideshow.assert_not_called()

    def test_second_presentation_forwarded_correctly(self, client, mock_controller):
        client.post(f"/api/presentations/{PPTX2_ENC}/start")
        mock_controller.start_slideshow.assert_called_once_with(PPTX2)


# ===========================================================================
# POST /api/presentations/{id}/stop
# ===========================================================================


class TestStopSlideshow:
    def test_success_returns_ok(self, client, mock_controller):
        r = client.post(f"/api/presentations/{PPTX_ENC}/stop")
        assert r.status_code == 200
        assert r.json() == {"ok": True}

    def test_correct_id_forwarded_to_controller(self, client, mock_controller):
        client.post(f"/api/presentations/{PPTX_ENC}/stop")
        mock_controller.stop_slideshow.assert_called_once_with(PPTX)

    def test_not_in_slideshow_returns_400(self, client, mock_controller):
        mock_controller.stop_slideshow.side_effect = PowerPointControllerError(
            "Presentation is not currently in slideshow mode."
        )
        r = client.post(f"/api/presentations/{PPTX_ENC}/stop")
        assert r.status_code == 400
        assert "not currently in slideshow" in r.json()["detail"]

    def test_id_too_long_returns_400(self, client, mock_controller):
        long_id = _encode("B" * 600)
        r = client.post(f"/api/presentations/{long_id}/stop")
        assert r.status_code == 400
        mock_controller.stop_slideshow.assert_not_called()


# ===========================================================================
# POST /api/presentations/{id}/next
# ===========================================================================


class TestNextSlide:
    def test_success_returns_ok(self, client, mock_controller):
        r = client.post(f"/api/presentations/{PPTX_ENC}/next")
        assert r.status_code == 200
        assert r.json() == {"ok": True}

    def test_correct_id_forwarded(self, client, mock_controller):
        client.post(f"/api/presentations/{PPTX_ENC}/next")
        mock_controller.next_slide.assert_called_once_with(PPTX)

    def test_controller_error_returns_400(self, client, mock_controller):
        mock_controller.next_slide.side_effect = PowerPointControllerError(
            "Could not enter slideshow mode."
        )
        r = client.post(f"/api/presentations/{PPTX_ENC}/next")
        assert r.status_code == 400
        assert "Could not enter slideshow mode." in r.json()["detail"]

    def test_id_too_long_returns_400(self, client, mock_controller):
        long_id = _encode("C" * 600)
        r = client.post(f"/api/presentations/{long_id}/next")
        assert r.status_code == 400
        mock_controller.next_slide.assert_not_called()


# ===========================================================================
# POST /api/presentations/{id}/previous
# ===========================================================================


class TestPreviousSlide:
    def test_success_returns_ok(self, client, mock_controller):
        r = client.post(f"/api/presentations/{PPTX_ENC}/previous")
        assert r.status_code == 200
        assert r.json() == {"ok": True}

    def test_correct_id_forwarded(self, client, mock_controller):
        client.post(f"/api/presentations/{PPTX_ENC}/previous")
        mock_controller.previous_slide.assert_called_once_with(PPTX)

    def test_controller_error_returns_400(self, client, mock_controller):
        mock_controller.previous_slide.side_effect = PowerPointControllerError(
            "Could not enter slideshow mode."
        )
        r = client.post(f"/api/presentations/{PPTX_ENC}/previous")
        assert r.status_code == 400
        assert "Could not enter slideshow mode." in r.json()["detail"]

    def test_id_too_long_returns_400(self, client, mock_controller):
        long_id = _encode("D" * 600)
        r = client.post(f"/api/presentations/{long_id}/previous")
        assert r.status_code == 400
        mock_controller.previous_slide.assert_not_called()


# ===========================================================================
# GET /api/presentations/{id}/notes
# ===========================================================================


class TestAllSpeakerNotes:
    """All-notes endpoint must return one SlideNotesDto per slide, 1-based."""

    def test_success_returns_list(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = [
            "Intro",
            "Main point",
            "",
        ]
        r = client.get(f"/api/presentations/{PPTX_ENC}/notes")
        assert r.status_code == 200
        data = r.json()
        assert isinstance(data, list)
        assert len(data) == 3

    def test_slide_index_is_one_based(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = ["First", "Second"]
        data = client.get(f"/api/presentations/{PPTX_ENC}/notes").json()
        assert data[0]["slide_index"] == 1
        assert data[1]["slide_index"] == 2

    def test_notes_text_preserved(self, client, mock_controller):
        notes_text = ["Intro notes", "  trimmed  ", ""]
        mock_controller.get_all_speaker_notes.return_value = notes_text
        data = client.get(f"/api/presentations/{PPTX_ENC}/notes").json()
        assert data[0]["notes"] == "Intro notes"
        assert data[1]["notes"] == "  trimmed  "
        assert data[2]["notes"] == ""

    def test_empty_presentation_returns_empty_list(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = []
        data = client.get(f"/api/presentations/{PPTX_ENC}/notes").json()
        assert data == []

    def test_schema_has_required_fields(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = ["Only slide"]
        item = client.get(f"/api/presentations/{PPTX_ENC}/notes").json()[0]
        assert "slide_index" in item
        assert "notes" in item

    def test_correct_id_forwarded(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = []
        client.get(f"/api/presentations/{PPTX_ENC}/notes")
        mock_controller.get_all_speaker_notes.assert_called_once_with(PPTX)

    def test_controller_error_returns_400(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.side_effect = PowerPointControllerError(
            "Presentation not found among open files."
        )
        r = client.get(f"/api/presentations/{PPTX_ENC}/notes")
        assert r.status_code == 400
        assert "Presentation not found" in r.json()["detail"]

    def test_single_slide_deck(self, client, mock_controller):
        mock_controller.get_all_speaker_notes.return_value = ["One and only"]
        data = client.get(f"/api/presentations/{PPTX_ENC}/notes").json()
        assert len(data) == 1
        assert data[0]["slide_index"] == 1
        assert data[0]["notes"] == "One and only"


# ===========================================================================
# GET /api/presentations/{id}/current-notes
# ===========================================================================


class TestCurrentSlideNotes:
    """Current-notes endpoint must return the active slide's index and notes."""

    def test_success_returns_slide_index_and_notes(self, client, mock_controller):
        mock_controller.get_current_slide_notes.return_value = (
            3,
            "Slide 3 speaker note",
        )
        r = client.get(f"/api/presentations/{PPTX_ENC}/current-notes")
        assert r.status_code == 200
        data = r.json()
        assert data["slide_index"] == 3
        assert data["notes"] == "Slide 3 speaker note"

    def test_schema_has_required_fields(self, client, mock_controller):
        mock_controller.get_current_slide_notes.return_value = (1, "")
        data = client.get(f"/api/presentations/{PPTX_ENC}/current-notes").json()
        assert "slide_index" in data
        assert "notes" in data

    def test_empty_notes_string(self, client, mock_controller):
        mock_controller.get_current_slide_notes.return_value = (5, "")
        data = client.get(f"/api/presentations/{PPTX_ENC}/current-notes").json()
        assert data["slide_index"] == 5
        assert data["notes"] == ""

    def test_first_slide(self, client, mock_controller):
        mock_controller.get_current_slide_notes.return_value = (1, "Opening remarks")
        data = client.get(f"/api/presentations/{PPTX_ENC}/current-notes").json()
        assert data["slide_index"] == 1

    def test_correct_id_forwarded(self, client, mock_controller):
        mock_controller.get_current_slide_notes.return_value = (2, "note")
        client.get(f"/api/presentations/{PPTX_ENC}/current-notes")
        mock_controller.get_current_slide_notes.assert_called_once_with(PPTX)

    def test_not_in_slideshow_returns_400(self, client, mock_controller):
        mock_controller.get_current_slide_notes.side_effect = PowerPointControllerError(
            "Presentation is not currently in slideshow mode."
        )
        r = client.get(f"/api/presentations/{PPTX_ENC}/current-notes")
        assert r.status_code == 400
        assert "not currently in slideshow" in r.json()["detail"]

    def test_presentation_not_found_returns_400(self, client, mock_controller):
        mock_controller.get_current_slide_notes.side_effect = PowerPointControllerError(
            "Presentation not found among open files."
        )
        r = client.get(f"/api/presentations/{PPTX_ENC}/current-notes")
        assert r.status_code == 400

    def test_id_too_long_returns_400(self, client, mock_controller):
        long_id = _encode("E" * 600)
        r = client.get(f"/api/presentations/{long_id}/current-notes")
        assert r.status_code == 400
        mock_controller.get_current_slide_notes.assert_not_called()


# ===========================================================================
# Input validation — _resolve_id
# ===========================================================================


class TestResolveId:
    """
    _resolve_id is used by every route that takes a presentation_id.
    Verify the 400 / pass-through behaviour via the start endpoint as a proxy.
    """

    def test_valid_windows_path_accepted(self, client, mock_controller):
        r = client.post(f"/api/presentations/{PPTX_ENC}/start")
        assert r.status_code == 200

    def test_path_exactly_512_chars_accepted(self, client, mock_controller):
        path_512 = "A" * 512
        r = client.post(f"/api/presentations/{_encode(path_512)}/start")
        # 512 chars == limit, should NOT raise the "too long" error
        assert r.status_code == 200

    def test_path_513_chars_rejected(self, client, mock_controller):
        path_513 = "A" * 513
        r = client.post(f"/api/presentations/{_encode(path_513)}/start")
        assert r.status_code == 400

    def test_url_encoded_path_decoded_before_forwarding(self, client, mock_controller):
        """The controller must receive the decoded path, not the percent-encoded one."""
        client.post(f"/api/presentations/{PPTX_ENC}/start")
        mock_controller.start_slideshow.assert_called_once_with(PPTX)
