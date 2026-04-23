from __future__ import annotations

import sys
import logging


def main() -> None:
    # Standalone executable entrypoint used for background startup.
    # When running as a windowless executable, stdout/stderr might be None
    if sys.stdout is None:
        sys.stdout = open("nul", "w")
    if sys.stderr is None:
        sys.stderr = open("nul", "w")
    
    # Configure basic logging
    logging.basicConfig(
        level=logging.WARNING,
        format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    
    # Import the FastAPI app directly instead of using string import
    # This is required for PyInstaller to work correctly
    from main import app
    import uvicorn
    
    # Run uvicorn with the app object directly
    uvicorn.run(
        app,  # Pass the app object directly, not as a string
        host="0.0.0.0",
        port=8787,
        log_level="error",
        access_log=False,
        use_colors=False
    )


if __name__ == "__main__":
    main()
