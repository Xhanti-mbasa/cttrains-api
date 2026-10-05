import tempfile
import tkinter as tk
import unittest
from unittest.mock import patch
from ctdesktop.app import Desktop
from test_services import circle
import time

class DesktopTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.app = Desktop(self.root.name)
        self.app.update()

    def tearDown(self):
        self.app.close(force=True)
        self.root.cleanup()

    def test_every_page_resizes_and_scrolls(self):
        for page in ("Overview", "Friends", "Servers", "Help"):
            self.app.show(page)
            self.app.geometry("940x640")
            self.app.update()
            self.assertEqual(self.app.page_title.cget("text"), page)
            self.assertTrue(self.app.page_host.winfo_children())
        self.app.show("Help")
        self.app.update()
        scroll = self.app.page_host.winfo_children()[0]
        self.assertGreater(scroll.content.winfo_height(), scroll.canvas.winfo_height())

    def test_stop_watching_clears_position_and_cancels_generation(self):
        self.app.latest["x"] = {"sentAt": int(time.time() * 1000), "expiresAt": int(time.time() * 1000) + 60000}
        self.app.watching_until = time.time() + 60
        old = self.app.watch_generation
        self.app.stop_watch()
        self.assertEqual(self.app.latest, {})
        self.assertTrue(self.app.watch_stop.is_set())
        self.assertGreater(self.app.watch_generation, old)

    def test_expired_fix_is_unknown(self):
        now = int(time.time() * 1000)
        self.app.latest["x"] = {"sentAt": now - 300001, "expiresAt": now + 60000}
        self.assertIsNone(self.app.current_update("x"))

    def test_no_invitation_no_tracking(self):
        self.app.start_watch()
        self.assertEqual(self.app.watching_until, 0)

    def test_remove_forgets_secret(self):
        now = int(time.time() * 1000)
        self.app.friends = [circle(now)]
        self.app.selected_friend = "trip-1"
        self.app.remove_friend()
        self.assertEqual(self.app.vault.read("friends"), [])

if __name__ == "__main__":
    unittest.main()
