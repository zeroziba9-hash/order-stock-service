"""Disposable HTTP contract fixture. Never use this provider outside browser CI."""
import base64
import json
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import RLock

payments={}
lock=RLock()
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass  # Do not emit request paths, bodies, keys or Authorization.
    def reply(self,status,body):
        data=json.dumps(body,ensure_ascii=False).encode()
        self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(data)
    def read(self):
        length=int(self.headers.get('Content-Length','0'))
        if length>16384:raise ValueError('Large fixture request')
        return json.loads(self.rfile.read(length) or b'{}')
    def do_PUT(self):
        if not self.path.startswith('/__fixtures/'):return self.reply(404,{})
        body=self.read();identity=self.path.rsplit('/',1)[1]
        with lock:
            state=payments.setdefault(identity,{'mode':'ok','confirms':0,'cancels':0})
            state.update({k:v for k,v in body.items() if k in {'mode','status'}})
        self.reply(200,{'ok':True})
    def do_GET(self):self.handle_payment()
    def do_POST(self):self.handle_payment()
    def handle_payment(self):
        with lock:
            if self.path.startswith('/__fixtures/'):
                state=payments.get(self.path.rsplit('/',1)[1],{})
                return self.reply(200,{key:state.get(key) for key in ('mode','status','confirms','cancels')})
            expected='Basic '+base64.b64encode(b'test_sk_browser_fixture:').decode()
            if self.headers.get('Authorization')!=expected:return self.reply(401,{})
            confirm=self.path=='/v1/payments/confirm';cancel=self.path.endswith('/cancel')
            body=self.read() if self.command=='POST' else {}
            identity=body.get('orderId') if confirm else self.path.split('/')[-2 if cancel else -1].removeprefix('pk_')
            state=payments.setdefault(identity,{'mode':'ok','confirms':0,'cancels':0})
            mode=state['mode']
            if confirm:
                state['confirms']+=1
                if mode=='decline':return self.reply(400,{'code':'REJECT_CARD_PAYMENT'})
                state.update(status='DONE',amount=body['amount'],paymentKey=body['paymentKey'])
            if cancel:
                state['cancels']+=1
                state['status']='CANCELED'
            if mode=='hidden':return self.reply(503,{})
            if not state.get('status'):return self.reply(404,{'code':'NOT_FOUND_PAYMENT'})
            stamp=datetime.now(timezone.utc).isoformat()
            self.reply(200,dict(orderId=identity,paymentKey=state['paymentKey'],currency='KRW',type='NORMAL',method='카드',
                totalAmount=state['amount'],balanceAmount=0 if state['status']=='CANCELED' else state['amount'],status=state['status'],approvedAt=stamp,
                cancels=[dict(transactionKey='tx_'+identity,cancelAmount=state['amount'],cancelStatus='DONE',canceledAt=stamp)]))

if __name__=='__main__':ThreadingHTTPServer(('0.0.0.0',8090),Handler).serve_forever()
