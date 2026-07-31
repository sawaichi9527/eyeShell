#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly JDK_VERSION="21.0.12+8"
readonly ARCHIVE_NAME="OpenJDK21U-jdk_x64_linux_hotspot_21.0.12_8.tar.gz"
readonly DOWNLOAD_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.12%2B8/${ARCHIVE_NAME}"
readonly EXPECTED_SHA256="e4446ff06a276155697597cc0f1b15da004ff083f4964a35271ecee567177370"
readonly LOCAL_DIR="${ROOT_DIR}/.local"
readonly JDK_DIR="${LOCAL_DIR}/jdk-21"
readonly DOWNLOAD_DIR="${LOCAL_DIR}/downloads"
readonly ARCHIVE_PATH="${DOWNLOAD_DIR}/${ARCHIVE_NAME}"

if [[ "$(uname -s)" != "Linux" || "$(uname -m)" != "x86_64" ]]; then
    printf 'Unsupported platform. This bootstrap supports Linux x86_64 only.\n' >&2
    exit 1
fi

if [[ -x "${JDK_DIR}/bin/java" ]]; then
    if [[ -f "${JDK_DIR}/release" ]] &&
        grep -Fxq 'IMPLEMENTOR="Eclipse Adoptium"' "${JDK_DIR}/release" &&
        grep -Fxq 'IMPLEMENTOR_VERSION="Temurin-21.0.12+8"' "${JDK_DIR}/release"; then
        if ! "${JDK_DIR}/bin/java" -version >/dev/null 2>&1; then
            printf 'The pinned JDK metadata is present, but java could not execute.\n' >&2
            exit 1
        fi
        printf 'Temurin %s is already installed at %s\n' "${JDK_VERSION}" "${JDK_DIR}"
        exit 0
    fi

    printf 'A different JDK already exists at %s. Remove it explicitly before bootstrapping.\n' "${JDK_DIR}" >&2
    exit 1
fi

command -v curl >/dev/null || {
    printf 'curl is required but was not found.\n' >&2
    exit 1
}
command -v sha256sum >/dev/null || {
    printf 'sha256sum is required but was not found.\n' >&2
    exit 1
}
command -v tar >/dev/null || {
    printf 'tar is required but was not found.\n' >&2
    exit 1
}

mkdir -p "${DOWNLOAD_DIR}" "${LOCAL_DIR}/tmp"

if [[ ! -f "${ARCHIVE_PATH}" ]]; then
    printf 'Downloading Temurin %s to %s\n' "${JDK_VERSION}" "${ARCHIVE_PATH}"
    curl --fail --location --retry 3 --continue-at - --output "${ARCHIVE_PATH}.part" "${DOWNLOAD_URL}"
    mv "${ARCHIVE_PATH}.part" "${ARCHIVE_PATH}"
fi

actual_sha256="$(sha256sum "${ARCHIVE_PATH}" | cut -d ' ' -f 1)"
if [[ "${actual_sha256}" != "${EXPECTED_SHA256}" ]]; then
    printf 'Checksum mismatch for %s\nExpected: %s\nActual:   %s\n' \
        "${ARCHIVE_PATH}" "${EXPECTED_SHA256}" "${actual_sha256}" >&2
    exit 1
fi

staging_dir="$(mktemp -d "${LOCAL_DIR}/tmp/jdk.XXXXXX")"
trap 'rm -rf "${staging_dir}"' EXIT

tar -xzf "${ARCHIVE_PATH}" -C "${staging_dir}"
extracted_dir="$(find "${staging_dir}" -mindepth 1 -maxdepth 1 -type d -print -quit)"
if [[ -z "${extracted_dir}" || ! -x "${extracted_dir}/bin/java" ]]; then
    printf 'The downloaded archive did not contain a usable JDK.\n' >&2
    exit 1
fi

mv "${extracted_dir}" "${JDK_DIR}"
"${JDK_DIR}/bin/java" -version
printf 'Installed project-local Temurin %s at %s\n' "${JDK_VERSION}" "${JDK_DIR}"
