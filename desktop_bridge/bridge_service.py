from __future__ import annotations

import uvicorn


def main() -> None:
    # Standalone executable entrypoint used for background startup.
    uvicorn.run("main:app", host="0.0.0.0", port=8787, log_level="info")


if __name__ == "__main__":
    main()
