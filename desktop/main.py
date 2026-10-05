#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from ctdesktop import __version__

def main():
    parser = argparse.ArgumentParser(description="CT Trains Desktop companion and private hosting console")
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--relay", metavar="DATA_DIRECTORY")
    parser.add_argument("--smoke-test", action="store_true")
    parser.add_argument("--data-dir", type=Path)
    parser.add_argument("--screenshot", type=Path)
    args = parser.parse_args()
    if args.version:
        print("CT Trains Desktop " + __version__)
        return
    if args.relay:
        from ctdesktop.relay import serve
        serve(args.relay)
        return
    from ctdesktop.app import Desktop
    app = Desktop(args.data_dir)
    if args.smoke_test:
        def check():
            try:
                for name in ("Friends", "Servers", "Help", "Overview"):
                    app.show(name)
                    app.update_idletasks()
                if args.screenshot:
                    from PIL import ImageGrab
                    ImageGrab.grab().save(args.screenshot)
                print(json.dumps({"desktop": "launched", "pages": 4, "version": __version__}))
            finally:
                app.close(force=True)
        app.after(800, check)
    app.mainloop()

if __name__ == "__main__":
    main()
