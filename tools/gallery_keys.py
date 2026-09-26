"""Make, sign with, and publish with the gallery signing key.

    python tools/gallery_keys.py keygen  --out keys/
    python tools/gallery_keys.py sign    --manifest gallery.json --key keys/gallery_signing_key.pem
    python tools/gallery_keys.py publish --dir <folder with gallery.json, .sig and gallery.f32> \
                                         --server http://localhost:8080 --token <admin token>

**The private key never goes to the server and never goes into git.** The server holds only the
public half: it can check that a release came from whoever holds the private key, and refuse
anything else, but it cannot produce one. If the server is ever breached, the worst that can be
served is an old catalogue — not a forged one.

ECDSA P-256, not Ed25519, for one practical reason: `Signature.getInstance("Ed25519")` needs
Android 13, and the app supports Android 8 upwards. P-256 has been in the platform since Android 6.

The signature covers `gallery.json` byte for byte. That manifest states the SHA-256 of
`gallery.f32`, so one signature protects both files — as long as nothing re-serialises the JSON on
the way, which is why every step here and in the server passes the bytes around untouched.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import sys
from pathlib import Path

try:
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
except ImportError:
    sys.exit("This needs the 'cryptography' package: pip install cryptography")


def keygen(args: argparse.Namespace) -> int:
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    private_path = out / "gallery_signing_key.pem"
    if private_path.exists() and not args.force:
        return fail(f"{private_path} already exists. Overwriting it would orphan every signed "
                    "release and every installed app. Use --force only if you mean that.")

    key = ec.generate_private_key(ec.SECP256R1())
    private_path.write_bytes(key.private_bytes(
        encoding=serialization.Encoding.PEM,
        format=serialization.PrivateFormat.PKCS8,
        encryption_algorithm=serialization.NoEncryption(),
    ))
    public_der = key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    public_b64 = base64.b64encode(public_der).decode()
    (out / "gallery_public_key.b64").write_text(public_b64 + "\n", encoding="utf-8")

    print(f"private key  {private_path}   <- back this up offline; it is not recoverable")
    print(f"public key   {out / 'gallery_public_key.b64'}")
    print()
    print("Give the public key to both the server and the app:")
    print(f"  server:  GALLERY_PUBLIC_KEY={public_b64}")
    print("  app:     Signing.PUBLIC_KEY_BASE64 in gallery/Signing.kt")
    return 0


def sign(args: argparse.Namespace) -> int:
    manifest_path = Path(args.manifest)
    manifest = manifest_path.read_bytes()
    key = serialization.load_pem_private_key(Path(args.key).read_bytes(), password=None)
    if not isinstance(key, ec.EllipticCurvePrivateKey):
        return fail(f"{args.key} is not an EC private key")

    # Check what is about to be signed rather than signing whatever is on disk.
    try:
        doc = json.loads(manifest)
    except json.JSONDecodeError as e:
        return fail(f"{manifest_path} is not valid JSON: {e}")
    vectors_path = manifest_path.with_name(doc.get("vectors_file", "gallery.f32"))
    if not vectors_path.exists():
        return fail(f"the manifest points at {vectors_path.name}, which is not next to it")
    digest = hashlib.sha256(vectors_path.read_bytes()).hexdigest()
    if doc.get("vectors_sha256") != digest:
        return fail(f"{vectors_path.name} does not match the checksum in the manifest — re-export "
                    "before signing, or the app will reject the release")

    signature = key.sign(manifest, ec.ECDSA(hashes.SHA256()))
    out = Path(args.out) if args.out else manifest_path.with_suffix(manifest_path.suffix + ".sig")
    out.write_bytes(signature)
    print(f"signed v{doc.get('version')} ({doc.get('count')} sections, model {doc.get('model_id')})")
    print(f"  {out}  ({len(signature)} bytes)")
    return 0


def publish(args: argparse.Namespace) -> int:
    import urllib.error
    import urllib.request

    folder = Path(args.dir)
    manifest = folder / "gallery.json"
    signature = folder / "gallery.json.sig"
    vectors = folder / "gallery.f32"
    for f in (manifest, signature, vectors):
        if not f.exists():
            return fail(f"{f} is missing")

    boundary = "----alumex-gallery-publish"
    parts: list[bytes] = []
    for field, path, content_type in (
        ("manifest", manifest, "application/json"),
        ("signature", signature, "application/octet-stream"),
        ("vectors", vectors, "application/octet-stream"),
    ):
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"{field}\"; "
            f"filename=\"{path.name}\"\r\nContent-Type: {content_type}\r\n\r\n".encode()
            + path.read_bytes() + b"\r\n"
        )
    if args.notes:
        parts.append(
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"notes\"\r\n\r\n"
            f"{args.notes}\r\n".encode()
        )
    parts.append(f"--{boundary}--\r\n".encode())

    request = urllib.request.Request(
        f"{args.server.rstrip('/')}/api/v1/admin/gallery",
        data=b"".join(parts),
        headers={
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "X-Admin-Token": args.token,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            print(json.dumps(json.loads(response.read()), indent=1))
    except urllib.error.HTTPError as e:
        return fail(f"the server refused it ({e.code}): {e.read().decode(errors='replace')}")
    except urllib.error.URLError as e:
        return fail(f"could not reach {args.server}: {e.reason}")
    return 0


def fail(message: str) -> int:
    print(message, file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    k = sub.add_parser("keygen", help="create the signing keypair (once, ever)")
    k.add_argument("--out", default="keys")
    k.add_argument("--force", action="store_true")
    k.set_defaults(func=keygen)

    s = sub.add_parser("sign", help="sign a gallery manifest")
    s.add_argument("--manifest", required=True)
    s.add_argument("--key", default="keys/gallery_signing_key.pem")
    s.add_argument("--out")
    s.set_defaults(func=sign)

    p = sub.add_parser("publish", help="upload a signed release to the server")
    p.add_argument("--dir", required=True)
    p.add_argument("--server", default="http://localhost:8080")
    p.add_argument("--token", required=True)
    p.add_argument("--notes")
    p.set_defaults(func=publish)

    args = parser.parse_args()
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
