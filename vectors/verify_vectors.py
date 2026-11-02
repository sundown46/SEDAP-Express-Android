#!/usr/bin/env python3
"""Rechnet golden-vectors.json unabhängig von jeder Implementierung nach.

Fängt Tippfehler in den Vektoren ab, bevor sie als Soll in Tests landen.
Nur Standardbibliothek. Aufruf: python3 vectors/verify_vectors.py
"""
import base64
import binascii
import hashlib
import hmac
import json
import math
import re
import sys
import zlib
from decimal import ROUND_HALF_EVEN, Decimal, localcontext
from pathlib import Path

ROOT = Path(__file__).resolve().parent
V = json.loads((ROOT / "golden-vectors.json").read_text(encoding="utf-8"))

failures = []
checks = 0


def check(label, actual, expected):
    global checks
    checks += 1
    if actual != expected:
        failures.append(f"{label}: erwartet {expected!r}, bekommen {actual!r}")


def latin1(text):
    try:
        return text.encode("latin-1")
    except UnicodeEncodeError:
        return text.encode("utf-8")


def compute_mac(line, password):
    canonical = line.rstrip("\n;")
    digest = hmac.new(latin1(password), latin1(canonical), hashlib.sha256).hexdigest().upper()
    return f"{zlib.adler32(digest.encode('ascii')) & 0xFFFFFFFF:08X}"


def verify_mac(line, password):
    fields = line.split(";")
    if len(fields) <= 6:
        return False
    received = fields[6]
    fields[6] = "0000"
    return received.upper() == compute_mac(";".join(fields), password)


def wire_number(value):
    with localcontext() as context:
        context.prec = 60  # Standard sind 28 Stellen, zu wenig für 1e16 mit 12 Nachkommastellen.
        d = Decimal(repr(float(value))).quantize(Decimal(1).scaleb(-12), rounding=ROUND_HALF_EVEN)
    text = format(d, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return "0" if text in ("", "-0") else text


def decode_text(value):
    cleaned = re.sub(r"[^A-Za-z0-9+/=]", "", value)
    if not cleaned:
        return None if value else ""
    try:
        data = base64.b64decode(cleaned, validate=True)
    except binascii.Error:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return data.decode("latin-1")


def inflate(value):
    try:
        data = base64.b64decode(value, validate=True)
    except binascii.Error:
        return None
    if not data:
        return None
    engine = zlib.decompressobj(-15)
    try:
        out = engine.decompress(data) + engine.flush()
    except zlib.error:
        return None
    return out.decode("latin-1") if engine.eof and out else None


def normalize_sidc(raw):
    sidc = raw.strip().upper().replace("*", "-")
    if not sidc or not re.fullmatch(r"[A-Z0-9-]+", sidc):
        return None
    return sidc.ljust(15, "-")


def cluster_key(sidc):
    upper = sidc.upper()
    identity = upper[1] if len(upper) > 1 else ""
    if identity not in "FHNUSA" or not identity:
        return "X"
    if identity not in "HS" and len(upper) >= 10:
        function = upper[4:10]
        if (upper[2] == "A" and function.startswith("C")
                or upper[2] == "G" and function.startswith("EVC")
                or upper[2] == "S" and function.startswith("X")):
            return "C"
    return {"F": "F", "A": "F", "H": "H", "S": "H", "N": "N", "U": "U"}[identity]


def resolve(ref, x, y, z):
    radius = 6_371_000.0
    lat = ref["lat"] + math.degrees(y / radius)
    lon = ref["lon"] + math.degrees(x / (radius * math.cos(math.radians(ref["lat"]))))
    alt = None if z is None else (ref["alt"] or 0) + z
    return lat, lon, alt


# Feldpositionen nach ICD §5/§6, gleiche Indizes wie die Referenz.
HEADER = {"number": 1, "time": 2, "sender": 3, "classification": 4, "acknowledgement": 5, "mac": 6}
BODY = {
    "OWNUNIT": dict(latitude=7, longitude=8, altitude=9, speed=10, course=11, heading=12, roll=13,
                    pitch=14, name=15, sidc=16),
    "CONTACT": dict(contactID=7, deleteFlag=8, latitude=9, longitude=10, altitude=11, speed=15,
                    course=16, name=23, source=24, sidc=25, mmsi=26, icao=27, comment=29),
    "POINT": dict(pointID=7, deleteFlag=8, latitude=9, longitude=10, altitude=11, speed=15, course=16,
                  name=23, sidc=24, comment=26),
    "TEXT": dict(recipient=7, kind=8, encoding=9, text=10, reference=11),
    "HEARTBEAT": dict(recipient=7),
    "ACKNOWLEDGE": dict(recipient=7, typeOfMessage=8, numberOfMessage=9),
    "TIMESYNC": dict(timestamp=7),
}
KINDS = {0: "undefined", 1: "alert", 2: "warning", 3: "notice", 4: "chat"}
OPTIONAL_NUMBERS = {"altitude", "speed", "course", "heading", "roll", "pitch"}


def text_kind(wire):
    try:
        return KINDS.get(int(wire), "undefined")
    except ValueError:
        return "undefined"


def to_float(text):
    try:
        value = float(text)
    except ValueError:
        return None
    return value if math.isfinite(value) else None


def parse(kind, line, reference=None):
    """Referenz-Parser nach den Android-Regeln in PROTOCOL.md. None = abgelehnt."""
    parts = line.split(";")
    get = lambda i: parts[i] if i < len(parts) else ""
    table = BODY[kind]
    if kind == "OWNUNIT":
        lat, lon = to_float(get(7)), to_float(get(8))
        if lat is None or lon is None:
            return None
    if kind == "TEXT" and not get(10):
        return None
    if kind in ("CONTACT", "POINT"):
        if not get(7).strip():
            return None
        delete = get(8).upper() == "TRUE"
        lat, lon = to_float(get(9)), to_float(get(10))
        relative = to_float(get(12)) is not None and to_float(get(13)) is not None
        if not delete and (lat is None or lon is None) and not (relative and reference):
            return None
        if lat is not None and not -90 <= lat <= 90 or lon is not None and not -180 <= lon <= 180:
            return None
    result = {name: get(index) for name, index in HEADER.items()}
    for name, index in table.items():
        raw = get(index)
        if name in OPTIONAL_NUMBERS or name in ("latitude", "longitude"):
            result[name] = to_float(raw) if raw else None
        elif name == "deleteFlag":
            result[name] = raw.upper() == "TRUE"
        elif name == "kind":
            result[name] = text_kind(raw)
        elif name == "timestamp":
            result[name] = int(raw, 16) if raw else None
        elif name == "recipient" and kind == "HEARTBEAT":
            result[name] = raw or None
        elif name == "comment":
            result["comment_decoded"] = decode_text(raw) if raw else ""
        elif name == "text":
            encoded = get(9).upper() == "BASE64"
            result["text_decoded"] = decode_text(raw) if encoded else raw
        else:
            result[name] = raw
    return result


FIELD_COUNT = {"OWNUNIT": 17, "HEARTBEAT": 8, "TEXT": 12, "ACKNOWLEDGE": 10}
KIND_WIRE = {name: str(number) for number, name in KINDS.items()}


def serialize(kind, fields):
    parts = [""] * FIELD_COUNT[kind]
    parts[0] = kind
    for name, value in fields.items():
        index = HEADER.get(name, BODY[kind].get(name))
        if name == "kind":
            value = KIND_WIRE[value]
        elif name == "sidc" and kind == "OWNUNIT":
            value = value.upper()
        elif isinstance(value, float):
            value = wire_number(value)
        parts[index] = value
    return ";".join(parts).rstrip(";") + "\n"


def main():
    for case in V["mac"]["compute"]:
        check(f"mac {case['line'][:30]}", compute_mac(case["line"], case["password"]), case["expected"])
    for case in V["mac"]["verify"]:
        line = case["line"].split(":", 1)[1] if ":HEARTBEAT" in case["line"] else case["line"]
        check(f"mac-verify {case['line'][:40]}", verify_mac(line, case["password"]), case["valid"])
    for case in V["number_format"]["cases"]:
        check(f"number {case['value']}", wire_number(case["value"]), case["expected"])
    for case in V["timestamp"]["cases"]:
        check(f"timestamp {case['ms']}", f"{case['ms']:012X}", case["expected"])
    for case in V["text_kind"]["read"]:
        check(f"kind {case['wire']!r}", text_kind(case["wire"]), case["kind"])
    for case in V["base64_text"]["decode"]:
        check(f"b64 {case['input']}", decode_text(case["input"]), case["expected"])
    for case in V["base64_text"]["encode"]:
        check(f"b64enc {case['text']}", base64.b64encode(latin1(case["text"])).decode(), case["expected"])
    for case in V["compression"]["decompress"]:
        check("inflate", inflate(case["input"]), case["expected"])
    for case in V["compression"]["invalid"]:
        check(f"inflate-invalid {case!r}", inflate(case), None)
    for case in V["sidc_normalize"]["cases"]:
        check(f"sidc {case['input']!r}", normalize_sidc(case["input"]), case["expected"])
    for case in V["cluster_key"]["cases"]:
        check(f"cluster {case['sidc']!r}", cluster_key(case["sidc"]), case["key"])
    tolerance = V["relative_position"]["tolerance_degrees"]
    for case in V["relative_position"]["cases"]:
        lat, lon, alt = resolve(case["reference"], case["x"], case["y"], case["z"])
        check("rel lat", abs(lat - case["expected"]["lat"]) < tolerance, True)
        check("rel lon", abs(lon - case["expected"]["lon"]) < tolerance, True)
        check("rel alt", alt, case["expected"]["alt"])
    for case in V["parse"]:
        parsed = parse(case["type"], case["line"], case.get("reference"))
        label = f"parse {case['line'][:45]}"
        if case.get("reject"):
            check(label, parsed, None)
            continue
        if parsed is None:
            failures.append(f"{label}: abgelehnt, sollte parsen")
            continue
        for name, expected in case["expect"].items():
            check(f"{label} .{name}", parsed.get(name), expected)
    for case in V["serialize"]:
        check(f"serialize {case['type']}", serialize(case["type"], case["fields"]), case["expected"])

    if failures:
        print(f"{len(failures)} von {checks} Prüfungen fehlgeschlagen:")
        for failure in failures:
            print("  -", failure)
        return 1
    print(f"OK: {checks} Prüfungen bestanden")
    return 0


if __name__ == "__main__":
    sys.exit(main())
