#!/usr/bin/env bash
set -euo pipefail
# 仅本地演示：所有RPC/P2P端口绑定回环地址。已存在的输出目录必须由操作者另行处理。
if [[ $# != 3 ]]; then echo "Usage: $0 /absolute/path/fisco-bcos admin-address /absolute/new/output-dir" >&2; exit 2; fi
binary=$(realpath "$1")
admin=$2
output=$3
[[ "$admin" =~ ^0x[0-9a-fA-F]{40}$ ]] || { echo 'Invalid admin address' >&2; exit 2; }
[[ "$output" = /* && ! -e "$output" ]] || { echo 'Output must be a new absolute path' >&2; exit 2; }
[[ -x "$binary" && -x "$HOME/.fisco/tassl-1.1.1b" ]] || { echo 'Install the official FISCO3.8.0 binary and TASSL1.1.1b first' >&2; exit 2; }
"$binary" --version | grep -q '3.8.0' || { echo 'FISCO3.8.0 required' >&2; exit 2; }
umask 077
mkdir -p "$output"
curl -fL --retry 3 'https://raw.githubusercontent.com/FISCO-BCOS/FISCO-BCOS/v3.8.0/tools/BcosAirBuilder/build_chain.sh' -o "$output/build_chain.sh"
cd "$output"
bash build_chain.sh -l 127.0.0.1:4 -p 30350,20250 -v 3.8.0 -e "$binary" -a "$admin" -o nodes
sed -i 's/listen_ip=0.0.0.0/listen_ip=127.0.0.1/g' nodes/127.0.0.1/node*/config.ini
echo 'Generated four local consensus nodes. Run nodes/127.0.0.1/start_all.sh, then use FiscoAdminMain observer to convert node3.'
