"""Tests of the actual delivered HTML/CSS/JS in Chromium.
HTTP and WebSocket responses are fixtures ONLY in this test file.
No Spring, MySQL, broker or physical ESP8266 is tested by this suite.
Run from backend: python src/test/frontend/browser_test.py
Requires: pip install playwright; playwright install chromium
Set CHROMIUM_PATH to use an already installed Chromium executable.
"""
import asyncio
import copy
import json
import math
import os
from pathlib import Path
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import threading
import unittest
from urllib.parse import urlparse, parse_qs
from playwright.async_api import async_playwright, expect

FRONTEND = Path(__file__).resolve().parents[4] / "frontend"

class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_):
        pass

class BrowserTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), partial(QuietHandler, directory=str(FRONTEND)))
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = "http://localhost:" + str(cls.server.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    async def asyncSetUp(self):
        self.pw = await async_playwright().start()
        options = {"headless": True, "args": ["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH"):
            options["executable_path"] = os.environ["CHROMIUM_PATH"]
        self.browser = await self.pw.chromium.launch(**options)
        self.context = await self.browser.new_context(viewport={"width": 1440, "height": 1000}, timezone_id="America/New_York")
        self.page = await self.context.new_page()
        self.errors = []
        self.page.on("pageerror", lambda error: self.errors.append(str(error)))
        self.authenticated = True
        self.sockets = []
        self.connections = 0
        self.requests = []
        self.commands = []
        self.success_devices = set()
        self.post_delay = 0
        self.snapshot_delay = 0
        self.current = {"temperature": None, "humidity": None, "light": None, "time": None, "ids": {}}
        self.chart = {"temperature": [], "humidity": [], "light": []}
        self.devices = {i: {"deviceId": i, "status": "OFF", "updatedAt": None} for i in (1, 2)}
        self.sensor_rows = []
        for packet in range(1, 25):
            for sid, name, value in [(1, "Temperature", 28.5), (2, "Humidity", 70.0), (3, "Light", 123.0)]:
                self.sensor_rows.append({"id": (packet-1)*3+sid, "sensorId": sid, "sensorName": name,
                    "value": value, "createdAt": f"2026-10-16T10:{packet:02}:00"})
        self.sensor_rows.reverse()
        self.action_rows = [{"id": i, "deviceName": "Light 1" if i%2 else "Light 2",
            "action": "ON" if i%3 else "OFF", "status": "LOADING" if i%4==0 else ("ON" if i%3 else "OFF"),
            "userId": 1, "username": "admin", "createdAt": f"2026-10-16T11:{i:02}:00"} for i in range(40, 0, -1)]
        await self.page.route("**/api/**", self.api)
        await self.page.route_web_socket("**/ws", self.open_socket)

    async def asyncTearDown(self):
        await self.context.close()
        await self.browser.close()
        await self.pw.stop()
        self.assertEqual([], self.errors, "Uncaught browser JavaScript errors")

    async def open_socket(self, ws):
        self.connections += 1
        self.sockets.append(ws)
        ws.on_close(lambda code, reason: self.sockets.remove(ws) if ws in self.sockets else None)

    async def emit(self, kind, data):
        for ws in list(self.sockets):
            await ws.send(json.dumps({"type": kind, "data": data}))

    def packet(self, n, same_time=False):
        time = "2026-10-16T10:00:00" if same_time else f"2026-10-16T10:{n//30:02}:{(n*2)%60:02}"
        self.current = {"temperature": round(25+n/10, 1), "humidity": 60.0, "light": float(100+n), "time": time,
            "ids": {"temperature": n*3-2, "humidity": n*3-1, "light": n*3}}
        for key in self.chart:
            self.chart[key].append({"id": self.current["ids"][key], "time": time, "value": self.current[key]})
            self.chart[key] = self.chart[key][-20:]
        return copy.deepcopy(self.current)

    async def api(self, route):
        request = route.request
        url = urlparse(request.url)
        path = url.path
        q = {k: v[0] for k, v in parse_qs(url.query).items()}
        self.requests.append((request.method, path, q))
        if path == "/api/auth/csrf":
            return await route.fulfill(json={"headerName": "X-CSRF-TOKEN", "token": "test-token"})
        if request.method == "POST" and request.headers.get("x-csrf-token") != "test-token":
            return await route.fulfill(status=403, json={"message": "Invalid CSRF"})
        if path == "/api/auth/login":
            body = request.post_data_json
            if body != {"username": "admin", "password": "123456"}:
                return await route.fulfill(status=401, json={"message": "Incorrect username or password."})
            self.authenticated = True
            return await route.fulfill(json={"id": 1, "username": "admin", "fullName": "Administrator"})
        if not self.authenticated:
            return await route.fulfill(status=401, json={"message": "Unauthorized"})
        if path == "/api/auth/me":
            return await route.fulfill(json={"id": 1, "username": "admin", "fullName": "Administrator"})
        if path == "/api/auth/logout":
            self.authenticated = False
            return await route.fulfill(status=204)
        if path == "/api/sensor-data/latest":
            snapshot = copy.deepcopy(self.current)
            await asyncio.sleep(self.snapshot_delay)
            return await route.fulfill(json=snapshot)
        if path == "/api/sensor-data/chart":
            snapshot = copy.deepcopy(self.chart)
            await asyncio.sleep(self.snapshot_delay)
            return await route.fulfill(json=snapshot)
        if path.endswith("/control"):
            body = request.post_data_json
            self.assertEqual(set(body), {"action"})
            device_id = int(path.split("/")[3])
            self.commands.append((device_id, body["action"]))
            if device_id in self.success_devices:
                self.devices[device_id] = {"deviceId": device_id, "status": body["action"],
                    "updatedAt": f"2026-10-16T12:00:{len(self.commands):02}"}
                await self.emit("device:status", self.devices[device_id])
            await asyncio.sleep(self.post_delay)
            return await route.fulfill(json={"id": len(self.commands), "status": "LOADING", "timeoutSeconds": 5})
        if path.endswith("/status"):
            snapshot = copy.deepcopy(self.devices[int(path.split("/")[3])])
            await asyncio.sleep(self.snapshot_delay)
            return await route.fulfill(json=snapshot)
        if path == "/api/sensor-data/history":
            rows = [r for r in self.sensor_rows if
                (not q.get("sensorId") or r["sensorId"] == int(q["sensorId"])) and
                (not q.get("value") or r["value"] == float(q["value"])) and
                (not q.get("time") or r["createdAt"].startswith(q["time"].replace(" ", "T")))]
            return await route.fulfill(json=self.paginate(rows, q))
        if path == "/api/devices/action-history":
            rows = [r for r in self.action_rows if
                (not q.get("deviceId") or r["deviceName"] == "Light " + q["deviceId"]) and
                (not q.get("action") or r["action"] == q["action"]) and
                (not q.get("status") or r["status"] == q["status"]) and
                (not q.get("time") or r["createdAt"].startswith(q["time"].replace(" ", "T")))]
            return await route.fulfill(json=self.paginate(rows, q))
        await route.fulfill(status=404, json={"message": path})

    def paginate(self, rows, q):
        page, size = int(q.get("page", 0)), int(q.get("size", 10))
        return {"content": rows[page*size:(page+1)*size], "totalElements": len(rows),
                "totalPages": math.ceil(len(rows)/size), "page": page, "size": size}

    async def dashboard(self):
        await self.page.goto(self.origin + "/html/dashboard.html")
        await self.page.wait_for_function("lightStates.get(1)?.ready && lightStates.get(2)?.ready")
        await self.page.wait_for_function("socket && socket.readyState === WebSocket.OPEN")
        await asyncio.sleep(0.05)

    async def test_login_validation_csrf_logout_and_login_again(self):
        self.authenticated = False
        await self.page.goto(self.origin + "/html/dashboard.html")
        await self.page.wait_for_url("**/html/login.html")
        await self.page.locator("#login-btn").click()
        await expect(self.page.locator("#login-error")).to_contain_text("Please enter both")
        await self.page.locator("#username").fill("admin")
        await self.page.locator("#password").fill("wrong")
        await self.page.locator("#toggle-password").click()
        await expect(self.page.locator("#password")).to_have_attribute("type", "text")
        await self.page.locator("#login-btn").click()
        await expect(self.page.locator("#login-error")).to_contain_text("Incorrect username")
        await self.page.locator("#password").fill("123456")
        await self.page.locator("#login-btn").click()
        await self.page.wait_for_url("**/html/dashboard.html")
        await self.page.locator("#user-chip").click()
        await self.page.locator("#logout-btn").click()
        await self.page.wait_for_url("**/html/login.html")
        await self.page.locator("#username").fill("admin")
        await self.page.locator("#password").fill("123456")
        await self.page.locator("#login-btn").click()
        await self.page.wait_for_url("**/html/dashboard.html")
        posts = len([r for r in self.requests if r[0] == "POST"])
        tokens = len([r for r in self.requests if r[1] == "/api/auth/csrf"])
        self.assertEqual(posts, tokens)
        self.assertEqual(await self.page.evaluate("localStorage.length"), 0)

    async def test_empty_dashboard_and_no_fake_updates(self):
        await self.dashboard()
        await expect(self.page.locator("#temp-value")).to_have_text("--")
        await expect(self.page.locator("#light-1-card .off-btn")).to_be_disabled()
        await expect(self.page.locator("#light-2-card .on-btn")).to_be_enabled()
        await expect(self.page.locator("#light-1-card .light-updated")).to_have_text("Last updated: --")
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 0)
        await asyncio.sleep(0.3)
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 0)

    async def test_chart_twenty_distinct_samples_and_connection_recovery(self):
        await self.dashboard()
        for n in range(1, 27):
            await self.emit("sensor:update", self.packet(n))
        await self.page.wait_for_function("chart.data.labels.length === 20")
        await expect(self.page.locator("#temp-value")).to_have_text("27.6")
        self.assertEqual(await self.page.evaluate("chart.data.datasets[0].data[0]"), 25.7)
        self.assertEqual(await self.page.locator("#temp-updated").inner_text(), "Updated 10:00:52")
        old = await self.page.evaluate("JSON.stringify(chart.data)")
        await asyncio.sleep(0.2)
        self.assertEqual(old, await self.page.evaluate("JSON.stringify(chart.data)"))
        await self.sockets[0].close()
        await asyncio.sleep(2.3)
        self.assertEqual(len(self.sockets), 1)
        self.assertGreaterEqual(self.connections, 2)
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 20)
        await self.emit("sensor:update", self.packet(27))
        await expect(self.page.locator("#temp-value")).to_have_text("27.7")
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 20)

    async def test_distinct_rows_same_second_and_duplicate_event_are_not_confused(self):
        await self.dashboard()
        for n in range(1, 23):
            await self.emit("sensor:update", self.packet(n, same_time=True))
        await self.emit("sensor:update", self.current)
        await self.page.wait_for_function("chart.data.labels.length === 20")
        self.assertEqual(await self.page.evaluate("chart.data.datasets[0].data[0]"), 25.3)
        await expect(self.page.locator("#temp-value")).to_have_text("27.2")
        await self.page.evaluate("refreshSnapshot()")
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 20)

    async def test_fast_ack_before_http_response_then_reverse(self):
        self.success_devices = {1, 2}
        self.post_delay = 0.4
        await self.dashboard()
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await expect(self.page.locator("#light-1-card .off-btn")).to_be_enabled()
        await asyncio.sleep(0.5)
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await self.page.locator("#light-1-card .off-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("OFF")
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("ON")
        await asyncio.sleep(0.5)
        self.assertEqual(self.commands, [(1, "ON"), (1, "OFF"), (2, "ON")])

    async def test_one_loading_other_success_timeout_retry_and_old_timer(self):
        self.success_devices = {2}
        await self.dashboard()
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("LOADING")
        await expect(self.page.locator("#light-1-card .on-btn")).to_be_disabled()
        await expect(self.page.locator("#light-1-card .off-btn")).to_be_disabled()
        await expect(self.page.locator("#light-2-card .on-btn")).to_be_enabled()
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("ON")
        await self.emit("device:timeout", self.devices[1])
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("OFF")
        await expect(self.page.locator("#light-1-card .light-updated")).to_have_text("Last updated: --")
        self.success_devices.add(1)
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await asyncio.sleep(5.1)
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        self.assertEqual(self.commands, [(1,"ON"), (2,"ON"), (1,"ON")])

    async def test_both_time_out_with_silent_fallback_when_no_ws_event(self):
        await self.dashboard()
        await self.page.locator("#light-1-card .on-btn").click()
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("LOADING")
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("OFF", timeout=6500)
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("OFF", timeout=6500)
        await expect(self.page.locator("#light-1-card .on-btn")).to_be_enabled()
        await expect(self.page.locator("#light-2-card .on-btn")).to_be_enabled()

    async def test_late_rest_snapshot_cannot_replace_ws(self):
        self.packet(1)
        await self.dashboard()
        self.snapshot_delay = 0.4
        # Start a request, then deliver an event newer than the in-flight REST snapshot.
        await self.page.evaluate("void refreshSnapshot()")
        await asyncio.sleep(0.08)
        await self.emit("sensor:update", self.packet(2))
        self.devices[1] = {"deviceId": 1, "status": "ON", "updatedAt": "2026-10-16T12:00:05"}
        await self.emit("device:status", self.devices[1])
        await asyncio.sleep(0.5)
        await expect(self.page.locator("#temp-value")).to_have_text("25.2")
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        self.assertEqual(await self.page.evaluate("chart.data.labels.length"), 2)
        self.assertEqual(await self.page.evaluate("chart.data.datasets[0].data[1]"), 25.2)

    async def test_sensor_filters_drafts_pages_reset_and_validation(self):
        await self.page.goto(self.origin + "/html/sensor-history.html")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        self.assertEqual(await self.page.locator("#table-body tr").count(), 10)
        self.assertEqual(await self.page.locator("#pagination button[aria-label^='Page ']").count(), 5)
        await self.page.locator("#f-filter-by").select_option("Temperature")
        await self.page.locator("#f-query").fill("+28.5")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 24 records")
        self.assertEqual(self.requests[-1][2].get("sensorId"), "1")
        self.assertEqual(self.requests[-1][2].get("value"), "+28.5")
        await self.page.locator("#f-query").fill("bogus draft")
        await self.page.locator("#rows-per-page").select_option("5")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 24 records")
        await expect(self.page.locator("#table-body tr")).to_have_count(5)
        await self.page.get_by_role("button", name="Next page", exact=True).click()
        await expect(self.page.locator("#summary-text")).to_contain_text("6")
        before = len(self.requests)
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
        self.assertEqual(len(self.requests), before)
        await self.page.locator("#reset-btn").click()
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        await expect(self.page.locator("#rows-per-page")).to_have_value("5")
        await self.page.locator("#f-filter-by").select_option("time")
        for bad in ["2023-02-29", "1900-02-29", "2026-2", "2026-01-01 24:00"]:
            before=len(self.requests)
            await self.page.locator("#f-query").fill(bad)
            await self.page.locator("#f-query").press("Enter")
            await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
            self.assertEqual(before,len(self.requests))
        await self.page.locator("#f-query").fill("2024-02-29")
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#summary-text")).to_have_text("Showing 0 records")
        await expect(self.page.locator("#pagination button")).to_have_count(0)
        await self.page.locator("#reset-btn").click()
        await self.page.locator("#rows-per-page").select_option("15")
        await expect(self.page.locator("#table-body tr")).to_have_count(15)
        await expect(self.page.locator(".table-wrap")).to_have_class("table-wrap is-scrollable")

    async def test_device_history_combines_filters_and_profile_is_static(self):
        await self.page.goto(self.origin + "/html/device-history.html")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 40 records")
        await self.page.locator("#f-device").select_option("Light 2")
        await self.page.locator("#f-action").select_option("ON")
        await self.page.locator("#f-status").select_option("LOADING")
        await self.page.locator("#f-date").fill("2026-10")
        await self.page.locator("#f-date").press("Enter")
        await expect(self.page.locator("#table-body tr")).to_have_count(7)
        for row in await self.page.locator("#table-body tr").all_text_contents():
            self.assertIn("Light 2",row); self.assertIn("LOADING",row); self.assertIn("admin",row)
        await self.page.locator("#f-date").fill("2026-02-30")
        await self.page.locator("#f-date").press("Enter")
        await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
        await expect(self.page.locator("#table-body tr")).to_have_count(7)
        await self.page.goto(self.origin + "/html/profile.html")
        await expect(self.page.locator("#profile-name")).to_have_text("Nguy\u1ec5n Trung Ki\u00ean")
        await expect(self.page.locator(".profile-role")).to_have_text("B23DCCN464")
        self.assertFalse(any("/profile" in path for method,path,q in self.requests))

    async def test_session_expiry_is_not_an_empty_table_or_five_second_wait(self):
        await self.dashboard()
        self.authenticated=False
        await self.page.locator("#light-1-card .on-btn").click()
        await self.page.wait_for_url("**/html/login.html", timeout=1500)
        self.assertEqual(self.commands,[])

    async def test_returning_dashboard_uses_db_snapshot_and_one_socket(self):
        self.success_devices={1}
        await self.dashboard()
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await self.page.goto(self.origin + "/html/sensor-history.html")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        await self.dashboard()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        self.assertEqual(len(self.sockets),1)
        self.assertEqual(await self.page.locator("#light-1-card .light-updated").inner_text(),"Last updated: 12:00:01")
        if os.environ.get("SCREENSHOT_DIR"):
            folder=Path(os.environ["SCREENSHOT_DIR"]);folder.mkdir(parents=True,exist_ok=True)
            await self.emit("sensor:update",self.packet(1))
            await self.page.screenshot(path=str(folder/"dashboard.png"),full_page=True)

if __name__ == "__main__":
    unittest.main(verbosity=2)
