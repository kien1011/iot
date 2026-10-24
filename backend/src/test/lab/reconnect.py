"""TEST ONLY: owns/restarts a disposable Mosquitto process; Backend/MySQL are already running.
Set IOT_TEST_MOSQUITTO and IOT_TEST_BROKER_CONFIG. Do not run against a shared broker.
The broker must initially be stopped. Uses test-only dependencies paho-mqtt via hardware.py.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

origin=os.environ.get('IOT_TEST_ORIGIN','http://127.0.0.1:8081')
http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
access_token=None
def api(path,body=None):
    headers={}
    if access_token and path!='/api/auth/login':headers['Authorization']='Bearer '+access_token
    if body is not None:headers['Content-Type']='application/json'
    req=urllib.request.Request(origin+path,data=None if body is None else json.dumps(body).encode(),headers=headers)
    with http.open(req,timeout=8) as response:
        raw=response.read();return json.loads(raw) if raw else None

def until(predicate,timeout=10):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        if predicate():return
        time.sleep(.1)
    raise AssertionError('Reconnect condition did not become true')

broker=None
peer=None

def start_broker():
    process=subprocess.Popen([os.environ['IOT_TEST_MOSQUITTO'],'-c',os.environ['IOT_TEST_BROKER_CONFIG']],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    def listening():
        try:
            with socket.create_connection(('127.0.0.1',int(os.environ.get('IOT_TEST_MQTT_PORT','1235'))),timeout=.2):return True
        except OSError:return False
    until(listening)
    return process

def command(operation,**args):
    peer.stdin.write(json.dumps({'operation':operation,**args})+'\n');peer.stdin.flush()
    response=json.loads(peer.stdout.readline());assert 'error' not in response,response
    return response['result']

def new_packet(temperature):
    command('publish',packet={'temperature':temperature,'humidity':55,'light':210})
    return api('/api/sensor-data/latest')['temperature']==temperature

try:
    access_token=api('/api/auth/login',{'username':'admin','password':'123456'})['token']
    before=api('/api/devices/1/status')
    assert before['status']=='OFF'
    accepted=api('/api/devices/1/control',{'action':'ON'})
    until(lambda:api('/api/devices/action-history?deviceId=1')['content'][0]['status']=='FAILED',7)
    assert api('/api/devices/1/status')==before
    broker=start_broker()
    peer=subprocess.Popen([sys.executable,str(Path(__file__).with_name('hardware.py'))],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
    assert json.loads(peer.stdout.readline())['ready']
    until(lambda:new_packet(32.1))
    assert command('commands')==[], 'An offline command was replayed'
    api('/api/devices/1/control',{'action':'ON'})
    until(lambda:api('/api/devices/1/status')['status']=='ON')
    api('/api/devices/1/control',{'action':'OFF'})
    until(lambda:api('/api/devices/1/status')['status']=='OFF')
    assert command('commands')==['1:ON','1:OFF']
    print('PASS initial broker unavailability, FAILED history, reconnect/subscribe and no queued command replay',flush=True)
    broker.terminate();broker.wait(timeout=8);broker=None
    until(lambda:not command('connected'))
    before=api('/api/devices/2/status')
    api('/api/devices/2/control',{'action':'ON'})
    until(lambda:api('/api/devices/action-history?deviceId=2')['content'][0]['status']=='FAILED',7)
    assert api('/api/devices/2/status')==before
    broker=start_broker()
    until(lambda:command('connected'))
    until(lambda:new_packet(33.1))
    assert command('commands')==['1:ON','1:OFF']
    api('/api/devices/2/control',{'action':'ON'})
    until(lambda:api('/api/devices/2/status')['status']=='ON')
    api('/api/devices/2/control',{'action':'OFF'})
    until(lambda:api('/api/devices/2/status')['status']=='OFF')
    assert command('commands')==['1:ON','1:OFF','2:ON','2:OFF']
    assert api('/api/devices/action-history?status=LOADING')['totalElements']==0
    print('PASS established connection loss, resubscribe after restart, new packets/commands, no replay and final OFF',flush=True)
    print('MQTT RECONNECT ACCEPTANCE SUCCESS',flush=True)
finally:
    if peer:
        peer.stdin.write(json.dumps({'operation':'quit'})+'\n');peer.stdin.flush()
        try:peer.wait(timeout=5)
        except subprocess.TimeoutExpired:peer.kill();peer.wait()
    if broker:broker.terminate();broker.wait(timeout=8)
