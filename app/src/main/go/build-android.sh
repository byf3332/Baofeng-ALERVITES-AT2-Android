#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "$script_dir/../../../.." && pwd)"
config_file="$script_dir/toolchain.properties"

if [[ ! -f "$config_file" ]]; then
  echo "Missing toolchain.properties. Copy toolchain.properties.example and edit its four paths." >&2
  exit 1
fi

declare -A config
while IFS='=' read -r key value; do
  key="${key//$'\r'/}"
  value="${value//$'\r'/}"
  [[ -z "$key" || "$key" == \#* ]] && continue
  config["$key"]="$value"
done < "$config_file"

for key in GO_ROOT GO_TOOLS_ROOT ANDROID_SDK_ROOT BUILD_CACHE_ROOT; do
  if [[ -z "${config[$key]:-}" ]]; then
    echo "Missing toolchain property: $key" >&2
    exit 1
  fi
done

go_root="${config[GO_ROOT]}"
go_tools_root="${config[GO_TOOLS_ROOT]}"
android_sdk_root="${config[ANDROID_SDK_ROOT]}"
build_root="${config[BUILD_CACHE_ROOT]}/libgojni"

to_shell_path() {
  local path="$1"
  if [[ "$path" =~ ^([A-Za-z]):/(.*)$ ]]; then
    local drive="${BASH_REMATCH[1],,}"
    printf '/%s/%s' "$drive" "${BASH_REMATCH[2]}"
  else
    printf '%s' "$path"
  fi
}

go_root_path="$(to_shell_path "$go_root")"
go_tools_root_path="$(to_shell_path "$go_tools_root")"
go_bin="$go_root/bin/go"
gomobile_bin="$go_tools_root/bin/gomobile"
gobind_bin="$go_tools_root/bin/gobind"
ndk_version="28.2.13676358"

for executable in "$go_bin" "$gomobile_bin" "$gobind_bin"; do
  if [[ ! -x "$executable" ]]; then
    echo "Required executable not found: $executable" >&2
    exit 1
  fi
done
if [[ ! -d "$android_sdk_root/ndk/$ndk_version" ]]; then
  echo "Android NDK $ndk_version not found under $android_sdk_root" >&2
  exit 1
fi
if ! command -v unzip >/dev/null 2>&1; then
  echo "unzip is required." >&2
  exit 1
fi

mkdir -p "$build_root/gopath/pkg/mod" "$build_root/go-build-cache" \
  "$build_root/tmp" "$build_root/android-user-home"

export GOPATH="$build_root/gopath"
export GOMODCACHE="$build_root/gopath/pkg/mod"
export GOCACHE="$build_root/go-build-cache"
export GOTELEMETRY=off
export GOENV=off
export GOTOOLCHAIN=local
export GOFLAGS='-trimpath'
export GOTMPDIR="$build_root/tmp"
export TMPDIR="$build_root/tmp"
export ANDROID_USER_HOME="$build_root/android-user-home"
export ANDROID_HOME="$android_sdk_root"
export ANDROID_SDK_ROOT="$android_sdk_root"
export ANDROID_NDK_HOME="$android_sdk_root/ndk/$ndk_version"
export CGO_LDFLAGS='-Wl,-z,max-page-size=16384'
export PATH="$go_root_path/bin:$go_tools_root_path/bin:$PATH"

go_version="$($go_bin version)"
if [[ ! "$go_version" =~ go1\.24(\.0)?([[:space:]]|$) ]]; then
  echo "Go 1.24.0 is required; found: $go_version" >&2
  exit 1
fi

aar_path="$build_root/resizer.aar"
extract_path="$build_root/aar"
destination="$project_root/app/src/main/jniLibs/arm64-v8a/libgojni.so"

cd "$script_dir"
"$go_bin" mod download
rm -f -- "$aar_path"
"$gomobile_bin" bind -target=android/arm64 -androidapi=27 -o "$aar_path" ./resizer
rm -rf -- "$extract_path"
mkdir -p "$extract_path" "$(dirname -- "$destination")"
unzip -q "$aar_path" -d "$extract_path"
cp -- "$extract_path/jni/arm64-v8a/libgojni.so" "$destination"
echo "Updated $destination"
