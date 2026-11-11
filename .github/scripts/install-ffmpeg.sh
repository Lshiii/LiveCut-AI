#!/usr/bin/env bash
set -euo pipefail

: "${LIVECUT_FFMPEG_PATH:=/usr/bin/ffmpeg}"
: "${LIVECUT_FFPROBE_PATH:=/usr/bin/ffprobe}"
if [ -x "$LIVECUT_FFMPEG_PATH" ] && [ -x "$LIVECUT_FFPROBE_PATH" ] \
  && "$LIVECUT_FFMPEG_PATH" -version >/dev/null 2>&1 \
  && "$LIVECUT_FFPROBE_PATH" -version >/dev/null 2>&1; then
  echo "Using existing FFmpeg and ffprobe."
  exit 0
fi

ubuntu_sources=/etc/apt/sources.list.d/ubuntu.sources
if [ ! -f "$ubuntu_sources" ]; then
  echo "::error::Expected Ubuntu 24.04 package sources are missing."
  exit 1
fi
# Keep the runner's global configuration intact. Both attempts use the Ubuntu
# archive signing key; no third-party binary or disabled signature check.
fallback_sources="${RUNNER_TEMP:?RUNNER_TEMP is required}/livecut-ubuntu.sources"
cat > "$fallback_sources" <<'SOURCES'
Types: deb
URIs: https://archive.ubuntu.com/ubuntu
Suites: noble noble-updates noble-backports
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg

Types: deb
URIs: https://security.ubuntu.com/ubuntu
Suites: noble-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
SOURCES

sources=("$ubuntu_sources" "$fallback_sources")
mirror_names=(runner-source official-https)
update_limits=(30s 60s)
download_limits=(45s 75s)

run_phase() {
  local mirror="$1" phase="$2" limit="$3" status
  shift 3
  echo "FFmpeg package mirror=$mirror phase=$phase limit=$limit: starting."
  if timeout --kill-after=5s "$limit" "$@"; then
    echo "FFmpeg package mirror=$mirror phase=$phase exit=0."
    return 0
  else
    status=$?
    echo "FFmpeg package mirror=$mirror phase=$phase exit=$status."
    return "$status"
  fi
}

prepared=false
for attempt in 0 1; do
  mirror="$((attempt + 1))/2:${mirror_names[$attempt]}"
  apt_options=(
    -o "Dir::Etc::sourcelist=${sources[$attempt]}"
    -o Dir::Etc::sourceparts=-
    -o APT::Get::List-Cleanup=0
    -o APT::Update::Error-Mode=any
    -o Acquire::Retries=1
    -o Acquire::http::Timeout=10
    -o Acquire::https::Timeout=10
    -o DPkg::Lock::Timeout=10
  )
  # Bound actual elapsed download time, not only idle socket time. Downloading
  # separately leaves dpkg untouched if a mirror stalls. Cached .deb files can
  # be reused by the second, official mirror after normal hash verification.
  if run_phase "$mirror" update "${update_limits[$attempt]}" sudo apt-get "${apt_options[@]}" update \
    && run_phase "$mirror" download "${download_limits[$attempt]}" sudo apt-get "${apt_options[@]}" \
      install --yes --download-only --no-install-recommends ffmpeg; then
    prepared=true
    break
  fi
  if [ "$attempt" -eq 0 ]; then
    echo "Switching to the bounded official HTTPS package source."
  fi
done
if [ "$prepared" != true ]; then
  echo "::error::Unable to download signed Ubuntu FFmpeg packages within the mirror budgets."
  exit 1
fi
# With downloads complete this step cannot stall on a mirror. The combined
# maximum, including five kill grace periods, is 280s: below the five-minute step.
run_phase "$mirror" install 45s sudo env DEBIAN_FRONTEND=noninteractive \
  apt-get "${apt_options[@]}" install --yes --no-download --no-install-recommends ffmpeg
"$LIVECUT_FFMPEG_PATH" -version
"$LIVECUT_FFPROBE_PATH" -version
