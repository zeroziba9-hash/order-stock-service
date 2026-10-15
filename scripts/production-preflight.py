"""Read-only production configuration checks. Never starts containers or prints secrets."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import subprocess
from datetime import datetime, timezone

def validate(path):
    if not path.is_file(): raise ValueError('Production environment file is missing')
    if os.name!='nt' and path.stat().st_mode & 0o077: raise ValueError('Use chmod 600 for the private environment file')
    values={}
    for line in path.read_text(encoding='utf-8-sig').splitlines():
        if not line.strip() or line.lstrip().startswith('#'):continue
        if '=' not in line:raise ValueError('Invalid environment file format')
        key,value=line.split('=',1);key=key.strip();value=value.strip()
        if key in values:raise ValueError('Duplicate environment variable')
        if len(value)>=2 and value[0]==value[-1] and value[0] in "\"'":value=value[1:-1]
        values[key]=value
    domain=values.get('APP_DOMAIN','')
    if (len(domain)>253 or not re.fullmatch(r'(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,63}',domain)
            or domain.lower().endswith('.localhost')):raise ValueError('APP_DOMAIN must be a bare DNS hostname')
    try:ipaddress.ip_address(domain)
    except ValueError:pass
    else:raise ValueError('Use a DNS hostname rather than an IP address')
    passwords=[values.get(key,'') for key in ('PROD_MYSQL_PASSWORD','PROD_MYSQL_ROOT_PASSWORD')]
    if any(not re.fullmatch(r'[A-Za-z0-9_-]{24,}',value) for value in passwords) or passwords[0]==passwords[1]:
        raise ValueError('Use two different random passwords of at least 24 letters/digits/underscore/hyphen')
    for secret in passwords:
        if any(word in secret.lower() for word in ('password','example','changeme','replace')):raise ValueError('Replace sample credentials')
    return domain

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--check-dns',action='store_true')
    args=parser.parse_args();domain=validate(args.env_file)
    root=Path(__file__).resolve().parents[1]
    process=subprocess.run(['docker','compose','--env-file',str(args.env_file.resolve()),'-f',str(root/'deploy/compose.prod.yaml'),'config','--format','json'],capture_output=True,text=True)
    if process.returncode:raise ValueError('Production Compose configuration could not be rendered')
    services=json.loads(process.stdout)['services']; checks=[]
    for name in ('app','db'):
        if services[name].get('ports'):raise ValueError('App and DB ports must remain internal')
        checks.append(name+'PortInternal')
    app=services['app'].get('environment',{})
    if app.get('SPRING_PROFILES_ACTIVE')!='prod' or app.get('APP_MOCK_PAYMENTS_ENABLED','false')!='false' or app.get('APP_MOCK_REFUNDS_ENABLED','false')!='false':raise ValueError('Production profile and mock write protection are required')
    checks.append('productionProfileAndMockProtection')
    if sorted(int(port['published']) for port in services['proxy'].get('ports',[]))!=[80,443]:raise ValueError('Proxy must publish HTTP and HTTPS only')
    checks.append('proxyPorts80And443')
    if args.check_dns:
        addresses={item[4][0] for item in socket.getaddrinfo(domain,443,type=socket.SOCK_STREAM)}
        if not addresses or any(not ipaddress.ip_address(address).is_global for address in addresses):raise ValueError('DNS must resolve to public server addresses')
        checks.append('publicDnsAddresses')
    report={'checkedAtUtc':datetime.now(timezone.utc).isoformat(),'domain':domain,'checks':checks,'passed':True,
            'publicDeploymentVerified':False,'externalBackupRestoreVerified':False,
            'note':'Configuration only. Run verified HTTPS checks and off-host backup recovery after deployment.'}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print('PASS: production configuration preflight; no deployment or backup recovery claim')

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,json.JSONDecodeError):raise SystemExit('FAIL: production preflight; check the private configuration and Docker without sharing credentials')
