#!/usr/bin/env bash
set -euo pipefail
# 只复制节点配置与证书，不复制链数据库；只用于新建测试群组。
if [[ $# != 3 ]]; then echo "Usage: $0 generated-nodes/127.0.0.1 new-secret-staging-dir namespace" >&2; exit 2; fi
source_dir=$(realpath "$1")
staging=$2
namespace=$3
[[ "$namespace" =~ ^[a-z0-9-]+$ && ! -e "$staging" ]] || { echo 'Invalid namespace or existing staging directory' >&2; exit 2; }
umask 077
mkdir -p "$staging"
endpoints=()
for index in 0 1 2 3; do
  ip=$(kubectl -n "$namespace" get service "fisco${index}-node" -o jsonpath='{.spec.clusterIP}')
  [[ "$ip" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Create IPv4 node Services first' >&2; exit 2; }
  endpoints+=("\"${ip}:30300\"")
done
nodes=$(IFS=,; echo "${endpoints[*]}")
for index in 0 1 2 3; do
  target="$staging/node${index}"
  mkdir -p "$target"
  cp "$source_dir/node${index}/conf/ca.crt" "$source_dir/node${index}/conf/ssl.crt" "$source_dir/node${index}/conf/ssl.key" "$source_dir/node${index}/conf/node.pem" "$source_dir/node${index}/conf/node.nodeid" "$target/"
  cp "$source_dir/node${index}/config.genesis" "$target/"
  sed -E 's/listen_ip=.*/listen_ip=0.0.0.0/; s/listen_port=303[0-9]+/listen_port=30300/; s/listen_port=202[0-9]+/listen_port=20200/; s|nodes_path=.*|nodes_path=/config|; s|ca_path=\./conf|ca_path=/config|; s|private_key_path=.*|private_key_path=/config/node.pem|' "$source_dir/node${index}/config.ini" > "$target/config.ini"
  printf '{"nodes":[%s]}\n' "$nodes" > "$target/nodes.json"
  kubectl -n "$namespace" create secret generic "fisco-node${index}" --from-file="$target" --dry-run=client -o yaml | kubectl apply -f -
done
mkdir -p "$staging/sdk"
cp "$source_dir/sdk/ca.crt" "$source_dir/sdk/sdk.crt" "$source_dir/sdk/sdk.key" "$staging/sdk/"
cat > "$staging/sdk/sdk.toml" <<'TOML'
[cryptoMaterial]
certPath = "/run/fisco-sdk"
[network]
peers = ["fisco0-node:20200", "fisco1-node:20200"]
[account]
keyStoreDir = "/tmp/fisco-unused-account"
accountFileFormat = "pem"
[threadPool]
threadPoolSize = "4"
TOML
kubectl -n "$namespace" create secret generic fisco-sdk --from-file="$staging/sdk" --dry-run=client -o yaml | kubectl apply -f -
