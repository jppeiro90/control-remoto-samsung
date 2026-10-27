"""Control local para televisores Samsung con API Tizen WebSocket."""
import base64
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import threading
import time
from urllib.parse import urlencode, urlsplit
from urllib.request import ProxyHandler, build_opener

from flask import Flask, jsonify, request, send_from_directory
import websocket

ROOT = Path(__file__).resolve().parent
CONFIG = ROOT / 'config.local.json'
app = Flask(__name__, static_folder='static')
app.config['MAX_CONTENT_LENGTH'] = 4096
KEYS = {'KEY_' + x for x in ['POWER', 'POWEROFF', 'HOME', 'SOURCE', 'MENU',
    'UP', 'DOWN', 'LEFT', 'RIGHT', 'ENTER', 'RETURN', 'EXIT', 'VOLUP', 'VOLDOWN',
    'MUTE', 'CHUP', 'CHDOWN', 'GUIDE', 'INFO', 'PLAY', 'PAUSE', 'STOP',
    'REWIND', 'FF', *map(str, range(10))]}


def private_ip(value):
    try:
        ip = ipaddress.IPv4Address(value)
        if not any(ip in ipaddress.ip_network(net) for net in
                   ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')):
            raise ValueError()
        return str(ip)
    except (ValueError, TypeError, ipaddress.AddressValueError):
        raise ValueError('Ingresá una IP de tu red local, por ejemplo 192.168.0.50.')


class Remote:
    def __init__(self):
        self.lock = threading.RLock()
        self.ws = None
        self.generation = 0
        self.state = 'disconnected'
        self.message = 'Conectá tu televisor para empezar.'
        try:
            self.config = json.loads(CONFIG.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            self.config = {}

    def save(self):
        temporary = CONFIG.with_suffix('.tmp')
        temporary.write_text(json.dumps(self.config, indent=2), encoding='utf-8')
        os.replace(temporary, CONFIG)

    def close(self):
        old, self.ws = self.ws, None
        if old:
            old.close()
        self.state = 'disconnected'

    def connect(self, host, port):
        with self.lock:
            if self.state == 'connecting':
                raise ValueError('Ya hay una conexión en curso. Aceptá el permiso en el TV.')
            self.close()
            self.generation += 1
            if host != self.config.get('host') or port != self.config.get('port'):
                self.config.pop('token', None)
            self.config.update(host=host, port=port)
            self.save()
            self.state = 'connecting'
            self.message = 'Mirá el TV y aceptá el permiso para Control Samsung. Puede tardar hasta 45 segundos.'
            threading.Thread(target=self._open, args=(self.generation, dict(self.config)), daemon=True).start()

    def _open(self, generation, config):
        ws = None
        try:
            params = {'name': base64.b64encode(b'Control Samsung').decode()}
            if config.get('token'):
                params['token'] = config['token']
            port = config['port']
            url = f"{'wss' if port == 8002 else 'ws'}://{config['host']}:{port}/api/v2/channels/samsung.remote.control?{urlencode(params)}"
            # Los TV usan certificados autofirmados. Se permite solo IP privada.
            ws = websocket.create_connection(url, timeout=5, sslopt={'cert_reqs': ssl.CERT_NONE},
                                              http_no_proxy=[config['host']], suppress_origin=True)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                try:
                    raw = ws.recv()
                except websocket.WebSocketTimeoutException:
                    continue
                if not raw:
                    raise ConnectionError('El TV cerró la conexión.')
                event = json.loads(raw)
                if event.get('event') == 'ms.channel.unauthorized':
                    raise PermissionError('El TV rechazó el permiso. Permití Control Samsung en el administrador de dispositivos del TV y volvé a conectar.')
                if event.get('event') == 'ms.channel.connect':
                    with self.lock:
                        if generation != self.generation:
                            return
                        token = event.get('data', {}).get('token')
                        if token:
                            self.config['token'] = token
                            self.save()
                        self.ws = ws
                        self.state = 'connected'
                        self.message = 'Conectado. Tu control está listo.'
                    break
            else:
                raise TimeoutError('No se recibió el permiso. Aceptá la solicitud en el TV y volvé a conectar.')
            # Consumir eventos mantiene el socket activo y detecta desconexiones.
            while self.ws is ws:
                try:
                    raw = ws.recv()
                    if not raw:
                        raise ConnectionError('El TV cerró la conexión. Volvé a conectar.')
                    event = json.loads(raw)
                    if event.get('event') == 'ms.error':
                        self.message = 'El TV informó que no pudo ejecutar el comando.'
                except websocket.WebSocketTimeoutException:
                    ws.ping()
        except Exception as exc:
            with self.lock:
                if generation != self.generation:
                    return
                self.ws = None
                self.state = 'disconnected'
                self.message = str(exc) if isinstance(exc, (PermissionError, TimeoutError, ConnectionError)) else 'No se pudo conectar. Revisá que el TV esté encendido, la IP sea correcta y ambos estén en la misma red. Si corresponde, probá el puerto 8001.'
        finally:
            if ws:
                ws.close()

    def key(self, key):
        with self.lock:
            if self.state != 'connected' or not self.ws:
                raise ValueError('Primero conectá el televisor.')
            try:
                self.ws.send(json.dumps({'method': 'ms.remote.control', 'params': {
                    'Cmd': 'Click', 'DataOfCmd': key, 'Option': 'false', 'TypeOfRemote': 'SendRemoteKey'}}))
            except Exception:
                self.close()
                self.message = 'Se perdió la conexión. Volvé a conectar el TV.'
                raise ValueError(self.message)


remote = Remote()


@app.before_request
def local_requests():
    # Un encabezado propio impide comandos desde formularios de otros sitios.
    if request.method == 'POST':
        if request.headers.get('X-Remote-Control') != '1':
            return jsonify(error='Solicitud no permitida.'), 403
        origin = request.headers.get('Origin')
        if origin and urlsplit(origin).netloc != request.host:
            return jsonify(error='Origen no permitido.'), 403


@app.errorhandler(ValueError)
def bad_input(error):
    return jsonify(error=str(error)), 400


@app.get('/')
def index():
    return send_from_directory(app.static_folder, 'index.html')


@app.get('/api/status')
def status():
    return jsonify(state=remote.state, message=remote.message,
                   host=remote.config.get('host', ''), port=remote.config.get('port', 8002))


@app.post('/api/connect')
def connect():
    data = request.get_json() or {}
    host = private_ip(data.get('host', ''))
    port = data.get('port', 8002)
    if port not in (8001, 8002):
        raise ValueError('El puerto debe ser 8001 u 8002.')
    remote.connect(host, port)
    return jsonify(ok=True), 202


@app.post('/api/key')
def key():
    key = (request.get_json() or {}).get('key')
    if not isinstance(key, str) or key not in KEYS:
        raise ValueError('Botón no válido.')
    remote.key(key)
    return jsonify(ok=True)


@app.post('/api/wake')
def wake():
    mac = (request.get_json() or {}).get('mac', '')
    if not isinstance(mac, str) or not re.fullmatch(r'(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}', mac):
        raise ValueError('Ingresá una dirección MAC válida, por ejemplo AA:BB:CC:DD:EE:FF.')
    packet = b'\xff' * 6 + bytes.fromhex(mac.replace(':', '').replace('-', '')) * 16
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.sendto(packet, ('255.255.255.255', 9))
    return jsonify(ok=True, message='Se envió la señal de encendido. Esperá unos segundos y conectá. Depende del soporte de encendido por red del TV.')


@app.post('/api/discover')
def discover():
    hosts = set()
    message = ('M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n'
               'MAN: "ssdp:discover"\r\nMX: 2\r\nST: ssdp:all\r\n\r\n')
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(.3)
        sock.sendto(message.encode(), ('239.255.255.250', 1900))
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                data, address = sock.recvfrom(8192)
                if b'samsung' in data.lower():
                    hosts.add(private_ip(address[0]))
            except socket.timeout:
                pass
            except ValueError:
                pass
    devices = []
    opener = build_opener(ProxyHandler({}))
    for host in sorted(hosts)[:12]:
        try:
            with opener.open(f'http://{host}:8001/api/v2/', timeout=1) as response:
                info = json.loads(response.read(65536))
            device = info.get('device', {})
            if info.get('type') == 'Samsung SmartTV' or device.get('type') == 'Samsung SmartTV':
                devices.append({'host': host, 'name': device.get('name', 'Samsung TV'), 'model': device.get('modelName', '')})
        except (OSError, ValueError):
            continue
    return jsonify(devices=devices)


if __name__ == '__main__':
    print('Control Samsung: http://localhost:8765 (desde el celular usá la IP Wi-Fi de esta PC).')
    app.run(host='0.0.0.0', port=8765, debug=False, threaded=True)
