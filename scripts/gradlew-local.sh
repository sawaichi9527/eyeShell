#!/usr/bin/env bash
set -euo pipefail

readonly ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly JDK_DIR="${ROOT_DIR}/.local/jdk-21"

if [[ ! -x "${JDK_DIR}/bin/java" ]]; then
    printf 'Project-local JDK not found. Run ./scripts/bootstrap-jdk.sh first.\n' >&2
    exit 1
fi

if [[ ! -x "${ROOT_DIR}/gradlew" ]]; then
    printf 'Gradle Wrapper not found or not executable.\n' >&2
    exit 1
fi

export JAVA_HOME="${JDK_DIR}"
export GRADLE_USER_HOME="${ROOT_DIR}/.local/gradle-home"
export PATH="${JAVA_HOME}/bin:${PATH}"

exec "${ROOT_DIR}/gradlew" "$@"
