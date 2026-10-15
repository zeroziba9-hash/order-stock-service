"""Paired, repeated HTTP order benchmarks on a fresh disposable local stack only."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import importlib.util
import json
import os
import platform
import re
from pathlib import Path
import statistics
import subprocess
import sys
import urllib.parse
import uuid

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('load_orders',ROOT/'scripts/load-orders.py')
load=importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


def require_responses(rows, expected, stage, context, output):
    failures=[row for row in rows if row['status']!=expected]
    if not failures: return
    evidence={'stage':stage, **context, 'expectedStatus':expected,
              'statusCounts':dict(Counter(str(row['status']) for row in rows)), 'failures':[]}
    for row in failures[:20]:
        code=row.get('body',{}).get('code') if isinstance(row.get('body'),dict) else None
        trace=next((value for name,value in row.get('headers',{}).items() if name.lower()=='x-request-id'),None)
        evidence['failures'].append({'status':row['status'],
            'code':code if isinstance(code,str) and re.fullmatch(r'[A-Z][A-Z0-9_]{0,63}',code) else 'UNCLASSIFIED',
            'requestId':trace if isinstance(trace,str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',trace) else None})
    output.parent.mkdir(parents=True,exist_ok=True)
    output.with_name('benchmark-failure.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
    print('BENCHMARK_UNEXPECTED_RESPONSE '+json.dumps(evidence),flush=True)
    raise AssertionError('Unexpected response; refusing a performance claim')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',required=True)
    parser.add_argument('--env-file',type=Path,required=True)
    parser.add_argument('--base-url',default='http://127.0.0.1:18088')
    parser.add_argument('--requests',type=int,default=240)
    parser.add_argument('--warmup',type=int,default=64)
    parser.add_argument('--repeats',type=int,default=3)
    parser.add_argument('--concurrency',type=int,nargs='+',default=[1,8,32])
    parser.add_argument('--products',type=int,default=8)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    target=urllib.parse.urlsplit(args.base_url)
    if (os.environ.get('BENCHMARK_DISPOSABLE')!='true' or not args.project.startswith('order-stock-benchmark-')
            or target.scheme!='http' or target.hostname not in ('127.0.0.1','localhost') or target.port!=18088
            or target.username or target.password or target.path not in ('','/') or target.query or target.fragment
            or not args.env_file.is_file()):
        parser.error('Use a fresh order-stock-benchmark-* stack on loopback HTTP 18088 with BENCHMARK_DISPOSABLE=true')
    env_values=dict(line.split('=',1) for line in args.env_file.read_text().splitlines() if '=' in line and not line.startswith('#'))
    if env_values.get('APP_PORT')!='18088' or os.environ.get('APP_PORT','18088')!='18088':
        parser.error('The isolated environment file must select APP_PORT=18088')
    if (not 32<=args.requests<=2000 or not 32<=args.warmup<=1000 or not 3<=args.repeats<=10
            or not 2<=args.products<=16 or not args.concurrency or len(set(args.concurrency))!=len(args.concurrency)
            or any(c<1 or c>min(64,args.requests,args.warmup) for c in args.concurrency)):
        parser.error('Use 32..2000 requests, 32..1000 warmup, 3..10 repeats, 2..16 products and unique 1..64 workers')
    args.base_url=args.base_url.rstrip('/')
    assert load.database(args,'SELECT DATABASE();')=='order_stock'
    assert load.database(args,'SELECT COUNT(*) FROM app_users;')=='0','A fresh disposable DB is required; existing data is preserved'
    assert load.database(args,'SELECT COUNT(*) FROM purchase_orders;')=='0'
    # A failed rerun must not leave an older successful measurement in the chosen output.
    args.output.unlink(missing_ok=True)
    args.output.with_name('benchmark-failure.json').unlink(missing_ok=True)
    compose=['docker','compose','-p',args.project,'--env-file',str(args.env_file.resolve()),'-f',str(ROOT/'compose.yaml'),'-f',str(ROOT/'benchmark/compose.benchmark.yaml')]
    rows=[]
    try:
        for repeat in range(args.repeats):
            # Alternate mode order to reduce systematic first/second-run bias.
            for mode in (['locking','conditional'] if repeat%2==0 else ['conditional','locking']):
                process_env=dict(os.environ,BENCHMARK_ALLOCATION=mode)
                result=subprocess.run(compose+['up','--no-build','--no-deps','--wait','--wait-timeout','120','app'],env=process_env,capture_output=True,text=True,timeout=150)
                if result.returncode: raise RuntimeError('Isolated benchmark app restart failed')
                inspect=subprocess.run(['docker','inspect',f'{args.project}-app-1','--format','{{json .Config.Env}}'],capture_output=True,text=True,check=True)
                config=json.loads(inspect.stdout)
                assert f'APP_INVENTORY_ALLOCATION={mode}' in config,'Measured mode does not match app configuration'
                operator=load.Client(args.base_url)
                operator.csrf=operator.call('/auth/csrf')[1]
                email=f'benchmark-admin-{uuid.uuid4().hex}@example.test'
                account={'email':email,'password':uuid.uuid4().hex}
                assert operator.call('/auth/register',account)[0]==201
                role=subprocess.run([sys.executable,str(ROOT/'scripts/set-admin-role.py'),'--project',args.project,'--env-file',str(args.env_file.resolve()),'--email',email,'--role','ADMIN','--confirm-role-change'],capture_output=True,text=True,timeout=40)
                if role.returncode: raise RuntimeError('Disposable administrator fixture failed')
                assert operator.call('/auth/login',account,form=True)[0]==200
                operator.csrf=operator.call('/auth/csrf')[1]
                buyer=load.Client(args.base_url).login()
                for concurrency in args.concurrency:
                    for workload,product_count in [('hot',1),('distributed',args.products)]:
                        ids=[]
                        for i in range(product_count):
                            status,created,_=operator.call('/admin/products',{'name':f'benchmark-{repeat}-{mode}-{concurrency}-{workload}-{i}','price':1000},uuid.uuid4().hex)
                            assert status==201
                            product=created['productId']; ids.append(product)
                            assert operator.call(f'/admin/products/{product}/inventory-adjustments',{'delta':args.requests+args.warmup,'reason':'Disposable benchmark fixture'},uuid.uuid4().hex)[0]==201
                        clients=[buyer]*concurrency
                        def order(client,index):
                            return client.call('/orders',{'productId':ids[index%len(ids)],'quantity':1},uuid.uuid4().hex)
                        warm,_=load.batch(clients,args.warmup,order)
                        context={'repeat':repeat+1,'mode':mode,'workload':workload,'concurrency':concurrency}
                        require_responses(warm,201,'warmup',context,args.output)
                        measured,summary=load.measure(args,clients,args.requests,order)
                        require_responses(measured,201,'measured',context,args.output)
                        all_orders=warm+measured
                        assert len({row['body']['orderId'] for row in all_orders})==len(all_orders)
                        joined=','.join(map(str,ids))
                        count=load.database(args,f"SELECT COUNT(*) FROM inventory_movements WHERE product_id IN ({joined}) AND movement_type='ORDER';")
                        assert int(count)==args.requests+args.warmup
                        for product in ids:
                            h=operator.call(f'/admin/inventory-movements?productId={product}')[1]
                            expected=args.requests+args.warmup-sum(row['body']['items'][0]['productId']==product for row in all_orders)
                            assert h['consistent'] and h['currentStock']==expected
                        cancelled,_=load.batch(clients,len(all_orders),lambda client,index:client.call(f"/orders/{all_orders[index]['body']['orderId']}/cancel",{}))
                        require_responses(cancelled,200,'cancellation',context,args.output)
                        for product in ids:
                            h=operator.call(f'/admin/inventory-movements?productId={product}')[1]
                            assert h['consistent'] and h['currentStock']==args.requests+args.warmup
                        summary.update(repeat=repeat+1,mode=mode,workload=workload,products=product_count,warmup=args.warmup,allInvariantsPassed=True)
                        rows.append(summary)
                        print(f'PASS: repeat={repeat+1} mode={mode} workload={workload} workers={concurrency} p95={summary["p95Ms"]}ms',flush=True)
    finally:
        # Leave this disposable application on the default strategy even after an assertion fails.
        restored=subprocess.run(compose+['up','--no-build','--no-deps','--wait','--wait-timeout','120','app'],env=dict(os.environ,BENCHMARK_ALLOCATION='locking'),capture_output=True,text=True,timeout=150)
        if restored.returncode: raise RuntimeError('Could not restore the isolated benchmark app to locking')
    groups=[]
    for mode in ('locking','conditional'):
        for concurrency in args.concurrency:
            for workload in ('hot','distributed'):
                group=[r for r in rows if r['mode']==mode and r['concurrency']==concurrency and r['workload']==workload]
                groups.append({'mode':mode,'concurrency':concurrency,'workload':workload,'repeats':len(group),
                    'medianP95Ms':round(statistics.median(r['p95Ms'] for r in group),2),
                    'medianRequestsPerSecond':round(statistics.median(r['requestsPerSecond'] for r in group),2)})
    report={'format':'order-stock-paired-benchmark-v1','measuredAtUtc':datetime.now(timezone.utc).isoformat(),
        'method':'closed-loop, successful orders only; warmup/auth/fixture/cancellation/DB inspection excluded; alternating mode order',
        'limitations':'Local single-client short repeated runs; medians of run p95s are not a pooled p95 or production capacity. Conditional mode retains a locked read for catalog/ledger snapshots.',
        'commit':subprocess.run(['git','-c',f'safe.directory={ROOT.as_posix()}','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip(),
        'workingTreeDirty':bool(subprocess.run(['git','-c',f'safe.directory={ROOT.as_posix()}','status','--porcelain'],cwd=ROOT,capture_output=True,text=True,check=True).stdout.strip()),
        'python':platform.python_version(),'clientPlatform':platform.system(),
        'docker':json.loads(subprocess.run(['docker','info','--format','{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}'],capture_output=True,text=True,check=True).stdout),
        'imageId':subprocess.run(['docker','inspect',f'{args.project}-app-1','--format','{{.Image}}'],capture_output=True,text=True,check=True).stdout.strip(),
        'rounds':rows,'summaries':groups,'allInvariantsPassed':True,'defaultStrategy':'locking'}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(f'PASS: sanitized aggregate report: {args.output}')


if __name__=='__main__': main()
