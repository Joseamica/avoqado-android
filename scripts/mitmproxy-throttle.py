"""
mitmproxy addon for network throttling simulation.
Controls latency and bandwidth from a config file that can be changed at runtime.

Usage:
    mitmdump -p 8888 --ssl-insecure -s scripts/mitmproxy-throttle.py

Config file: scripts/throttle-config.json
Change the config file while mitmproxy is running to adjust throttling in real-time.
"""

import json
import os
import time
import asyncio
from pathlib import Path
from mitmproxy import http, ctx

CONFIG_PATH = Path(__file__).parent / "throttle-config.json"

# Default config (no throttle)
DEFAULT_CONFIG = {
    "enabled": False,
    "latency_ms": 0,        # Extra latency per request (ms)
    "bandwidth_kbps": 0,    # Bandwidth limit (0 = unlimited)
    "profile": "none",      # Preset name for logging
    "log_requests": True,   # Log each request with timing
    "log_responses": True,  # Log each response with timing and size
    "block_hosts": [],      # Block requests to these hosts (simulate offline for specific services)
}

# Preset profiles
PROFILES = {
    "none": {"enabled": False, "latency_ms": 0, "bandwidth_kbps": 0},
    "3g": {"enabled": True, "latency_ms": 2000, "bandwidth_kbps": 384},
    "3g-slow": {"enabled": True, "latency_ms": 3500, "bandwidth_kbps": 200},
    "edge": {"enabled": True, "latency_ms": 5000, "bandwidth_kbps": 56},
    "2g": {"enabled": True, "latency_ms": 8000, "bandwidth_kbps": 30},
    "intermittent": {"enabled": True, "latency_ms": 4000, "bandwidth_kbps": 100},
    "slow-latency-only": {"enabled": True, "latency_ms": 5000, "bandwidth_kbps": 0},
}


def ensure_config():
    """Create default config if it doesn't exist."""
    if not CONFIG_PATH.exists():
        save_config(DEFAULT_CONFIG)


def save_config(config):
    """Save config to file."""
    with open(CONFIG_PATH, "w") as f:
        json.dump(config, f, indent=2)


def load_config():
    """Load config from file (re-reads every time for live updates)."""
    try:
        with open(CONFIG_PATH, "r") as f:
            config = json.load(f)
            # Apply profile if specified
            profile = config.get("profile", "none")
            if profile in PROFILES and profile != "none":
                base = PROFILES[profile].copy()
                # Allow overrides from config
                base["profile"] = profile
                base["log_requests"] = config.get("log_requests", True)
                base["log_responses"] = config.get("log_responses", True)
                base["block_hosts"] = config.get("block_hosts", [])
                # Only override latency/bandwidth if explicitly set in config
                if "latency_ms" in config and config["latency_ms"] > 0:
                    base["latency_ms"] = config["latency_ms"]
                if "bandwidth_kbps" in config and config["bandwidth_kbps"] > 0:
                    base["bandwidth_kbps"] = config["bandwidth_kbps"]
                return base
            return {**DEFAULT_CONFIG, **config}
    except (json.JSONDecodeError, FileNotFoundError):
        return DEFAULT_CONFIG


class ThrottleAddon:
    def __init__(self):
        ensure_config()
        self.request_times = {}
        ctx.log.info("[Throttle] Addon loaded. Config: " + str(CONFIG_PATH))

    def request(self, flow: http.HTTPFlow):
        config = load_config()
        url = flow.request.pretty_url
        host = flow.request.host
        method = flow.request.method

        # Block specific hosts
        if config.get("block_hosts"):
            for blocked in config["block_hosts"]:
                if blocked in host:
                    flow.response = http.Response.make(
                        503, b"Service Unavailable (throttle: blocked)",
                        {"Content-Type": "text/plain"}
                    )
                    ctx.log.warn(f"[BLOCKED] {method} {url}")
                    return

        # Track request start time
        self.request_times[flow.id] = time.time()

        # Log request
        if config.get("log_requests", True):
            path = flow.request.path
            # Shorten the URL for readability
            if len(path) > 80:
                path = path[:77] + "..."
            throttle_info = ""
            if config.get("enabled"):
                throttle_info = f" [throttle: {config.get('profile', 'custom')} +{config.get('latency_ms', 0)}ms]"
            ctx.log.info(f"[REQ] {method} {path}{throttle_info}")

    def response(self, flow: http.HTTPFlow):
        config = load_config()

        # Calculate actual latency
        start_time = self.request_times.pop(flow.id, None)
        actual_latency = int((time.time() - start_time) * 1000) if start_time else 0

        # Apply artificial latency
        if config.get("enabled") and config.get("latency_ms", 0) > 0:
            delay_sec = config["latency_ms"] / 1000.0
            time.sleep(delay_sec)

        # Apply bandwidth throttle (delay based on response size)
        if config.get("enabled") and config.get("bandwidth_kbps", 0) > 0:
            content_length = len(flow.response.content) if flow.response.content else 0
            if content_length > 0:
                bandwidth_bytes_per_sec = config["bandwidth_kbps"] * 128  # kbps to bytes/s
                transfer_time = content_length / bandwidth_bytes_per_sec
                time.sleep(transfer_time)

        # Log response
        if config.get("log_responses", True):
            status = flow.response.status_code
            path = flow.request.path
            if len(path) > 60:
                path = path[:57] + "..."
            content_length = len(flow.response.content) if flow.response.content else 0
            size_str = f"{content_length}B" if content_length < 1024 else f"{content_length/1024:.1f}KB"

            # Check for cache headers
            cache_status = ""
            cache_control = flow.response.headers.get("cache-control", "")
            etag = flow.response.headers.get("etag", "")
            if "no-cache" in cache_control or "no-store" in cache_control:
                cache_status = " [no-cache]"
            elif etag:
                cache_status = f" [etag:{etag[:12]}]"
            elif "max-age" in cache_control:
                cache_status = f" [cache:{cache_control}]"

            added_latency = config.get("latency_ms", 0) if config.get("enabled") else 0
            total = actual_latency + added_latency

            ctx.log.info(
                f"[RES] {status} {path} | "
                f"real={actual_latency}ms +throttle={added_latency}ms =total={total}ms | "
                f"size={size_str}{cache_status}"
            )


addons = [ThrottleAddon()]
