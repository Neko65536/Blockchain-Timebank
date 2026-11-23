#!/usr/bin/env python3
"""公告缓存读取压测：16 并发、60 秒、keep-alive，对齐旧 JMeter 口径。

目标：GET http://127.0.0.1:8080/dashboard/notices?limit=10
断言：HTTP 200 + body.code==1 + data 为非空 List
"""
import http.client
import json
import time
import threading
import statistics
from concurrent.futures import ThreadPoolExecutor

HOST = "127.0.0.1"
PORT = 8080
PATH = "/dashboard/notices?limit=10"
THREADS = 16
DURATION = 60.0

results = []          # 每次请求延迟（秒）
errors = 0
lock = threading.Lock()
start_time = time.time()


def worker():
    global errors
    conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
    while time.time() - start_time < DURATION:
        t0 = time.time()
        ok = True
        try:
            conn.request("GET", PATH)
            resp = conn.getresponse()
            body = resp.read()
            elapsed = time.time() - t0
            if resp.status != 200:
                ok = False
            else:
                data = json.loads(body)
                if data.get("code") != 1 or not isinstance(data.get("data"), list) or not data.get("data"):
                    ok = False
        except Exception:
            ok = False
            elapsed = time.time() - t0
            # 连接可能断开，重建
            try:
                conn.close()
            except Exception:
                pass
            conn = http.client.HTTPConnection(HOST, PORT, timeout=10)
        with lock:
            if not ok:
                errors += 1
            results.append(elapsed)
    try:
        conn.close()
    except Exception:
        pass


def main():
    with ThreadPoolExecutor(max_workers=THREADS) as ex:
        futures = [ex.submit(worker) for _ in range(THREADS)]
        for f in futures:
            f.result()

    elapsed_total = time.time() - start_time
    lat = sorted(r * 1000 for r in results)
    n = len(lat)

    def pct(p):
        if n == 0:
            return 0.0
        return lat[min(n - 1, int(n * p / 100))]

    out = {
        "samples": n,
        "errors": errors,
        "errorPct": round(errors / n * 100, 6) if n else 0.0,
        "durationSec": round(elapsed_total, 2),
        "throughputQps": round(n / elapsed_total, 2) if elapsed_total else 0,
        "meanMs": round(statistics.mean(lat), 2) if lat else 0,
        "medianMs": round(pct(50), 2),
        "p95Ms": round(pct(95), 2),
        "p99Ms": round(pct(99), 2),
        "minMs": round(min(lat), 2) if lat else 0,
        "maxMs": round(max(lat), 2) if lat else 0,
    }
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
