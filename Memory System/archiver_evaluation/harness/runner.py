"""Model runners: how the harness obtains a raw model response for one request.

Two backends behind one interface, so scoring code never knows or cares which
produced a response:

- ``RecordedRunner`` — replays raw responses saved under ``recorded/``. This is
  the default, needs no API key or network, and makes every run deterministic
  and free. It is used for the offline self-test and for *re-scoring* a live
  run without paying to call the model again.
- ``LiveRunner`` — calls an OpenAI-compatible ``/chat/completions`` endpoint,
  records the raw response plus the provider's *actual* reported token usage and
  measured latency. This is the only path that produces real cross-model / cost
  evidence, and it needs a provider API key that is deliberately NOT present in
  the build environment (see ``../README.md``).

Neither runner ever writes to the app's memory database — the harness's whole
job is to stay outside it.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, Optional
import json
import os
import time
import urllib.request
import urllib.error

from . import tokens


@dataclass
class ModelCall:
    """The result of one request, whatever the backend."""

    raw: str
    input_tokens: int
    output_tokens: int
    latency_ms: int
    # None on success; otherwise the harness failure class name — mirrors the
    # production ArchivistFailure categories so the report can count them.
    error: Optional[str] = None
    http_status: Optional[int] = None
    # Where the response came from, for report provenance.
    source: str = "recorded"


class ModelRunner:
    def call(self, system_prompt: str, user_message: str, *, model_id: str,
             temperature: float, structured: bool, call_key: str) -> ModelCall:
        raise NotImplementedError


class RecordedRunner(ModelRunner):
    """Replays saved raw responses. ``store`` maps a call key to a record with
    ``raw`` and optional ``usage``/``latency_ms``. A missing key is reported as
    an explicit 'not recorded' error rather than a silent empty response, so a
    gap in recordings can never masquerade as 'the model found nothing'."""

    def __init__(self, store: Dict[str, dict]):
        self.store = store

    @classmethod
    def from_dir(cls, path: str) -> "RecordedRunner":
        store: Dict[str, dict] = {}
        if os.path.isdir(path):
            for name in sorted(os.listdir(path)):
                if not name.endswith(".json"):
                    continue
                with open(os.path.join(path, name), "r", encoding="utf-8") as f:
                    data = json.load(f)
                # Each file maps call keys -> record.
                for k, v in data.items():
                    store[k] = v
        return cls(store)

    def call(self, system_prompt, user_message, *, model_id, temperature,
             structured, call_key) -> ModelCall:
        rec = self.store.get(call_key)
        if rec is None:
            return ModelCall(
                raw="",
                input_tokens=tokens.estimate_tokens(system_prompt + user_message),
                output_tokens=0,
                latency_ms=0,
                error="not_recorded",
                source="recorded",
            )
        raw = rec.get("raw", "")
        usage = rec.get("usage", {})
        in_tok = usage.get("prompt_tokens") or tokens.estimate_tokens(system_prompt + user_message)
        out_tok = usage.get("completion_tokens") or tokens.estimate_tokens(raw)
        return ModelCall(
            raw=raw,
            input_tokens=int(in_tok),
            output_tokens=int(out_tok),
            latency_ms=int(rec.get("latency_ms", 0)),
            error=rec.get("error"),
            http_status=rec.get("http_status"),
            source="recorded",
        )


class LiveRunner(ModelRunner):
    """Calls a real OpenAI-compatible endpoint. Records the raw content and the
    provider's own ``usage`` so cost math uses measured tokens, not estimates.

    ``record_into`` (optional) captures every call keyed by ``call_key`` so a
    live run can be replayed offline later for free re-scoring."""

    def __init__(self, base_url: str, api_key: str, *, timeout: int = 120,
                 record_into: Optional[Dict[str, dict]] = None):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout
        self.record_into = record_into

    def call(self, system_prompt, user_message, *, model_id, temperature,
             structured, call_key) -> ModelCall:
        body = {
            "model": model_id,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_message},
            ],
            "temperature": temperature,
        }
        if structured:
            # Provider-supported structured output; generic endpoints that do
            # not honor this simply ignore it and we fall back to plain-JSON
            # parsing of whatever prose comes back.
            body["response_format"] = {"type": "json_object"}

        data = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=data,
            headers={
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json",
            },
            method="POST",
        )
        started = time.time()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
                status = resp.status
        except urllib.error.HTTPError as e:
            latency = int((time.time() - started) * 1000)
            return ModelCall(raw="", input_tokens=0, output_tokens=0,
                             latency_ms=latency, error=_classify_http(e.code),
                             http_status=e.code, source="live")
        except Exception:
            latency = int((time.time() - started) * 1000)
            return ModelCall(raw="", input_tokens=0, output_tokens=0,
                             latency_ms=latency, error="unreachable", source="live")

        latency = int((time.time() - started) * 1000)
        choices = payload.get("choices") or []
        raw = ""
        if choices:
            raw = (choices[0].get("message") or {}).get("content") or ""
        usage = payload.get("usage") or {}
        call = ModelCall(
            raw=raw,
            input_tokens=int(usage.get("prompt_tokens") or 0),
            output_tokens=int(usage.get("completion_tokens") or 0),
            latency_ms=latency,
            error=None,
            http_status=status,
            source="live",
        )
        if self.record_into is not None:
            self.record_into[call_key] = {
                "raw": raw,
                "usage": {"prompt_tokens": call.input_tokens,
                          "completion_tokens": call.output_tokens},
                "latency_ms": latency,
                "http_status": status,
            }
        return call


def _classify_http(code: int) -> str:
    """Map an HTTP status to the app's visible failure classes (ArchivistFailure)."""
    if code in (401, 403, 404):
        return "rejected"       # B — access rejected (endpoint/key/model)
    if code == 429:
        return "limit"          # C — usage limit reached
    if 500 <= code < 600:
        return "unreachable"    # A — service could not be reached
    return "unknown"            # G
