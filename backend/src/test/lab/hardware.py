"""TEST ONLY: MQTT peer using the unchanged ESP8266 payload, plus read-only SQL assertions.
Dependencies: paho-mqtt, pymysql. Never loaded by the application.
"""
import json
import os
import sys
import threading
import time
import pymysql
import paho.mqtt.client as mqtt

acked_devices = {1, 2}
received_commands = []
connected = threading.Event()
client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id='iot-acceptance-test-peer', clean_session=True)
client.username_pw_set('nguyentrungkien', 'b23dccn464')

def on_connect(client, userdata, flags, reason_code, properties):
    if reason_code == 0:
        client.subscribe('iot/devices/command', qos=0)
        connected.set()

def on_message(client, userdata, message):
    command = message.payload.decode()
    received_commands.append(command)
    device, action = command.split(':')
    if int(device) in acked_devices:
        client.publish('iot/devices/status', json.dumps({'deviceId': int(device), 'status': action}), qos=0, retain=False)

client.on_connect = on_connect
client.on_message = on_message
client.connect(os.environ.get('IOT_TEST_MQTT_HOST', '127.0.0.1'), int(os.environ.get('IOT_TEST_MQTT_PORT', '1235')))
client.loop_start()
if not connected.wait(8):
    raise RuntimeError('MQTT test peer failed to connect')
print(json.dumps({'ready': True}), flush=True)

for line in sys.stdin:
    request = json.loads(line)
    try:
        operation = request['operation']
        if operation == 'ack':
            acked_devices = set(request['devices'])
            result = True
        elif operation == 'publish':
            client.publish('iot/sensors/data', json.dumps(request['packet']), qos=0, retain=False).wait_for_publish(3)
            result = True
        elif operation == 'burst':
            for i in range(request['count']):
                client.publish('iot/sensors/data', json.dumps({'temperature': 20+i/10, 'humidity': 60+i/10, 'light': 200+i}), qos=0, retain=False).wait_for_publish(3)
                time.sleep(.03)
            result = True
        elif operation == 'connected':
            result = client.is_connected()
        elif operation == 'commands':
            result = list(received_commands)
        elif operation == 'query':
            connection = pymysql.connect(host=os.environ.get('IOT_TEST_MYSQL_HOST', '127.0.0.1'),
                port=int(os.environ.get('IOT_TEST_MYSQL_PORT', '3306')), user=os.environ.get('IOT_TEST_MYSQL_USER', 'root'),
                password=os.environ.get('IOT_TEST_MYSQL_PASSWORD', '123'), database=os.environ.get('IOT_TEST_MYSQL_SCHEMA', 'iot_control_center'),
                cursorclass=pymysql.cursors.DictCursor)
            try:
                with connection.cursor() as cursor:
                    cursor.execute(request['sql'], request.get('params', []))
                    result = cursor.fetchall()
            finally:
                connection.close()
        elif operation == 'quit':
            break
        else:
            raise ValueError(operation)
        print(json.dumps({'result': result}, default=str), flush=True)
    except Exception as error:
        print(json.dumps({'error': str(error)}), flush=True)
client.disconnect()
client.loop_stop()
