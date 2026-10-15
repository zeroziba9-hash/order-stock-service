"""Check a deployed STOCKROOM origin with verified TLS and GET requests only."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import ssl
import urllib.error
import urllib.parse
import urllib.request


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def origin(parser, value, scheme):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != scheme or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment):
        parser.error(f"Expected a {scheme} origin without credentials, path, query or fragment")
    return parsed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="HTTPS origin, e.g. https://shop.example.com")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--local-test-ca", type=Path, help="Disposable localhost CA; never accepted for a public host")
    parser.add_argument("--local-test-http-url", help="Disposable localhost HTTP redirect origin")
    args = parser.parse_args()
    target = origin(parser, args.url, "https")
    local = args.local_test_ca is not None
    if local:
        if (target.hostname != "localhost" or target.port != 18443 or not args.local_test_ca.is_file()
                or not args.local_test_http_url):
            parser.error("Local test mode requires localhost:18443, its CA file and localhost:18081 HTTP origin")
        http = origin(parser, args.local_test_http_url, "http")
        if http.hostname != "localhost" or http.port != 18081:
            parser.error("Local HTTP test origin must be http://localhost:18081")
        http_url = args.local_test_http_url.rstrip("/")
    else:
        if target.hostname in ("localhost", "127.0.0.1", "::1") or target.port not in (None, 443) or args.local_test_http_url:
            parser.error("Use a deployed HTTPS hostname on port 443; local tests require explicit local test mode")
        http_host = f"[{target.hostname}]" if ":" in target.hostname else target.hostname
        http_url = "http://" + http_host

    context = ssl.create_default_context(cafile=str(args.local_test_ca) if local else None)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                        urllib.request.HTTPSHandler(context=context))
    checks = []

    def get(url, expected, label):
        request = urllib.request.Request(url, method="GET", headers={"User-Agent": "stockroom-deployment-check/1"})
        try:
            response = opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            require(response.status == expected, f"{label}: expected HTTP {expected}, received {response.status}")
            body = response.read(4 * 1024 * 1024 + 1)
            require(len(body) <= 4 * 1024 * 1024, f"{label}: response exceeds size limit")
            checks.append({"check": label, "status": response.status, "passed": True})
            return body, response.headers

    base = args.url.rstrip("/")
    body, headers = get(http_url + "/products", 308, "HTTP redirect")
    location = urllib.parse.urlsplit(headers.get("Location", ""))
    require(location.scheme == "https" and location.hostname == target.hostname
            and location.port in (None, 443) and location.path == "/products"
            and not location.username and not location.password and not location.query and not location.fragment,
            "HTTP redirect must preserve the same host and product path over HTTPS")
    body, headers = get(base + "/", 200, "Storefront HTML")
    require(headers.get_content_type() == "text/html" and "STOCKROOM" in body.decode("utf-8"), "Unexpected storefront HTML")
    for path, types in [("/assets/store.css", {"text/css"}),
                        ("/assets/store.js", {"text/javascript", "application/javascript"})]:
        body, headers = get(base + path, 200, "Asset " + path)
        require(body and headers.get_content_type() in types, "Unexpected asset content type: " + path)

    def data(path, expected=200):
        body, headers = get(base + path, expected, path)
        content_type = headers.get_content_type()
        # Actuator uses application/vnd.spring-boot.actuator.v3+json by default.
        require(content_type == "application/json" or
                (content_type.startswith("application/") and content_type.endswith("+json")),
                "Expected JSON: " + path)
        return json.loads(body)

    require(data("/actuator/health/readiness") == {"status": "UP"}, "Application not ready")
    products = data("/products")
    require(isinstance(products, list) and all(isinstance(p, dict) and isinstance(p.get("id"), int)
            and isinstance(p.get("stock"), int) and p["stock"] >= 0
            and isinstance(p.get("price"), int) and p["price"] >= 0 for p in products), "Invalid catalog")
    search = data("/products/search?sort=PRICE_ASC&page=0")
    require(isinstance(search.get("products"),list) and len(search["products"])<=20
            and search.get("page")==0 and isinstance(search.get("hasMore"),bool), "Invalid paged catalog")
    require(data("/storefront/config") == {"mockPayments": False, "mockRefunds": False}, "Mock writes must be disabled")
    for path in ("/auth/me", "/auth/sessions", "/orders", "/cart"):
        require(data(path, 401).get("code") == "AUTH_REQUIRED", "Authentication required: " + path)

    report = {"format": "order-stock-deployment-v1", "checkedAtUtc": datetime.now(timezone.utc).isoformat(),
              "mode": "local-test" if local else "deployed-origin", "origin": base,
              "tlsVerification": True, "allChecksPassed": True, "checks": checks}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(checks)} GET checks; verified TLS; mode={report['mode']}")
    print("Scope: reachability from this machine; login, order writes and global DNS propagation are not tested.")


if __name__ == "__main__":
    main()
