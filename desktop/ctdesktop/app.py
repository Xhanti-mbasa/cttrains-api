import json
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from datetime import datetime
from zoneinfo import ZoneInfo
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import sys
import webbrowser
from . import __version__
from .storage import data_dir, Vault, atomic
from .hosting import Hosting
from .matrix import MatrixClient, circle_document, open_update, request_json

BG, SURFACE, PANEL = "#0c1412", "#15201d", "#1f302a"
TEXT, MUTED, ACCENT, INK = "#edf5ef", "#aabeb3", "#85d7c2", "#082b22"

def guide():
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).parent.parent))
    return (base / "assets/HELP.txt").read_text()

class ScrollPage(tk.Frame):
    def __init__(self, parent):
        super().__init__(parent, bg=BG)
        self.canvas = tk.Canvas(self, bg=BG, highlightthickness=0)
        bar = ttk.Scrollbar(self, orient="vertical", command=self.canvas.yview)
        self.content = tk.Frame(self.canvas, bg=BG)
        window = self.canvas.create_window((0, 0), window=self.content, anchor="nw")
        self.content.bind("<Configure>", lambda _: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.bind("<Configure>", lambda e: self.canvas.itemconfigure(window, width=e.width))
        self.canvas.configure(yscrollcommand=bar.set)
        self.canvas.pack(side="left", fill="both", expand=True)
        bar.pack(side="right", fill="y")

class Desktop(tk.Tk):
    def __init__(self, root=None):
        super().__init__()
        self.title("CT Trains Desktop")
        self.geometry("1140x800")
        self.minsize(940, 640)
        self.configure(bg=BG)
        self.root_dir = Path(root) if root else data_dir()
        self.vault = Vault(self.root_dir)
        self.host = Hosting(self.root_dir)
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="cttrains")
        self.results = queue.Queue()
        self.busy = False
        self.closing = False
        self.session = self.vault.read("matrix-session")
        self.friends = self.vault.read("friends", [])
        self.issued = self.vault.read("relay-invitations", [])
        self.latest, self.sequences = {}, {}
        self.watching_until = 0
        self.watch_generation = 0
        self.watch_stop = threading.Event()
        self.poll_pending = False
        self.notice = tk.StringVar(value="Ready. Host services when you choose.")
        self.matrix_state = tk.StringVar(value="Not checked")
        self.relay_state = tk.StringVar(value="Offline")
        self.selected_friend = None
        self.build_style()
        self.build_shell()
        self.show("Overview")
        self.protocol("WM_DELETE_WINDOW", self.close)
        self.after(100, self.drain)
        self.after(200, self.check_status)

    def build_style(self):
        style = ttk.Style(self)
        style.theme_use("clam")
        style.configure("TScrollbar", background=PANEL, troughcolor=BG, arrowcolor=MUTED, borderwidth=0)
        style.configure("Treeview", background=SURFACE, foreground=TEXT, fieldbackground=SURFACE, rowheight=42, font=("DejaVu Sans", 11), borderwidth=0)
        style.configure("Treeview.Heading", background=PANEL, foreground=ACCENT, font=("DejaVu Sans", 10, "bold"), relief="flat")
        style.map("Treeview", background=[("selected", PANEL)], foreground=[("selected", ACCENT)])
        self.option_add("*Font", ("DejaVu Sans", 11))

    def label(self, parent, text, size=11, color=TEXT, bold=False, **kw):
        return tk.Label(parent, text=text, bg=parent.cget("bg"), fg=color, font=("DejaVu Sans", size, "bold" if bold else "normal"), anchor="w", justify="left", **kw)

    def button(self, parent, text, command, primary=False):
        return tk.Button(parent, text=text, command=command, bg=ACCENT if primary else PANEL, fg=INK if primary else TEXT,
            activebackground="#a5e6d4" if primary else "#31463c", activeforeground=INK if primary else TEXT,
            relief="flat", borderwidth=0, padx=14, pady=10, cursor="hand2", font=("DejaVu Sans", 10, "bold"))

    def input(self, parent, name, initial="", password=False):
        self.label(parent, name, 10, MUTED).pack(anchor="w", pady=(14, 5))
        entry = tk.Entry(parent, bg=BG, fg=TEXT, insertbackground=ACCENT, relief="flat", highlightthickness=1,
            highlightbackground="#3b5147", highlightcolor=ACCENT, font=("DejaVu Sans", 12), show="•" if password else "")
        entry.insert(0, initial)
        entry.pack(fill="x", ipady=9)
        return entry

    def card(self, parent, title, detail=""):
        card = tk.Frame(parent, bg=SURFACE, padx=22, pady=20)
        card.pack(fill="x", padx=30, pady=(0, 16))
        self.label(card, title, 16, bold=True).pack(anchor="w", pady=(0, 8))
        if detail:
            label = self.label(card, detail, 10, MUTED, wraplength=700)
            label.pack(fill="x")
            label.bind("<Configure>", lambda e: label.configure(wraplength=max(180, e.width)))
        return card

    def build_shell(self):
        sidebar = tk.Frame(self, bg=SURFACE, width=210, padx=18, pady=28)
        sidebar.pack(side="left", fill="y")
        sidebar.pack_propagate(False)
        self.label(sidebar, "CT TRAINS", 18, ACCENT, True).pack(anchor="w")
        self.label(sidebar, "DESKTOP COMPANION", 9, MUTED).pack(anchor="w", pady=(4, 35))
        self.nav = {}
        for name, desc in (("Overview", "Your hosting dashboard"), ("Friends", "Private trip updates"), ("Servers", "Host & manage"), ("Help", "Setup & phone connection")):
            button = self.button(sidebar, name, lambda n=name: self.show(n))
            button.pack(fill="x", pady=(0, 4))
            self.label(sidebar, desc, 8, MUTED).pack(anchor="w", padx=8, pady=(0, 20))
            self.nav[name] = button
        self.label(sidebar, "LOCAL FIRST\nYou choose what to share.", 9, MUTED).pack(side="bottom", anchor="w", pady=20)
        self.body = tk.Frame(self, bg=BG)
        self.body.pack(side="right", fill="both", expand=True)
        top = tk.Frame(self.body, bg=BG, padx=30, pady=22)
        top.pack(fill="x")
        self.page_title = self.label(top, "Overview", 26, bold=True)
        self.page_title.pack(side="left")
        self.label(top, "ALPHA  " + __version__, 9, ACCENT).pack(side="right")
        status = tk.Frame(self.body, bg=PANEL, padx=24, pady=12)
        status.pack(side="bottom", fill="x")
        label = tk.Label(status, textvariable=self.notice, bg=PANEL, fg=TEXT, font=("DejaVu Sans", 10), anchor="w", justify="left", wraplength=820)
        label.pack(fill="x")
        label.bind("<Configure>", lambda e: label.configure(wraplength=max(180, e.width)))
        self.page_host = tk.Frame(self.body, bg=BG)
        self.page_host.pack(fill="both", expand=True)

    def show(self, page):
        self.page = page
        self.page_title.configure(text=page)
        for name, button in self.nav.items():
            button.configure(bg=ACCENT if name == page else PANEL, fg=INK if name == page else TEXT)
        for widget in self.page_host.winfo_children():
            widget.destroy()
        scroll = ScrollPage(self.page_host)
        scroll.pack(fill="both", expand=True)
        getattr(self, "page_" + page.lower())(scroll.content)
        scroll.canvas.bind_all("<MouseWheel>", lambda e: scroll.canvas.yview_scroll(-int(e.delta / 120), "units"))
        scroll.canvas.bind_all("<Button-4>", lambda e: scroll.canvas.yview_scroll(-1, "units"))
        scroll.canvas.bind_all("<Button-5>", lambda e: scroll.canvas.yview_scroll(1, "units"))

    def page_overview(self, parent):
        card = self.card(parent, "Your trips. Your people. Your server.", "A lightweight control centre for private trip updates and services hosted on this computer.")
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x", pady=(20, 2))
        for name, value in (("MATRIX", self.matrix_state), ("LOCATION RELAY", self.relay_state)):
            col = tk.Frame(row, bg=SURFACE)
            col.pack(side="left", fill="x", expand=True)
            self.label(col, name, 9, MUTED).pack(anchor="w")
            tk.Label(col, textvariable=value, bg=SURFACE, fg=ACCENT, font=("DejaVu Sans", 16, "bold"), anchor="w").pack(anchor="w", pady=8)
        self.button(card, "Manage servers", lambda: self.show("Servers"), True).pack(anchor="w", pady=(12, 0))
        card = self.card(parent, "Friend trips", "%d private invitation(s) saved · watching is %s" % (len(self.friends), "on" if self.watching_until > time.time() else "off"))
        if not self.friends:
            self.label(card, "Connect Matrix, import a private trip invitation,\nthen start watching when you need it.", 12, MUTED).pack(anchor="w", pady=10)
        else:
            for friend in self.friends[:5]:
                update = self.current_update(friend["id"])
                self.label(card, friend["trip"]["owner"] + " · " + (update["phase"] if update else "Current position unknown"), 12).pack(anchor="w", pady=6)
        self.button(card, "Open friends", lambda: self.show("Friends"), True).pack(anchor="w", pady=(12, 0))
        card = self.card(parent, "Connect your phone", "Android connects through Matrix. The standalone REST relay is available for future clients.")
        self.button(card, "Setup instructions", lambda: self.show("Help")).pack(anchor="w", pady=(16, 0))

    def page_servers(self, parent):
        card = self.card(parent, "Matrix homeserver", "Synapse + PostgreSQL. Stops preserve data. First setup downloads the official server images.")
        try:
            server = self.host.server_name()
        except ValueError:
            server = ""
        name = self.input(card, "Permanent server hostname", server or "matrix.example.com")
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x", pady=(16, 0))
        for title, action, primary in (
            ("Set up", lambda: self.run(lambda n=name.get(): self.host.setup(n)), True),
            ("Start Matrix", lambda: self.run(self.host.start_matrix), False),
            ("Stop Matrix", lambda: self.run(self.host.stop_matrix), False)):
            self.button(row, title, action, primary).pack(side="left", padx=(0, 8))
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x", pady=(10, 0))
        self.button(row, "Enable HTTPS", self.enable_https).pack(side="left", padx=(0, 8))
        self.button(row, "Refresh status", self.check_status).pack(side="left", padx=(0, 8))
        self.button(row, "Logs", lambda: self.run(self.host.logs, lambda r: self.text_dialog("Server logs", r))).pack(side="left")
        self.label(card, "Phones need trusted HTTPS. Configure DNS and router access first.\nAll users share this server; federation and public registration are off.", 10, MUTED).pack(anchor="w", pady=(14, 0))
        card = self.card(parent, "Create an account", "Create a normal account for each friend and for the desktop. Matrix must be running.")
        username = self.input(card, "Username")
        password = self.input(card, "Password · at least 12 characters", password=True)
        def account():
            user, secret = username.get().strip(), password.get()
            password.delete(0, "end")
            self.run(lambda: self.host.account(user, secret))
        self.button(card, "Create Matrix account", account, True).pack(anchor="w", pady=(16, 0))
        card = self.card(parent, "Location relay", "Independent of Matrix. Positions stay in memory; each publisher needs consent and a separate invitation.")
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x", pady=(16, 0))
        self.button(row, "Start relay", lambda: self.run(self.host.start_relay), True).pack(side="left", padx=(0, 8))
        self.button(row, "Stop relay", lambda: self.run(self.host.stop_relay)).pack(side="left")
        person = self.input(card, "Person sharing their location")
        minutes = tk.IntVar(value=60)
        self.label(card, "Invitation lifetime · minutes", 10, MUTED).pack(anchor="w", pady=(14, 0))
        tk.Scale(card, from_=1, to=120, orient="horizontal", variable=minutes, bg=SURFACE, fg=TEXT, troughcolor=PANEL,
            activebackground=ACCENT, highlightthickness=0, length=400).pack(anchor="w")
        self.button(card, "Create relay invitation",
            lambda: self.run(lambda n=person.get(), d=minutes.get() * 60: self.host.relay_invitation(n, d), self.invitation_created), True).pack(anchor="w", pady=(8, 0))
        for invite in self.issued:
            if invite["expires_at"] <= time.time():
                continue
            row = tk.Frame(card, bg=SURFACE)
            row.pack(fill="x", pady=(12, 0))
            self.label(row, invite["name"], 11).pack(side="left")
            self.button(row, "Revoke", lambda v=invite: self.revoke(v)).pack(side="right")

    def enable_https(self):
        if messagebox.askokcancel("Enable phone access", "This starts HTTPS on ports 80/443. Your Matrix hostname must point here. Matrix client/media and the relay are exposed; administration stays local."):
            self.run(self.host.start_https)

    def invitation_created(self, value):
        self.issued = [v for v in self.issued if v["expires_at"] > time.time()] + [value]
        self.vault.write("relay-invitations", self.issued)
        try:
            base = "https://" + self.host.server_name() + "/location"
        except ValueError:
            base = "http://127.0.0.1:8787"
        for role in ("publisher", "reader"):
            path = filedialog.asksaveasfilename(title="Save PRIVATE %s invitation" % role,
                initialfile="CT-Trains-%s.ctlocation" % role, defaultextension=".ctlocation")
            if path:
                payload = {"format": "cttrains.location.invitation", "version": 1, "base_url": base, "id": value["id"],
                    "name": value["name"], "role": role, "token": value[role + "_token"], "expires_at": value["expires_at"]}
                atomic(path, json.dumps(payload, indent=2))
        self.notice.set("Give the publisher file only to its owner and the reader file to their invited friend.")
        self.show("Servers")

    def revoke(self, value):
        def done(_):
            self.issued = [v for v in self.issued if v["id"] != value["id"]]
            self.vault.write("relay-invitations", self.issued)
            self.notice.set("Invitation revoked; publishing and reading are blocked.")
            self.show("Servers")
        self.run(lambda: request_json("http://127.0.0.1:8787", "/v1/location", "DELETE", token=value["publisher_token"]), done)

    def page_friends(self, parent):
        card = self.card(parent, "Private friend trips", "Compatible with the phone's Matrix trip invitations. Watching stays off until you start it.")
        if self.session:
            self.label(card, self.session["user"], 13, ACCENT, True).pack(anchor="w", pady=10)
            self.label(card, self.session["base"], 10, MUTED).pack(anchor="w")
            self.button(card, "Sign out and revoke session", self.logout).pack(anchor="w", pady=(12, 0))
        else:
            base = self.input(card, "Matrix homeserver", "http://127.0.0.1:8008")
            user = self.input(card, "Username / full Matrix ID")
            password = self.input(card, "Password", password=True)
            def login():
                address, username, secret = base.get(), user.get(), password.get()
                password.delete(0, "end")
                def done(response):
                    self.session = response
                    self.vault.write("matrix-session", response)
                    self.notice.set("Connected. Create a phone invitation for " + response["user"] + ".")
                    self.show("Friends")
                self.run(lambda: MatrixClient(address).login(username, secret), done)
            self.button(card, "Connect Matrix", login, True).pack(anchor="w", pady=(16, 0))
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x", pady=(16, 0))
        self.button(row, "Import invitation", self.import_friend).pack(side="left", padx=(0, 8))
        self.button(row, "Start watching", self.start_watch, True).pack(side="left", padx=(0, 8))
        self.button(row, "Stop watching", self.stop_watch).pack(side="left")
        card = self.card(parent, "Latest updates", "No location history. After five minutes without a fresh fix, position is unknown.")
        tree = ttk.Treeview(card, columns=("friend", "phase", "at"), show="headings", height=5, selectmode="browse")
        for key, title, width in (("friend", "Friend", 160), ("phase", "Check-in / location", 260), ("at", "Last shared", 110)):
            tree.heading(key, text=title)
            tree.column(key, width=width, minwidth=70)
        tree.pack(fill="x", pady=(8, 12))
        self.friend_tree = tree
        tree.bind("<<TreeviewSelect>>", lambda _: self.select_friend(tree))
        self.update_friend_table()
        row = tk.Frame(card, bg=SURFACE)
        row.pack(fill="x")
        self.button(row, "Planned steps", self.view_trip).pack(side="left", padx=(0, 8))
        self.button(row, "Open position in map", self.open_map).pack(side="left", padx=(0, 8))
        self.button(row, "Remove invitation", self.remove_friend).pack(side="left")
        self.label(card, "Opening a map shares that coordinate with OpenStreetMap.\nPlanned times are not live arrivals.", 9, MUTED).pack(anchor="w", pady=(14, 0))

    def select_friend(self, tree):
        selection = tree.selection()
        self.selected_friend = selection[0] if selection else None

    def import_friend(self):
        path = filedialog.askopenfilename(title="Open private phone trip invitation",
            filetypes=[("CT Trains invitation", "*.ctcircle"), ("JSON", "*.json"), ("All files", "*")])
        if not path:
            return
        try:
            with open(path) as stream:
                link = circle_document(stream.read(256 * 1024 + 1))
            if len(self.friends) >= 30 and not any(f["id"] == link["id"] for f in self.friends):
                raise ValueError("Remove an old invitation first; at most 30 are supported.")
            self.friends = [f for f in self.friends if f["id"] != link["id"]] + [link]
            self.vault.write("friends", self.friends)
            self.latest.pop(link["id"], None)
            self.sequences.pop(link["id"], None)
            self.notice.set("Invitation imported. Start watching after the phone owner starts sharing.")
            self.show("Friends")
        except Exception as error:
            messagebox.showerror("Could not import", str(error))

    def logout(self):
        if not self.session:
            return
        session = self.session.copy()
        self.stop_watch()
        def done(_):
            self.vault.delete("matrix-session")
            self.session = None
            self.notice.set("Signed out; the homeserver session was revoked.")
            self.show("Friends")
        self.run(lambda: MatrixClient(session["base"], session["token"], session["user"]).logout(), done)

    def start_watch(self):
        if not self.session or not self.friends:
            self.notice.set("Connect Matrix and import a private invitation first.")
            return
        self.watch_stop.set()
        self.watch_stop = threading.Event()
        self.watch_generation += 1
        self.watching_until = time.time() + 7200
        self.notice.set("Watching private updates for up to two hours. Stop whenever you choose.")
        self.poll(self.watch_generation)

    def stop_watch(self):
        self.watch_stop.set()
        self.watching_until = 0
        self.watch_generation += 1
        self.latest.clear()
        self.notice.set("Watching stopped. In-memory positions cleared.")
        self.update_friend_table()

    def poll(self, generation):
        if self.closing or generation != self.watch_generation:
            return
        if self.watching_until <= time.time():
            self.watching_until = 0
            self.latest.clear()
            self.update_friend_table()
            return
        if not self.poll_pending and self.session:
            self.poll_pending = True
            session, friends, sequences, stop = self.session.copy(), list(self.friends), self.sequences.copy(), self.watch_stop
            def work():
                client = MatrixClient(session["base"], session["token"], session["user"])
                results, errors = {}, []
                for link in friends:
                    if stop.is_set():
                        break
                    if link["expiresAt"] <= time.time() * 1000:
                        continue
                    try:
                        client.join_private(link["room"])
                        valid = []
                        for event in client.messages(link["room"]):
                            try:
                                valid.append(open_update(link, event, sequences.get(link["id"], -1)))
                            except Exception:
                                continue
                        if valid:
                            results[link["id"]] = max(valid, key=lambda update: update["sequence"])
                    except Exception:
                        errors.append(link["trip"]["owner"])
                return results, errors
            def done(value):
                self.poll_pending = False
                if generation != self.watch_generation or not self.watching_until:
                    return
                updates, errors = value
                self.latest.update(updates)
                self.sequences.update({key: update["sequence"] for key, update in updates.items()})
                self.update_friend_table()
                self.notice.set("Watching · " + ("Some rooms are unavailable. Check this account was invited." if errors else "Last checked " + datetime.now().strftime("%H:%M:%S")))
            def failed(_):
                self.poll_pending = False
                if generation == self.watch_generation:
                    self.notice.set("Update check failed. Position becomes unknown when stale; retrying.")
            self.submit(work, done, failed)
        self.after(15000, lambda: self.poll(generation))

    def current_update(self, identity):
        update = self.latest.get(identity)
        if not update or time.time() * 1000 - update["sentAt"] > 300000 or update["expiresAt"] <= time.time() * 1000:
            self.latest.pop(identity, None)
            return None
        return update

    def update_friend_table(self):
        if self.page != "Friends" or not hasattr(self, "friend_tree") or not self.friend_tree.winfo_exists():
            return
        tree = self.friend_tree
        tree.delete(*tree.get_children())
        for friend in self.friends:
            update = self.current_update(friend["id"])
            at = datetime.fromtimestamp(update["sentAt"] / 1000).strftime("%H:%M:%S") if update else "—"
            if friend["expiresAt"] <= time.time() * 1000:
                phase = "Expired"
            elif update:
                phase = update["phase"] + (" · " + update["near"] if update.get("near") else "")
            else:
                phase = "Current position unknown"
            tree.insert("", "end", iid=friend["id"], values=(friend["trip"]["owner"], phase, at))
        if self.selected_friend in tree.get_children():
            tree.selection_set(self.selected_friend)

    def view_trip(self):
        friend = next((f for f in self.friends if f["id"] == self.selected_friend), None)
        if not friend:
            self.notice.set("Select a friend first.")
            return
        lines = [friend["trip"]["owner"], "Planned itinerary · South African time\n"]
        for step in friend["trip"]["steps"]:
            stamp = datetime.fromtimestamp(step["at"] / 1000, ZoneInfo("Africa/Johannesburg")).strftime("%d %b %H:%M")
            platform = " · Platform " + str(step.get("platform") or "unknown") if step["kind"] in ("board", "transfer") else ""
            lines.append("%s · %s · %s%s" % (stamp, step["kind"].capitalize(), step["label"], platform))
        self.text_dialog("Planned trip", "\n\n".join(lines))

    def open_map(self):
        update = self.current_update(self.selected_friend)
        if not update or not update.get("point"):
            self.notice.set("No recent shared position is available.")
            return
        point = update["point"]
        webbrowser.open("https://www.openstreetmap.org/?mlat=%s&mlon=%s#map=17/%s/%s" %
            (point["latitude"], point["longitude"], point["latitude"], point["longitude"]))

    def remove_friend(self):
        if not self.selected_friend:
            return
        self.friends = [f for f in self.friends if f["id"] != self.selected_friend]
        self.latest.pop(self.selected_friend, None)
        self.sequences.pop(self.selected_friend, None)
        self.vault.write("friends", self.friends)
        self.selected_friend = None
        self.update_friend_table()
        self.notice.set("Invitation removed from this computer.")

    def page_help(self, parent):
        card = self.card(parent, "Setup & privacy guide", "Follow these steps once, then connect the phone using your own homeserver address.")
        self.button(card, "Copy phone setup", self.copy_setup, True).pack(anchor="w", pady=(12, 18))
        label = self.label(card, guide(), 11, wraplength=700)
        label.pack(fill="x")
        label.bind("<Configure>", lambda e: label.configure(wraplength=max(180, e.width)))

    def copy_setup(self):
        try:
            server = self.host.server_name()
            text = "CT Trains phone setup\nHomeserver: https://" + server + "\nEnable hosted sharing in Settings → Beta features. Connect with your own Matrix account. Ask the trip owner to invite your full @username:" + server + " ID and privately share their .ctcircle file. Sharing stays off until the owner starts it."
            self.clipboard_clear()
            self.clipboard_append(text)
            self.notice.set("Phone setup copied. No password or invitation key included.")
        except ValueError as error:
            self.notice.set(str(error))

    def text_dialog(self, title, text):
        window = tk.Toplevel(self)
        window.title(title)
        window.geometry("820x610")
        window.configure(bg=SURFACE)
        content = tk.Text(window, bg=SURFACE, fg=TEXT, wrap="word", relief="flat", padx=24, pady=20, font=("DejaVu Sans", 11))
        bar = ttk.Scrollbar(window, command=content.yview)
        content.configure(yscrollcommand=bar.set)
        bar.pack(side="right", fill="y")
        content.pack(fill="both", expand=True)
        content.insert("1.0", text)
        content.configure(state="disabled")

    def submit(self, work, success, failure=None):
        def runner():
            try:
                self.results.put((success, work()))
            except Exception as error:
                self.results.put((failure or self.failure, error))
        self.pool.submit(runner)

    def run(self, work, done=None):
        if self.busy:
            self.notice.set("A hosting action is already running. Wait for it to finish.")
            return
        self.busy = True
        self.notice.set("Working… the window stays responsive. First setup can download images.")
        def complete(result):
            self.busy = False
            if done:
                done(result)
            else:
                self.notice.set(str(result))
            self.check_status()
        def fail(error):
            self.busy = False
            self.failure(error)
        self.submit(work, complete, fail)

    def failure(self, error):
        self.notice.set(str(error) if isinstance(error, ValueError) else
            "Action failed. Check connection/permissions and Help. No account credentials were logged.")

    def check_status(self):
        if not self.closing:
            def done(result):
                self.matrix_state.set(result[0])
                self.relay_state.set(result[1])
            self.submit(lambda: (self.host.matrix_status(), self.host.relay_status()), done)

    def drain(self):
        if self.closing:
            return
        try:
            while True:
                callback, value = self.results.get_nowait()
                callback(value)
        except queue.Empty:
            pass
        self.after(100, self.drain)

    def close(self, force=False):
        if self.busy and not force:
            messagebox.showinfo("Hosting action running", "Wait for the hosting action to finish before closing.")
            return
        self.closing = True
        self.watch_stop.set()
        self.watching_until = 0
        self.latest.clear()
        self.host.stop_relay()
        self.pool.shutdown(wait=False, cancel_futures=True)
        self.destroy()
