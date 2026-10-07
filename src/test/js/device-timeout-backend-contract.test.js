const assert = require('node:assert/strict');
const fs = require('node:fs');

const service = fs.readFileSync(
  'src/main/java/com/ptit/iot/service/DeviceControlService.java',
  'utf8'
);

assert.match(
  service,
  /webSocketBroadcaster\.broadcast\(\s*"device:timeout",\s*new DeviceTimeoutEvent\(historyId,\s*deviceId\)\s*\)/,
  'Backend timeout path must notify the frontend with device:timeout without changing DB state'
);

const dtoPath = 'src/main/java/com/ptit/iot/dto/device/DeviceTimeoutEvent.java';
assert.equal(fs.existsSync(dtoPath), true, 'DeviceTimeoutEvent DTO must exist');
if (fs.existsSync(dtoPath)) {
  const dto = fs.readFileSync(dtoPath, 'utf8');
  assert.match(dto, /record DeviceTimeoutEvent\(\s*Long actionHistoryId,\s*Integer deviceId\s*\)/s);
}

console.log('backend timeout websocket contract: PASS');
