#!/usr/bin/env bash
set -euo pipefail

# Installs the pinned appimagetool used by the `appImage` Gradle task. The release archive is an
# AppImage; it is extracted once into `.local/appimagetool` so packaging never needs FUSE or a
# system-wide installation. Provenance: AppImage/appimagetool tag 1.9.0, asset
# appimagetool-x86_64.AppImage, SHA-256 recorded below and re-verified on every download.
readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly TOOL_VERSION="1.9.0"
readonly DOWNLOAD_URL="https://github.com/AppImage/appimagetool/releases/download/${TOOL_VERSION}/appimagetool-x86_64.AppImage"
readonly EXPECTED_SHA256="46fdd785094c7f6e545b61afcfb0f3d98d8eab243f644b4b17698c01d06083d1"
readonly LOCAL_DIR="${ROOT_DIR}/.local"
readonly TOOL_DIR="${LOCAL_DIR}/appimagetool"
readonly DOWNLOAD_DIR="${LOCAL_DIR}/downloads"
readonly ARCHIVE_PATH="${DOWNLOAD_DIR}/appimagetool-x86_64-${TOOL_VERSION}.AppImage"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    printf 'Unsupported platform. appimagetool is installed for Linux x86_64 only.\n' >&2
    exit 1
fi

if [[ -x "${TOOL_DIR}/AppRun" ]]; then
    printf 'appimagetool %s is already installed at %s\n' "${TOOL_VERSION}" "${TOOL_DIR}"
    exit 0
fi

command -v curl >/dev/null || {
    printf 'curl is required but was not found.\n' >&2
    exit 1
}
command -v sha256sum >/dev/null || {
    printf 'sha256sum is required but was not found.\n' >&2
    exit 1
}

mkdir -p "${DOWNLOAD_DIR}" "${LOCAL_DIR}/tmp"

if [[ ! -f "${ARCHIVE_PATH}" ]]; then
    printf 'Downloading appimagetool %s to %s\n' "${TOOL_VERSION}" "${ARCHIVE_PATH}"
    curl --fail --location --retry 3 --continue-at - --output "${ARCHIVE_PATH}.part" "${DOWNLOAD_URL}"
    mv "${ARCHIVE_PATH}.part" "${ARCHIVE_PATH}"
fi

actual_sha256="$(sha256sum "${ARCHIVE_PATH}" | cut -d ' ' -f 1)"
if [[ "${actual_sha256}" != "${EXPECTED_SHA256}" ]]; then
    printf 'Checksum mismatch for %s\nExpected: %s\nActual:   %s\n' \
        "${ARCHIVE_PATH}" "${EXPECTED_SHA256}" "${actual_sha256}" >&2
    exit 1
fi

if [[ -e "${TOOL_DIR}" ]]; then
    printf 'A file or directory already exists at %s. Remove it explicitly before bootstrapping.\n' \
        "${TOOL_DIR}" >&2
    exit 1
fi

printf 'Extracting appimagetool %s into %s\n' "${TOOL_VERSION}" "${TOOL_DIR}"
extract_dir="$(mktemp -d "${LOCAL_DIR}/tmp/appimagetool.XXXXXX")"
trap 'rm -rf "${extract_dir}"' EXIT

chmod +x "${ARCHIVE_PATH}"
(
    cd "${extract_dir}"
    APPIMAGE_EXTRACT_AND_RUN=1 "${ARCHIVE_PATH}" --appimage-extract >/dev/null
)

mv "${extract_dir}/squashfs-root" "${TOOL_DIR}"
chmod +x "${TOOL_DIR}/AppRun"
"${TOOL_DIR}/AppRun" --version >/dev/null
printf 'appimagetool %s is installed at %s\n' "${TOOL_VERSION}" "${TOOL_DIR}"
