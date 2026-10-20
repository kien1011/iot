"""Offline Chromium DOM tests: actual frontend sources, injected boundary doubles.
No navigation, network access, Spring, broker, database or physical hardware is used.
This also runs in environments where browser URL navigation is administratively disabled.
Requires playwright and Chromium. Set CHROMIUM_PATH for an installed binary.
"""
import asyncio
import json
import os
from pathlib import Path
import re
import unittest
from playwright.async_api import async_playwright, expect

HERE = Path(__file__).resolve().parent
FRONTEND = HERE.parents[3] / "frontend"

class OfflineDomTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pw=await async_playwright().start()
        options={"headless":True,"args":["--no-sandbox"]}
        if os.environ.get("CHROMIUM_PATH"):options["executable_path"]=os.environ["CHROMIUM_PATH"]
        self.browser=await self.pw.chromium.launch(**options)
        self.page=await self.browser.new_page(viewport={"width":1440,"height":1000},timezone_id="America/New_York")
        self.errors=[]
        self.page.on("pageerror",lambda error:self.errors.append(str(error)))

    async def asyncTearDown(self):
        await self.browser.close();await self.pw.stop()
        self.assertEqual(self.errors,[])

    async def load(self,name,setup=""):
        html=(FRONTEND/"html"/(name+".html")).read_text()
        def inline_css(match):
            return "<style>"+(FRONTEND/"html"/match.group(1)).resolve().read_text()+"</style>"
        html=re.sub(r'<link rel="stylesheet" href="([^"]+)"\s*/?>',inline_css,html)
        html=re.sub(r'<script\b[^>]*>.*?</script>',"",html,flags=re.S)
        await self.page.set_content(html)
        await self.page.add_script_tag(content=(HERE/"offline_harness.js").read_text())
        if setup:await self.page.evaluate(setup)
        if name=="dashboard":await self.page.add_script_tag(content=(FRONTEND/"vendor/chart.umd.min.js").read_text())
        names=["api"]+(["app"] if name!="login" else [])+(["history"] if "history" in name else [])
        if name!="profile":names.append(name)
        source="\n".join((FRONTEND/"js"/(n+".js")).read_text() for n in names)
        hooks="{get chart(){return chart}, refreshSnapshot}" if name=="dashboard" else "{}"
        code="new Function('window','location','fetch','WebSocket',"+json.dumps(source+"\nglobalThis.H="+hooks+";")+ ")(T.window,T.location,T.fetch,T.WebSocket);document.dispatchEvent(new Event('DOMContentLoaded'));"
        await self.page.evaluate(code)
        await asyncio.sleep(0.05)

    async def test_empty_state_and_twenty_points_same_second_dedup(self):
        await self.load("dashboard")
        await expect(self.page.locator("#temp-value")).to_have_text("--")
        self.assertEqual(await self.page.evaluate("H.chart.data.labels.length"),0)
        await self.page.evaluate("for(let n=1;n<=25;n++)T.packet(n,true);T.emit('sensor:update',T.current)")
        self.assertEqual(await self.page.evaluate("H.chart.data.labels.length"),20)
        self.assertEqual(await self.page.evaluate("H.chart.data.datasets[0].data[0]"),25.6)
        await self.page.evaluate("H.refreshSnapshot()")
        self.assertEqual(await self.page.evaluate("H.chart.data.labels.length"),20)
        await expect(self.page.locator("#temp-value")).to_have_text("27.5")
        await expect(self.page.locator("#temp-updated")).to_have_text("Updated 10:00:00")
        before=await self.page.evaluate("JSON.stringify(H.chart.data)")
        await asyncio.sleep(.2)
        self.assertEqual(before,await self.page.evaluate("JSON.stringify(H.chart.data)"))
        if os.environ.get("SCREENSHOT_DIR"):
            p=Path(os.environ["SCREENSHOT_DIR"]);p.mkdir(parents=True,exist_ok=True)
            await self.page.screenshot(path=str(p/"dashboard.png"),full_page=True)

    async def test_immediate_ack_beats_http_and_reverse_is_allowed(self):
        await self.load("dashboard","T.successDevices=[1,2];T.postDelay=350")
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await expect(self.page.locator("#light-1-card .off-btn")).to_be_enabled()
        await asyncio.sleep(.4)
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await self.page.locator("#light-1-card .off-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("OFF")
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("ON")
        await asyncio.sleep(.4)
        self.assertEqual(await self.page.evaluate("T.commands.length"),3)
        self.assertEqual(await self.page.evaluate("Object.keys(T.requests.find(r=>r.path.endsWith('/control')).body)"),["action"])

    async def test_led_independence_timeout_retry_and_no_old_timer_effect(self):
        await self.load("dashboard","T.successDevices=[2]")
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("LOADING")
        await expect(self.page.locator("#light-1-card .on-btn")).to_be_disabled()
        await expect(self.page.locator("#light-1-card .off-btn")).to_be_disabled()
        await expect(self.page.locator("#light-2-card .on-btn")).to_be_enabled()
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("ON")
        await self.page.evaluate("T.emit('device:timeout',T.devices[1]);T.successDevices.push(1)")
        await expect(self.page.locator("#light-1-card .light-updated")).to_have_text("Last updated: --")
        await self.page.locator("#light-1-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        await asyncio.sleep(5.1)
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")

    async def test_both_timeout_silent_fallback(self):
        await self.load("dashboard")
        await self.page.locator("#light-1-card .on-btn").click()
        await self.page.locator("#light-2-card .on-btn").click()
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("LOADING")
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("LOADING")
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("OFF",timeout=6500)
        await expect(self.page.locator("#light-2-card .status-text")).to_have_text("OFF",timeout=6500)
        await expect(self.page.locator("#light-1-card .on-btn")).to_be_enabled()
        await expect(self.page.locator("#light-2-card .on-btn")).to_be_enabled()

    async def test_stale_rest_and_reconnect_snapshot(self):
        await self.load("dashboard","T.packet(1)")
        await self.page.evaluate("T.snapshotDelay=350;void H.refreshSnapshot()")
        await asyncio.sleep(.05)
        await self.page.evaluate("T.packet(2);T.devices[1]={deviceId:1,status:'ON',updatedAt:'2026-10-16T12:00:05'};T.emit('device:status',T.devices[1])")
        await asyncio.sleep(.4)
        await expect(self.page.locator("#temp-value")).to_have_text("25.2")
        await expect(self.page.locator("#light-1-card .status-text")).to_have_text("ON")
        self.assertEqual(await self.page.evaluate("H.chart.data.labels.length"),2)
        await self.page.evaluate("T.snapshotDelay=0;T.sockets[0].close()")
        await asyncio.sleep(2.2)
        self.assertEqual(await self.page.evaluate("T.sockets.length"),1)
        self.assertEqual(await self.page.evaluate("T.connections"),2)
        self.assertEqual(await self.page.evaluate("H.chart.data.labels.length"),2)

    async def test_sensor_search_draft_pagination_reset_and_invalid_dates(self):
        await self.load("sensor-history")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        self.assertEqual(await self.page.locator("#table-body tr").count(),10)
        self.assertEqual(await self.page.locator("#pagination button[aria-label^='Page ']").count(),5)
        await self.page.locator("#f-filter-by").select_option("Temperature")
        await self.page.locator("#f-query").fill("+28.5")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 24 records")
        self.assertEqual(await self.page.evaluate("T.requests.at(-1).query.value"),"+28.5")
        await self.page.locator("#f-query").fill("bad draft")
        await self.page.locator("#rows-per-page").select_option("5")
        await expect(self.page.locator("#table-body tr")).to_have_count(5)
        await expect(self.page.locator("#summary-text")).to_contain_text("of 24 records")
        await self.page.get_by_role("button",name="Next page",exact=True).click()
        self.assertEqual(await self.page.evaluate("T.requests.at(-1).query.page"),"1")
        before=await self.page.evaluate("T.requests.length")
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
        self.assertEqual(await self.page.evaluate("T.requests.length"),before)
        await self.page.locator("#reset-btn").click()
        await expect(self.page.locator("#rows-per-page")).to_have_value("5")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 72 records")
        await self.page.locator("#f-filter-by").select_option("time")
        for value in ["2023-02-29","1900-02-29","2026-2","2026-01-01 24:00"]:
            before=await self.page.evaluate("T.requests.length")
            await self.page.locator("#f-query").fill(value)
            await self.page.locator("#f-query").press("Enter")
            await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
            self.assertEqual(await self.page.evaluate("T.requests.length"),before)
        await self.page.locator("#f-query").fill("2024-02-29")
        await self.page.locator("#f-query").press("Enter")
        await expect(self.page.locator("#summary-text")).to_have_text("Showing 0 records")
        await expect(self.page.locator("#pagination button")).to_have_count(0)
        await self.page.locator("#reset-btn").click()
        await self.page.locator("#rows-per-page").select_option("15")
        await expect(self.page.locator("#table-body tr")).to_have_count(15)
        await expect(self.page.locator(".table-wrap")).to_have_class("table-wrap is-scrollable")

    async def test_device_filters_and_no_results(self):
        await self.load("device-history")
        await expect(self.page.locator("#summary-text")).to_contain_text("of 40 records")
        await self.page.locator("#f-device").select_option("Light 2")
        await self.page.locator("#f-action").select_option("ON")
        await self.page.locator("#f-status").select_option("LOADING")
        await self.page.locator("#f-date").fill("2026-10")
        await self.page.locator("#f-date").press("Enter")
        await expect(self.page.locator("#table-body tr")).to_have_count(7)
        self.assertEqual(await self.page.evaluate("T.requests.at(-1).query.deviceId"),"2")
        await self.page.locator("#f-date").fill("2026-02-30")
        await self.page.locator("#f-date").press("Enter")
        await expect(self.page.locator("#filter-error")).to_contain_text("Invalid input")
        await expect(self.page.locator("#table-body tr")).to_have_count(7)
        await self.page.locator("#f-date").fill("2025")
        await self.page.locator("#f-date").press("Enter")
        await expect(self.page.locator("#summary-text")).to_have_text("Showing 0 records")

    async def test_login_input_csrf_success_and_password_not_stored(self):
        await self.load("login","T.authenticated=false")
        await self.page.locator("#login-btn").click()
        await expect(self.page.locator("#login-error")).to_contain_text("Please enter both")
        await self.page.locator("#username").fill("admin")
        await self.page.locator("#password").fill("wrong")
        await self.page.locator("#toggle-password").click()
        await expect(self.page.locator("#password")).to_have_attribute("type","text")
        await self.page.locator("#login-btn").click()
        await expect(self.page.locator("#login-error")).to_contain_text("Incorrect username")
        await self.page.locator("#password").fill("123456")
        await self.page.locator("#login-btn").click()
        await self.page.wait_for_function("T.navigations.at(-1) === 'dashboard.html'")
        self.assertEqual(await self.page.evaluate("T.navigations.at(-1)"),"dashboard.html")
        self.assertEqual(await self.page.evaluate("T.requests.filter(r=>r.path==='/api/auth/csrf').length"),2)
        self.assertEqual(await self.page.evaluate("T.requests.filter(r=>r.path==='/api/auth/me').length"),1)

    async def test_session_expiry_and_logout_204(self):
        await self.load("dashboard")
        await self.page.evaluate("T.authenticated=false")
        await self.page.locator("#light-1-card .on-btn").click()
        await self.page.wait_for_function("T.navigations.at(-1) === '/html/login.html'")
        self.assertEqual(await self.page.evaluate("T.navigations.at(-1)"),"/html/login.html")
        self.assertEqual(await self.page.evaluate("T.commands.length"),0)
        await self.page.evaluate("T.authenticated=true")
        await self.page.locator("#user-chip").click()
        await self.page.locator("#logout-btn").click()
        await self.page.wait_for_function("T.navigations.at(-1) === 'login.html'")
        self.assertEqual(await self.page.evaluate("T.navigations.at(-1)"),"login.html")
        self.assertFalse(await self.page.evaluate("T.authenticated"))

    async def test_static_profile_identity(self):
        await self.load("profile")
        await expect(self.page.locator("#profile-name")).to_have_text("Nguy\u1ec5n Trung Ki\u00ean")
        await expect(self.page.locator(".profile-role")).to_have_text("B23DCCN464")
        self.assertEqual(await self.page.evaluate("T.requests.map(r=>r.path)"),["/api/auth/me"])

if __name__=="__main__":unittest.main(verbosity=2)
