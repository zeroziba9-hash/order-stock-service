"""Verify image bytes and registered checkout recovery across disposable CI backup/recreation."""
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import urllib.request
import uuid

spec=importlib.util.spec_from_file_location("smoke",Path(__file__).with_name("compose-smoke.py"))
smoke=importlib.util.module_from_spec(spec);spec.loader.exec_module(smoke)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase",choices=["prepare","verify"])
    parser.add_argument("--state-file",type=Path,required=True)
    parser.add_argument("--env-file",type=Path,required=True)
    parser.add_argument("--project",required=True)
    args=parser.parse_args()
    if os.environ.get("CI")!="true" or args.project not in {"order-stock-backup-source-ci","order-stock-backup-target-ci"}:
        raise SystemExit("Only the dedicated disposable CI backup projects are supported")
    state=json.loads(args.state_file.read_text(encoding="utf-8"));client=smoke.Client();client.login(state["account"])
    product=state["products"][0]
    def sql(query):
        result=subprocess.run(["docker","compose","-p",args.project,"--env-file",str(args.env_file),"-f",str(Path(__file__).resolve().parents[2]/"deploy/compose.prod.yaml"),"exec","-T","db","sh","-c",
            'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names'],input=query,text=True,capture_output=True,timeout=30)
        if result.returncode:raise RuntimeError("Disposable media SQL fixture failed; private output omitted")
        return result.stdout.strip()
    if args.phase=="prepare":
        user=int(client.request("/auth/me")["userId"])
        assert sql("SELECT DATABASE()") == "order_stock_prod"
        sql(f"UPDATE app_users SET user_role='ADMIN' WHERE id={user}")
        try:
            client.login(state["account"])
            png=base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
            boundary="media-"+uuid.uuid4().hex
            body=(f"--{boundary}\r\nContent-Disposition: form-data; name=\"revision\"\r\n\r\n0\r\n--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.png\"\r\nContent-Type: image/png\r\n\r\n".encode()+png+f"\r\n--{boundary}--\r\n".encode())
            token=client.request("/auth/csrf")
            request=urllib.request.Request(smoke.BASE_URL+f"/admin/products/{product['id']}/image",data=body,headers={token["headerName"]:token["token"],"Content-Type":"multipart/form-data; boundary="+boundary})
            with client.opener.open(request,timeout=15) as response:
                assert response.status==200;info=json.load(response)
            with client.opener.open(smoke.BASE_URL+info["imageUrl"],timeout=15) as response:
                image_hash=hashlib.sha256(response.read()).hexdigest()
            image_history=sql("CHECKSUM TABLE product_images,product_image_changes EXTENDED")
        finally:
            sql(f"UPDATE app_users SET user_role='USER' WHERE id={user}")
        client.login(state["account"])
        confirmed=state["confirmedRequest"]
        completed=client.request(f"/orders/cart/quote/{confirmed['body']['quoteId']}/intent",data={"key":confirmed["key"],"kind":"direct"},method="PUT")
        assert completed["orderId"]==state["orders"][1]["orderId"] and completed["kind"]=="cart"
        quote=client.request("/orders/cart/quote",data={"items":[{"productId":product["id"],"quantity":1}]})
        intent=client.request(f"/orders/cart/quote/{quote['quoteId']}/intent",data={"key":uuid.uuid4().hex,"kind":"direct"},method="PUT")
        state["recovery"]=client.request("/orders/cart/recovery")
        assert len(state["recovery"])==2 and intent in state["recovery"] and completed in state["recovery"]
        state["media"]={"imageUrl":info["imageUrl"],"hash":image_hash,"checksums":image_history,"intents":state["recovery"]}
        state["products"]=client.request("/products")
        # A real recovery must never resurrect a password-reset link consumed after the snapshot.
        sql(f"INSERT INTO password_resets(user_id,token_hash,auth_version,issued_at,expires_at,window_started_at,request_count) SELECT id,SHA2(UUID(),256),auth_version,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 15 MINUTE,UTC_TIMESTAMP(6),1 FROM app_users WHERE id={user}")
        assert sql('SELECT COUNT(*) FROM password_resets')=='1'
        # The media login replaced the smoke cookie; preserve the actual current session for restore checks.
        state["sessionId"]=client.session_id();state["csrf"]=client.request("/auth/csrf")
        args.state_file.write_text(json.dumps(state),encoding="utf-8");args.state_file.chmod(0o600)
        print("PASS: real admin upload and server checkout intent prepared for backup")
    else:
        media=state["media"]
        with client.opener.open(smoke.BASE_URL+media["imageUrl"],timeout=15) as response:
            assert response.status==200 and hashlib.sha256(response.read()).hexdigest()==media["hash"]
        assert sql("CHECKSUM TABLE product_images,product_image_changes EXTENDED")==media["checksums"]
        assert client.request("/orders/cart/recovery")==media["intents"]
        assert sql('SELECT COUNT(*) FROM password_resets')=='0'
        print("PASS: exact normalized image bytes, image revisions/audit and unsubmitted checkout intent restored")

if __name__=="__main__":main()
