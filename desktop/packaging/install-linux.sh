#!/bin/sh
set -eu
app_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
data_base="${XDG_DATA_HOME:-$HOME/.local/share}"
destination="$data_base/cttrains-desktop-app"
mkdir -p "$destination" "$data_base/applications"
install -m 755 "$app_dir/CT-Trains-Desktop" "$destination/CT-Trains-Desktop"
install -m 644 "$app_dir/cttrains.svg" "$destination/cttrains.svg"
cat > "$data_base/applications/cttrains-desktop.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=CT Trains Desktop
Comment=Private trip updates and Matrix hosting
Exec="$destination/CT-Trains-Desktop"
Icon=$destination/cttrains.svg
Terminal=false
Categories=Network;Utility;
StartupNotify=true
EOF
printf 'Installed CT Trains Desktop in your application menu.\n'
