#!/usr/bin/env python3
"""Local Supabase setup. Never prints passwords or puts them in process arguments."""
import argparse
import getpass
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / 'data/cloud/supabase.json'
JAR = ROOT / 'target/inventory-management-system-0.1.0-SNAPSHOT.jar'
PROJECT = 'pleggnxdtkyjoitrtyla'
HOST = 'aws-1-ap-south-1.pooler.supabase.com'

def java():
    candidate = shutil.which('java')
    for path in (Path('/private/tmp/inventory-runtime/zulu21.54.109-ca-jdk21.0.13.0.3-macosx_aarch64/Contents/Home/bin/java'),):
        if path.is_file(): return str(path)
    if candidate: return candidate
    raise SystemExit('Install Java 21 before continuing.')

def save(settings):
    CONFIG.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(CONFIG.parent, 0o700)
    fd = os.open(CONFIG, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as output: json.dump(settings, output)

def environment(settings):
    env = os.environ.copy()
    # Local application with a cloud database. Hosting origin is configured separately.
    for key in ('INVENTORY_ENV', 'INVENTORY_PUBLIC_ORIGIN', 'INVENTORY_BIND_ADDRESS'):
        env.pop(key, None)
    env.update(settings)
    return env

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=('configure', 'migrate', 'serve', 'check'))
    parser.add_argument('--port', type=int, default=8081)
    parser.add_argument('--demo-personas', action='store_true', help='Show existing fictional demo login cards for local testing')
    args = parser.parse_args()
    if not JAR.is_file(): raise SystemExit('Build the project with mvn package first.')
    if args.action == 'configure':
        if not sys.stdin.isatty(): raise SystemExit('Run configure in your own Terminal so password input stays private.')
        cert = ROOT / 'data/cloud/supabase-ca.crt'
        if not cert.is_file(): cert = Path('/etc/ssl/cert.pem')
        if not cert.is_file():
            cert = Path(input('Path to Supabase CA certificate: ').strip()).expanduser().resolve()
        if not cert.is_file(): raise SystemExit('Certificate file not found.')
        password = getpass.getpass('Supabase database password (hidden): ')
        if not password: raise SystemExit('Password is required.')
        from urllib.parse import urlencode
        query = urlencode({'sslmode': 'verify-full', 'sslrootcert': str(cert)})
        settings = {'INVENTORY_JDBC_URL': f'jdbc:postgresql://{HOST}:5432/postgres?{query}',
                    'INVENTORY_DB_USER': f'postgres.{PROJECT}', 'INVENTORY_DB_PASSWORD': password,
                    'INVENTORY_DB_POOL_SIZE': '3', 'INVENTORY_DB_SCHEMA': 'inventory'}
        # Verify TLS and credentials before saving or creating schema/data.
        result = subprocess.run([java(), '-cp', str(JAR), 'com.inventory.database.DatabaseCheck'],
                                cwd=ROOT, env=environment(settings))
        if result.returncode: raise SystemExit('Connection was not saved. Check password, network and CA certificate.')
        save(settings)
        print('Verified connection saved privately. No business data was copied.')
        return
    if not CONFIG.is_file(): raise SystemExit('First run: python3 scripts/supabase.py configure')
    settings = json.loads(CONFIG.read_text())
    if args.action == 'check':
        command = [java(), '-cp', str(JAR), 'com.inventory.database.DatabaseCheck']
    elif args.action == 'migrate':
        command = [java(), '-jar', str(JAR), '--migrate-h2=data/inventory']
    else:
        command = [java(), '-jar', str(JAR), '--serve', f'--port={args.port}']
        if args.demo_personas: command.append('--demo-personas')
    raise SystemExit(subprocess.run(command, cwd=ROOT, env=environment(settings)).returncode)

if __name__ == '__main__': main()
