#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"

cd "${project_root}/frontend"
npm ci
npm run build

cd "${project_root}"
mvn -pl backend clean package "$@"

jar_path="${project_root}/backend/target/micropass-0.0.1-SNAPSHOT.jar"
jar_entries="$(jar tf "${jar_path}")"
if [[ "${jar_entries}" != *"BOOT-INF/classes/static/index.html"* ]]; then
  printf 'Combined build failed: React index.html is missing from %s\n' "${jar_path}" >&2
  exit 1
fi

printf 'Combined build ready: %s\n' "${jar_path}"
