import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import app as module


class FakeTV:
    def __init__(self):
        self.first = True
        self.closed = threading.Event()
        self.sent = []

    def recv(self):
        if self.first:
            self.first = False
            return json.dumps({'event': 'ms.channel.connect', 'data': {'token': 'test-token'}})
        self.closed.wait(2)
        return ''

    def send(self, message):
        self.sent.append(json.loads(message))

    def close(self):
        self.closed.set()


class RemoteTests(unittest.TestCase):
    def setUp(self):
        self.client = module.app.test_client()
        self.headers = {'X-Remote-Control': '1'}

    def test_rejects_external_or_loopback_targets(self):
        for host in ['8.8.8.8', '127.0.0.1', '169.254.169.254', 'localhost', '']:
            result = self.client.post('/api/connect', json={'host': host}, headers=self.headers)
            self.assertEqual(result.status_code, 400)

    def test_blocks_cross_origin_commands(self):
        self.assertEqual(self.client.post('/api/key', json={'key': 'KEY_HOME'}).status_code, 403)
        result = self.client.post('/api/key', json={'key': 'KEY_HOME'},
                                  headers={**self.headers, 'Origin': 'https://example.com'})
        self.assertEqual(result.status_code, 403)

    def test_invalid_keys_and_mac(self):
        for key in ['KEY_INVALID', [], None]:
            self.assertEqual(self.client.post('/api/key', json={'key': key}, headers=self.headers).status_code, 400)
        self.assertEqual(self.client.post('/api/wake', json={'mac': 'invalid'}, headers=self.headers).status_code, 400)

    def test_pairing_remembers_token_and_sends_real_protocol(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(module, 'CONFIG', Path(folder) / 'config.json'):
            tv = FakeTV()
            remote = module.Remote()
            with patch.object(module.websocket, 'create_connection', return_value=tv) as factory:
                remote.connect('192.168.0.50', 8002)
                deadline = time.monotonic() + 2
                while remote.state == 'connecting' and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertEqual(remote.state, 'connected')
                self.assertEqual(json.loads(module.CONFIG.read_text())['token'], 'test-token')
                remote.key('KEY_VOLUP')
                self.assertEqual(tv.sent[0]['method'], 'ms.remote.control')
                self.assertEqual(tv.sent[0]['params']['DataOfCmd'], 'KEY_VOLUP')
                self.assertTrue(factory.call_args.args[0].startswith('wss://192.168.0.50:8002/'))
                remote.close()
                remembered = module.Remote()
                self.assertEqual(remembered.config['token'], 'test-token')

    def test_disconnected_does_not_send(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(module, 'CONFIG', Path(folder) / 'config.json'):
            with self.assertRaises(ValueError):
                module.Remote().key('KEY_HOME')


if __name__ == '__main__':
    unittest.main()
