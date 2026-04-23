"""
Background runner for the PowerPoint Bridge service.
This script runs the bridge service without showing a console window.
"""
from __future__ import annotations

import sys
import os

# Hide console window on Windows
if sys.platform == "win32":
    import ctypes
    ctypes.windll.user32.ShowWindow(ctypes.windll.kernel32.GetConsoleWindow(), 0)

# Change to the script directory
os.chdir(os.path.dirname(os.path.abspath(__file__)))

# Import and run the service
from bridge_service import main

if __name__ == "__main__":
    main()
