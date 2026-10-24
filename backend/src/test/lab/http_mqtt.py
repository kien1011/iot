"""TEST ONLY: full deployed HTTP/token/WebSocket/Mosquitto/MySQL flow.
Run from backend with the same test environment variables as browser.cjs.
Needs websockets plus the dependencies of hardware.py. Uses a fresh test deployment.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
from websockets.sync.client import connect

origin = os.environ.get('IOT_TEST_ORIGIN', 'http://127.0.0.1:8081')
http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
access_token = None

def request(path, method='GET', body=None, headers=None, expected=200, raw=False):
    headers = dict(headers or {})
    if access_token and path != '/api/auth/login':
        headers.setdefault('Authorization', 'Bearer ' + access_token)
    if body is not None:
        headers['Content-Type'] = 'application/json'
        body = json.dumps(body).encode()
    req = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
    try:
        response = http.open(req, timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    assert response.status == expected, (path, response.status, response.read().decode())
    data = response.read()
    return data if raw else (json.loads(data) if data else None)

def post(path, body=None, expected=200):
    return request(path, 'POST', body, expected=expected)

def until(check, timeout=8):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.06)
    raise AssertionError('Condition did not become true')

peer = subprocess.Popen([sys.executable, str(Path(__file__).with_name('hardware.py'))], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
assert json.loads(peer.stdout.readline())['ready']

def command(operation, **values):
    peer.stdin.write(json.dumps({'operation': operation, **values})+'\n')
    peer.stdin.flush()
    result = json.loads(peer.stdout.readline())
    assert 'error' not in result, result
    return result['result']

ws = None

def event(kind, device_id=None, timeout=8):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        value = json.loads(ws.recv(timeout=max(.1,deadline-time.monotonic())))
        if value['type'] == kind and (device_id is None or value['data']['deviceId']==device_id):
            return value['data']
    raise AssertionError('Expected event '+kind)

def control(device_id, action):
    response = post(f'/api/devices/{device_id}/control', {'action': action})
    assert response['status']=='LOADING' and response['timeoutSeconds']==5
    return response

try:
    request('/api/auth/me', expected=401)
    request('/api/sensor-data/latest', expected=401)
    post('/api/devices/1/control', {'action':'ON'}, expected=401)
    post('/api/auth/login', {'username':'admin','password':'wrong'}, expected=401)
    user = post('/api/auth/login', {'username':'admin','password':'123456'})
    access_token = user.pop('token')
    assert access_token and user == {'id':1,'username':'admin','fullName':'Administrator'}
    assert request('/api/auth/me') == user
    request('/api/auth/me', headers={'Authorization':'Bearer invalid-token'}, expected=401)
    ws = connect(origin.replace('http://','ws://').replace('https://','wss://')+'/ws', origin=origin, subprotocols=['bearer',access_token])
    assert ws.subprotocol == 'bearer'
    assert request('/api/sensor-data/latest')['temperature'] is None
    assert not request('/api/sensor-data/chart')['temperature']
    for time_text in ['0000','9999','2024-02-29']:
        assert request('/api/sensor-data/history?time='+time_text)['totalElements']==0
    frontend = Path(__file__).resolve().parents[4]/'frontend'
    for asset in ['index.html','html/login.html','html/dashboard.html','html/sensor-history.html','html/device-history.html','html/profile.html',
                  'css/app.css','css/profile.css','css/dashboard.css','js/api.js','js/app.js','js/dashboard.js','js/history.js','vendor/chart.umd.min.js']:
        assert request('/'+asset, raw=True)==(frontend/asset).read_bytes(), asset
    print('PASS deployed login/Bearer token, actual WebSocket handshake, static FE assets and empty snapshots', flush=True)

    command('publish',packet={'temperature':28.56,'humidity':70.14,'light':68})
    reading = event('sensor:update')
    assert reading == request('/api/sensor-data/latest')
    assert reading['temperature']==28.6 and reading['humidity']==70.1
    assert reading['light']==int(reading['light']) and reading['light']>650
    rows=command('query',sql='SELECT COUNT(*) AS n,COUNT(DISTINCT created_at) AS times FROM sensor_data')
    assert rows==[{'n':3,'times':1}],rows
    found=request('/api/sensor-data/history?sensorId=3&value='+str(reading['light']))
    assert found['totalElements']==1 and found['content'][0]['value']==reading['light']
    assert request('/api/sensor-data/chart')['light'][0]['value']==reading['light']
    command('burst',count=25)
    until(lambda:request('/api/sensor-data/history')['totalElements']==78)
    chart=request('/api/sensor-data/chart')
    assert all(len(points)==20 for points in chart.values())
    assert [p['id'] for p in chart['temperature']]==sorted(p['id'] for p in chart['temperature'])
    stable=request('/api/sensor-data/latest');time.sleep(1.1)
    assert stable==request('/api/sensor-data/latest')
    assert chart==request('/api/sensor-data/chart')
    print('PASS actual QoS0 sensor packets -> atomic MySQL rows -> REST and WebSocket, precision/lux, latest 20 points and silence',flush=True)

    initial=request('/api/devices/1/status')
    started=time.monotonic();first=control(1,'ON');success=event('device:status',1)
    assert time.monotonic()-started<5
    assert success==request('/api/devices/1/status') and success['status']=='ON'
    assert success['updatedAt']>=initial['updatedAt']
    row=request('/api/devices/action-history?deviceId=1')['content'][0]
    assert row['id']==first['id'] and row['status']=='SUCCESS' and row['username']=='admin'
    created=row['createdAt']
    control(1,'OFF');event('device:status',1)
    assert next(r for r in request('/api/devices/action-history')['content'] if r['id']==first['id'])['createdAt']==created
    print('PASS actual command string/ACK, success before five seconds, confirmed device time and unchanged history creation time',flush=True)

    old=request('/api/devices/1/status')
    command('ack',devices=[2])
    first=control(1,'ON')
    post('/api/devices/1/control',{'action':'ON'},expected=409)
    control(2,'ON')
    event('device:status',2)
    assert request('/api/devices/1/status')==old
    assert request('/api/devices/action-history?deviceId=1')['content'][0]['status']=='LOADING'
    assert event('device:timeout',1)==old
    assert request('/api/devices/1/status')==old
    failed=request('/api/devices/action-history?deviceId=1&action=ON&status=FAILED')
    assert failed['totalElements']==1 and failed['content'][0]['id']==first['id']
    command('ack',devices=[1,2]);control(1,'ON');event('device:status',1)
    print('PASS independent LEDs, same-LED exclusion, one timeout vs one success, preserved updated_at and successful retry',flush=True)

    for device in [1,2]:
        control(device,'OFF');event('device:status',device)
    before={device:request(f'/api/devices/{device}/status') for device in [1,2]}
    command('ack',devices=[])
    requests=[control(1,'ON'),control(2,'ON')]
    # Read both timeout events even if their callback order differs.
    terminal={}
    while len(terminal)<2:
        response=event('device:timeout');terminal[response['deviceId']]=response
    assert terminal==before
    command('ack',devices=[1,2])
    for device in [1,2]:
        control(device,'ON');event('device:status',device)
        control(device,'OFF');event('device:status',device)
    assert request('/api/devices/action-history?status=LOADING')['totalElements']==0
    print('PASS both timeouts, both retries/reverse actions, zero leftover LOADING and final OFF state',flush=True)

    result=request('/api/sensor-data/history?sensorId=1&size=5&page=1')
    assert result['size']==5 and result['page']==1 and len(result['content'])==5 and result['totalElements']==26
    assert [row['id'] for row in result['content']]==sorted([row['id'] for row in result['content']],reverse=True)
    for invalid in ['1e2','Infinity','12,3','1.']:
        request('/api/sensor-data/history?value='+urllib.parse.quote(invalid),expected=400)
    request('/api/sensor-data/history?time=2023-02-29',expected=400)
    request('/api/devices/action-history?time=2026-13',expected=400)
    print('PASS database AND filters, pagination, valid empty years/leap day and invalid search HTTP 400',flush=True)

    ws.close();ws=None
    old_count=command('query',sql='SELECT COUNT(*) AS n FROM sensor_data')[0]['n']
    command('publish',packet={'temperature':31.2,'humidity':58.9,'light':220})
    until(lambda:command('query',sql='SELECT COUNT(*) AS n FROM sensor_data')[0]['n']==old_count+3)
    post('/api/auth/logout',expected=204)
    request('/api/auth/me',expected=401)
    access_token = post('/api/auth/login',{'username':'admin','password':'123456'})['token']
    assert request('/api/auth/me')['username']=='admin'
    assert request('/api/sensor-data/latest')['temperature']==31.2
    for device in [1,2]:assert request(f'/api/devices/{device}/status')['status']=='OFF'
    print('PASS acquisition with no FE/socket open, reload from database, 204 logout and relogin',flush=True)
    print('HTTP/MQTT/WEBSOCKET ACCEPTANCE SUCCESS',flush=True)
finally:
    if ws is not None:ws.close()
    peer.stdin.write(json.dumps({'operation':'quit'})+'\n');peer.stdin.flush()
    try:peer.wait(timeout=5)
    except subprocess.TimeoutExpired:peer.kill();peer.wait()
