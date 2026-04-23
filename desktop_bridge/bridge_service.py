from __future__ import annotations

import uvicorn


def main() -> None:
    # Standalone executable entrypoint used for background startup.
    # Suppress access logs for cleaner background operation
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8787,
        log_level="warning",
        access_log=False
    )


if __name__ == "__main__":
    main()
