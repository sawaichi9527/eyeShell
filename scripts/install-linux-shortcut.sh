#!/usr/bin/env bash
set -euo pipefail

# Installs (or removes) a per-user .desktop entry and icon so GNOME links the eyeShell window to the
# application. Without it, the window's WM_CLASS (derived by AWT from the main class) has no desktop
# entry to match and the dock/app menu fall back to a generic gear icon.
#
# The entry launches the AppImage with APPIMAGELAUNCHER_DISABLE=1 so AppImageLauncher does not move
# the file, rewrite the entry, or add a second "eyeShell (1)" shortcut with a missing icon.
#
# Usage:
#   scripts/install-linux-shortcut.sh [path/to/eyeShell-<version>-x86_64.AppImage]
#   scripts/install-linux-shortcut.sh --remove
#
# Only files under $XDG_DATA_HOME (default ~/.local/share) are written; nothing system-wide.

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly APP_ID="eyeshell"
readonly APP_NAME="eyeShell"
readonly WM_CLASS="io-github-sawaichi9527-eyeshell-MainKt"
readonly ICON_SIZES=(16 32 48 64 128 256 512)
readonly DATA_HOME="${XDG_DATA_HOME:-${HOME}/.local/share}"
readonly ICON_ROOT="${DATA_HOME}/icons/hicolor"
readonly DESKTOP_DIR="${DATA_HOME}/applications"
readonly DESKTOP_PATH="${DESKTOP_DIR}/${APP_ID}.desktop"

usage() {
    printf 'Usage: %s [path/to/eyeShell-<version>-x86_64.AppImage]\n' "$(basename "$0")" >&2
    printf '       %s --remove\n' "$(basename "$0")" >&2
}

refresh_caches() {
    if command -v update-desktop-database >/dev/null; then
        update-desktop-database "${DESKTOP_DIR}" >/dev/null 2>&1 || true
    fi
    if command -v gtk-update-icon-cache >/dev/null; then
        gtk-update-icon-cache --force --ignore-theme-index "${ICON_ROOT}" >/dev/null 2>&1 || true
    fi
}

if [[ "${1:-}" == "--remove" ]]; then
    rm -f "${DESKTOP_PATH}"
    for size in "${ICON_SIZES[@]}"; do
        rm -f "${ICON_ROOT}/${size}x${size}/apps/${APP_ID}.png"
    done
    refresh_caches
    printf 'Removed %s and the hicolor %s icons\n' "${DESKTOP_PATH}" "${APP_ID}"
    exit 0
fi

if [[ $# -gt 1 ]]; then
    usage
    exit 2
fi

app_image="${1:-}"
if [[ -z "${app_image}" ]]; then
    mapfile -t candidates < <(find "${ROOT_DIR}/build/package" -maxdepth 1 -name 'eyeShell-*-x86_64.AppImage' -type f 2>/dev/null | sort)
    if [[ ${#candidates[@]} -eq 0 ]]; then
        printf 'No AppImage found under build/package. Run ./scripts/gradlew-local.sh appImage first,\n' >&2
        printf 'or pass the AppImage path as the first argument.\n' >&2
        exit 1
    fi
    app_image="${candidates[${#candidates[@]} - 1]}"
fi

if [[ ! -f "${app_image}" ]]; then
    printf 'AppImage not found: %s\n' "$app_image" >&2
    exit 1
fi

app_image="$(cd "$(dirname "$app_image")" && pwd)/$(basename "$app_image")"
chmod +x "${app_image}"

mkdir -p "${DESKTOP_DIR}"
for size in "${ICON_SIZES[@]}"; do
    source_icon="${ROOT_DIR}/src/main/resources/icons/eyeshell-${size}.png"
    if [[ ! -f "${source_icon}" ]]; then
        printf 'Icon source not found: %s\n' "${source_icon}" >&2
        exit 1
    fi
    mkdir -p "${ICON_ROOT}/${size}x${size}/apps"
    cp -f "${source_icon}" "${ICON_ROOT}/${size}x${size}/apps/${APP_ID}.png"
done

# AppImageLauncher re-adds its own appimagekit_* entry with Name=eyeShell (1) and an icon it failed
# to extract; remove those stale entries so the menu shows one eyeShell shortcut.
removed_stale=0
for stale in "${DESKTOP_DIR}"/appimagekit_*eyeShell*.desktop; do
    [[ -e "${stale}" ]] || continue
    rm -f "${stale}"
    removed_stale=$((removed_stale + 1))
done

# No TryExec: GLib rejects a desktop entry whose quoted TryExec path cannot be resolved, which makes
# the whole entry invisible to GNOME. TryExec is optional, so it is omitted for quoted paths.
cat > "${DESKTOP_PATH}" <<EOF
[Desktop Entry]
Type=Application
Name=${APP_NAME}
Comment=Local-first visual SSH console workbench
Exec=env APPIMAGELAUNCHER_DISABLE=1 "${app_image}"
Icon=${APP_ID}
Terminal=false
Categories=Network;
StartupWMClass=${WM_CLASS}
EOF
chmod 644 "${DESKTOP_PATH}"

if command -v desktop-file-validate >/dev/null; then
    desktop-file-validate "${DESKTOP_PATH}" || true
fi
refresh_caches

printf 'Installed %s\n' "${DESKTOP_PATH}"
printf 'Installed hicolor icons under %s/*/apps/%s.png\n' "${ICON_ROOT}" "${APP_ID}"
printf 'Exec: env APPIMAGELAUNCHER_DISABLE=1 "%s"\n' "${app_image}"
printf 'StartupWMClass: %s\n' "${WM_CLASS}"
if [[ "${removed_stale}" -gt 0 ]]; then
    printf 'Removed %s stale AppImageLauncher entr(y/ies) for eyeShell.\n' "${removed_stale}"
fi
