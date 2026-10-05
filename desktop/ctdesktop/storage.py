import json
import os
import tempfile
from pathlib import Path
from cryptography.fernet import Fernet

def data_dir():
    if os.name == "nt":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home()))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    root = base / "cttrains-desktop"
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    return root

def atomic(path, data, mode=0o600):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, tmp = tempfile.mkstemp(dir=path.parent)
    try:
        os.fchmod(fd, mode) if hasattr(os, "fchmod") else None
        with os.fdopen(fd, "wb") as stream:
            stream.write(data if isinstance(data, bytes) else data.encode())
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)

class Vault:
    """Encrypted OS-user-private state; this is not protection against an OS account compromise."""
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.root.chmod(0o700)
        key = self.root / "device.key"
        if not key.exists():
            try:
                fd = os.open(key, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
                with os.fdopen(fd, "wb") as stream:
                    stream.write(Fernet.generate_key())
            except FileExistsError:
                pass
        self.cipher = Fernet(key.read_bytes())

    def read(self, name, default=None):
        path = self.root / (name + ".sealed")
        return json.loads(self.cipher.decrypt(path.read_bytes())) if path.exists() else default

    def write(self, name, value):
        atomic(self.root / (name + ".sealed"), self.cipher.encrypt(json.dumps(value).encode()))

    def delete(self, name):
        (self.root / (name + ".sealed")).unlink(missing_ok=True)
