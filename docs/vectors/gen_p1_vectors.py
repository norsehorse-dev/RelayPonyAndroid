#!/usr/bin/env python3
"""Reference implementation of the RelayPony protocol v3 constructions that are not the PAKE.

Writes p1_vectors.json next to this script. The Kotlin and Swift test suites assert these values,
so both platforms are proven byte-identical against one independent implementation.

Requires: pip install cryptography
"""
import base64, hashlib, hmac, json, os, re, urllib.parse
from cryptography.hazmat.primitives.asymmetric.x25519 import X25519PrivateKey, X25519PublicKey
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from cryptography.hazmat.primitives import hashes

h = bytes.fromhex
SCALAR_A = h("0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
PUB_B = h("0d799600f6ffaee2e121e6b8f7a05dc66874b51db3102d0d71f799a09cb4c461")
HANDLE_A = "age1q73he0q5yzfu3d64msd3p6rvksnrwjk3d2598mgtmlqt9wrdr37q2vrn72"
HANDLE_B = "age1p4uevq8kl7hw9cfpu6u00gzace58fdgakvgz6rt377v6p895c3ss5ae3ad"
NONCE_A = bytes(range(0x00, 0x10))
NONCE_B = bytes(range(0x10, 0x20))
INBOX_A = bytes(range(0x40, 0x60))
INBOX_B = bytes(range(0x60, 0x80))

def b64u(b): return base64.urlsafe_b64encode(b).rstrip(b"=").decode()
def form(s): return urllib.parse.quote_plus(s, safe="*-._")  # matches java.net.URLEncoder

def K(info):
    shared = X25519PrivateKey.from_private_bytes(SCALAR_A).exchange(X25519PublicKey.from_public_bytes(PUB_B))
    lo, hi = sorted([HANDLE_A, HANDLE_B])
    return HKDF(hashes.SHA256(), 32, lo.encode() + b"\0" + hi.encode(), info.encode()).derive(shared)

def tag(info, body): return hmac.new(K(info), body.encode(), hashlib.sha256).hexdigest()

def sas_v2(a, b, na, nb):
    lo, hi = sorted([a, b])
    d = hashlib.sha256(b"relaypony-sas-v2\0" + lo.encode() + b"\0" + hi.encode() + b"\0" + na + nb).digest()
    return d.hex(), "%08d" % (int.from_bytes(d[:8], "big") % 10**8)

def normalize_code(code):
    return re.sub(r"[\s\-_.]+", "-", code.strip().lower()).strip("-")

def mbox(nameplate, relay, side):
    return b64u(hashlib.sha256(b"rp-pake-v1\0" + str(nameplate).encode() + b"\0" + relay.encode() + b"\0" + side.encode()).digest())

out = {}
digest, code = sas_v2(HANDLE_A, HANDLE_B, NONCE_A, NONCE_B)
_, code_swapped = sas_v2(HANDLE_B, HANDLE_A, NONCE_A, NONCE_B)
assert code == code_swapped
out["sas_v2"] = {"handleA": HANDLE_A, "handleB": HANDLE_B, "nonceA": NONCE_A.hex(), "nonceB": NONCE_B.hex(),
                 "digest": digest, "code": code,
                 "code_nonces_swapped": sas_v2(HANDLE_A, HANDLE_B, NONCE_B, NONCE_A)[1]}

qr_default = "|".join(["RP2", "01", HANDLE_A, form("Test Phone 8"), b64u(NONCE_A), b64u(INBOX_A), ""])
qr_custom = "|".join(["RP2", "01", HANDLE_A, form("Test Phone 8"), b64u(NONCE_A), b64u(INBOX_A), form("https://relay.example.com")])
out["qr_v2"] = {"name": "Test Phone 8", "inboxId": b64u(INBOX_A), "pairNonce": b64u(NONCE_A),
                "default_relay": qr_default, "custom_relay": qr_custom, "relay": "https://relay.example.com"}

req = "|".join(["RPQ1", HANDLE_B, form("Test Tablet"), b64u(INBOX_B), "", b64u(NONCE_A), b64u(NONCE_B)])
out["pair_req_plain"] = req

ack_body = "|".join(["RPA1", HANDLE_A, HANDLE_B, b64u(NONCE_B), "ok"])
out["pair_ack"] = {"key": K("relaypony/pair/ack/v1").hex(), "body": ack_body, "tag": tag("relaypony/pair/ack/v1", ack_body)}

inbox_body = "|".join(["RPI1", HANDLE_A, HANDLE_B, b64u(INBOX_A), form("https://relay.example.com")])
out["inbox_announce"] = {"key": K("relaypony/inbox/v1").hex(), "body": inbox_body, "tag": tag("relaypony/inbox/v1", inbox_body)}

out["pake_mailbox"] = {
    "nameplate": 47,
    "default_A": mbox(47, "", "A"), "default_B": mbox(47, "", "B"),
    "custom_relay": "https://relay.example.com",
    "custom_A": mbox(47, "https://relay.example.com", "A"),
}
out["normalize_code"] = [
    ["47-lantern-orbit-maple", normalize_code("47-lantern-orbit-maple")],
    ["  47 Lantern  ORBIT_maple ", normalize_code("  47 Lantern  ORBIT_maple ")],
    ["47--lantern..orbit-maple-", normalize_code("47--lantern..orbit-maple-")],
]

path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "p1_vectors.json")
with open(path, "w") as f:
    json.dump(out, f, indent=2)
    f.write("\n")
print(json.dumps(out, indent=2))
