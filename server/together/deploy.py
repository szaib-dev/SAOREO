"""Deploy only SAOREO's service and additive proxy/firewall rules. Password is stdin/getpass."""
import argparse
import getpass
import io
import pathlib
import sys
import tarfile
import time

import paramiko

SCRIPT = r"""set -eu
DEPLOY_DIR="$1"
id saoreo-together >/dev/null 2>&1 || useradd --system --home-dir /opt/saoreo-together --shell /usr/sbin/nologin saoreo-together
install -d -m 755 /opt/saoreo-together
tar -xf "$DEPLOY_DIR/source.tar" -C /opt/saoreo-together
python3 -m venv /opt/saoreo-together/venv
/opt/saoreo-together/venv/bin/pip install --quiet -r /opt/saoreo-together/requirements.txt
python3 - <<'PY'
import pathlib, secrets
p=pathlib.Path('/etc/saoreo-together.env')
if not p.exists():
    p.write_text('TOGETHER_ACCESS_KEY='+secrets.token_urlsafe(24)+'\nTOGETHER_TURN_SECRET='+secrets.token_hex(32)+'\nTOGETHER_TURN_HOST=193.181.223.127\n')
    p.chmod(0o600)
env=dict(x.split('=',1) for x in p.read_text().splitlines())
turn=pathlib.Path('/etc/saoreo-together-turn.conf')
turn.write_text('''listening-port=3478
listening-ip=193.181.223.127
relay-ip=193.181.223.127
external-ip=193.181.223.127
relay-threads=1
min-port=52000
max-port=52049
fingerprint
use-auth-secret
realm=saoreo-together
no-cli
no-tls
no-dtls
no-multicast-peers
no-tcp-relay
user-quota=4
total-quota=12
max-bps=1000000
bps-capacity=4000000
max-allocate-lifetime=3600
stale-nonce=600
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=224.0.0.0-255.255.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
log-file=stdout
simple-log
'''+'static-auth-secret='+env['TOGETHER_TURN_SECRET']+'\n')
turn.chmod(0o640)
import shutil
shutil.chown(turn, user='root', group='turnserver')
# Preserve the entire existing Caddy configuration, adding one distinct path handler.
caddy=pathlib.Path('/etc/caddy/Caddyfile')
s=caddy.read_text()
if 'handle /together/*' not in s:
    if 'szaibdev.duckdns.org:8765 {' not in s: raise RuntimeError('Expected site missing; refusing to edit proxy')
    backup=pathlib.Path('/etc/caddy/Caddyfile.before-saoreo-together')
    if not backup.exists(): shutil.copy2(caddy, backup)
    s=s.replace('szaibdev.duckdns.org:8765 {', 'szaibdev.duckdns.org:8765 {\n    handle /together/* {\n        reverse_proxy 127.0.0.1:8770\n    }',1)
    caddy.write_text(s)
PY
cat > /etc/systemd/system/saoreo-together.service <<'UNIT'
[Unit]
Description=SAOREO Together private room coordination
After=network-online.target
Wants=network-online.target
[Service]
User=saoreo-together
Group=saoreo-together
WorkingDirectory=/opt/saoreo-together
EnvironmentFile=/etc/saoreo-together.env
ExecStart=/opt/saoreo-together/venv/bin/python /opt/saoreo-together/service.py
Restart=on-failure
RestartSec=3
MemoryMax=160M
CPUQuota=25%
TasksMax=32
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
cat > /etc/systemd/system/saoreo-together-turn.service <<'UNIT'
[Unit]
Description=SAOREO Together authenticated call relay
After=network-online.target
Wants=network-online.target
[Service]
User=turnserver
Group=turnserver
ExecStart=/usr/bin/turnserver -c /etc/saoreo-together-turn.conf
Restart=on-failure
RestartSec=3
MemoryMax=128M
CPUQuota=35%
TasksMax=256
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT
# coturn was installed exclusively for SAOREO; the dedicated service uses bounded ports.
systemctl disable --now coturn
systemctl daemon-reload
systemctl enable --now saoreo-together saoreo-together-turn
systemctl restart saoreo-together saoreo-together-turn
caddy validate --config /etc/caddy/Caddyfile
systemctl reload caddy
ufw allow 3478/tcp comment 'SAOREO Together TURN'
ufw allow 3478/udp comment 'SAOREO Together TURN'
ufw allow 52000:52049/udp comment 'SAOREO Together call relay'
/opt/saoreo-together/venv/bin/python -m unittest discover -s /opt/saoreo-together -p 'test_*.py' -v
systemctl is-active saoreo-together saoreo-together-turn caddy
"""

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--known-hosts', required=True)
    parser.add_argument('--access-file', required=True)
    parser.add_argument('--password-stdin', action='store_true')
    args = parser.parse_args()
    password = sys.stdin.readline().strip() if args.password_stdin else getpass.getpass('SSH password: ')
    client = paramiko.SSHClient()
    client.load_host_keys(args.known_hosts)
    client.connect(args.host, username=args.user, password=password, look_for_keys=False, allow_agent=False, timeout=15)
    try:
        sftp = client.open_sftp()
        stage = sftp.normalize('.') + '/saoreo-together-upload-' + str(int(time.time()))
        sftp.mkdir(stage, mode=0o700)
        archive = io.BytesIO()
        with tarfile.open(fileobj=archive, mode='w') as tar:
            for name in ['service.py', 'relay.py', 'requirements.txt', 'test_service.py', 'test_relay.py']:
                tar.add(pathlib.Path(__file__).parent / name, arcname=name)
        with sftp.open(stage + '/source.tar', 'wb') as f: f.write(archive.getvalue())
        with sftp.open(stage + '/deploy.sh', 'w') as f: f.write(SCRIPT)
        stdin, stdout, stderr = client.exec_command(f"sudo -S -p '' sh '{stage}/deploy.sh' '{stage}'", timeout=300)
        stdin.write(password + '\n'); stdin.flush()
        print(stdout.read().decode('utf-8', 'replace'))
        print(stderr.read().decode('utf-8', 'replace'))
        status = stdout.channel.recv_exit_status()
        if status: raise RuntimeError(f'Deployment exited {status}')
        stdin, stdout, stderr = client.exec_command("sudo -S -p '' cat /etc/saoreo-together.env")
        stdin.write(password + '\n'); stdin.flush()
        env = dict(x.split('=', 1) for x in stdout.read().decode().splitlines())
        access = pathlib.Path(args.access_file)
        access.write_text('SAOREO Together private server\nServer: https://szaibdev.duckdns.org:8765\nRoom creator access key: ' + env['TOGETHER_ACCESS_KEY'] + '\n\nKeep this key private. Your partner only needs your invitation.\n', encoding='utf-8')
        print('Deployment checked. Private creator access key saved to the requested local access file.')
    finally:
        client.close()

if __name__ == '__main__':
    sys.stdout.reconfigure(encoding='utf-8')
    main()
