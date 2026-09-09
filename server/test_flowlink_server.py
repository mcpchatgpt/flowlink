import base64, hashlib, hmac, tempfile, unittest
from pathlib import Path
from unittest.mock import Mock, patch
import flowlink_core
import flowlink_server
from flowlink_core import (FlowLinkError, app_update, effective_ports,
    migrate_config, publish_apk, token_hash, validate_public_key,
    wireguard_status)
class CoreTests(unittest.TestCase):
    def test_tls_handshake_is_deferred_to_worker_thread(self):
        raw_socket = Mock()
        secure_socket = Mock()
        tls_context = Mock()
        tls_context.wrap_socket.return_value = secure_socket
        server = flowlink_server.FlowLinkHTTPServer.__new__(
            flowlink_server.FlowLinkHTTPServer)
        server.tls_context = tls_context
        with patch.object(flowlink_server.ThreadingHTTPServer, "get_request",
                          return_value=(raw_socket, ("203.0.113.1", 12345))):
            result = server.get_request()
        raw_socket.settimeout.assert_called_once_with(10)
        tls_context.wrap_socket.assert_called_once_with(
            raw_socket, server_side=True, do_handshake_on_connect=False)
        self.assertEqual(result, (secure_socket, ("203.0.113.1", 12345)))

    def test_hmac_signature_is_deterministic(self):
        first = hmac.new(b"token", b"body", hashlib.sha256).hexdigest()
        second = hmac.new(b"token", b"body", hashlib.sha256).hexdigest()
        self.assertEqual(first, second)
        self.assertEqual(len(first), 64)
    def test_token_hash_does_not_store_raw_token(self):
        self.assertNotEqual(token_hash("secret"), "secret")
    def test_public_key_validation(self):
        key = base64.b64encode(bytes(range(32))).decode()
        self.assertEqual(validate_public_key(key), key)
        with self.assertRaises(FlowLinkError):
            validate_public_key("bad")
    def test_effective_ports_honors_grace(self):
        state = {"active_ports": [443, 51820],
                 "grace_ports": [{"port": 8443, "expires_at": 200},
                                 {"port": 2053, "expires_at": 99}]}
        self.assertEqual(effective_ports(state, 100), [443, 51820, 8443])
    def test_publish_apk_manifest(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source.apk"
            source.write_bytes(b"test-apk")
            with patch("flowlink_core.RELEASE_DIR", root), \
                 patch("flowlink_core.APK_PATH", root / "latest.apk"), \
                 patch("flowlink_core.UPDATE_PATH", root / "update.json"):
                result = publish_apk(source, 2, "0.2.0")
                self.assertEqual(result["version_code"], 2)
                self.assertEqual(result["size"], 8)
                self.assertTrue(app_update()["available"])
    def test_wireguard_dump_peer_columns(self):
        dump = ("private\tpublic\t51820\toff\n"
                "peer\t(none)\t1.2.3.4:51820\t10.77.0.2/32\t123\t456\t789\toff\n")
        completed = Mock(returncode=0, stdout=dump)
        with patch("flowlink_core.run", return_value=completed):
            status = wireguard_status({"interface": "flwg0"})
        self.assertEqual(status["latest_handshake"], 123)
        self.assertEqual(status["rx_bytes"], 456)
        self.assertEqual(status["tx_bytes"], 789)
    def test_migrate_config_expands_port_pool(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            config_path, state_path = root / "node.json", root / "state.json"
            config_path.write_text(
                '{"version":2,"ports":[443,2053,8443,51820],'
                '"stable_ports":[443,51820],"https_port":443,'
                '"rotating_port_count":2,"rotating_port_range":[20000,60000],'
                '"rotation_interval_seconds":86400,"port_grace_seconds":172800}',
                encoding="utf-8")
            patches = (
                patch.object(flowlink_core, "CONFIG_PATH", config_path),
                patch.object(flowlink_core, "STATE_PATH", state_path),
                patch.object(flowlink_core, "LOCK_PATH", root / "state.lock"),
                patch("flowlink_core.secrets.randbelow",
                      side_effect=range(100, 108)))
            with patches[0], patches[1], patches[2], patches[3]:
                result = migrate_config()
                config = flowlink_core.load_json(config_path)
                state = flowlink_core.load_json(state_path)
            self.assertTrue(result["changed"])
            self.assertEqual(config["stable_ports"], [443, 2053, 8443, 51820])
            self.assertEqual(config["rotating_port_count"], 8)
            self.assertEqual(config["rotation_interval_seconds"], 21600)
            self.assertEqual(len(state["active_ports"]), 12)
if __name__ == "__main__":
    unittest.main()
