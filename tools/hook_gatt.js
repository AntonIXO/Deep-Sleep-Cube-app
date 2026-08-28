
function hex(b) {
  if (b === null || b === undefined) return "null";
  var a = new Uint8Array(b);
  var s = [];
  for (var i = 0; i < a.length; i++) s.push(("0" + a[i].toString(16)).slice(-2));
  return s.join(" ");
}
function dump(label, ch, value, type) {
  var uuid = "";
  try { uuid = ch.getUuid().toString(); } catch (e) { uuid = "?"; }
  console.log(label + " uuid=" + uuid + " type=" + type + " val=" + hex(value));
}
Java.perform(function () {
  var Gatt = Java.use("android.bluetooth.BluetoothGatt");
  try {
    Gatt.writeCharacteristic.overload("android.bluetooth.BluetoothGattCharacteristic").implementation = function (ch) {
      dump("WRITE", ch, ch.getValue(), ch.getWriteType());
      return this.writeCharacteristic(ch);
    };
  } catch (e) { console.log("old: " + e); }
  try {
    Gatt.writeCharacteristic.overload("android.bluetooth.BluetoothGattCharacteristic", "[B", "int").implementation = function (ch, value, type) {
      dump("WRITE33", ch, value, type);
      return this.writeCharacteristic(ch, value, type);
    };
  } catch (e) { console.log("n: " + e); }
  console.log("GATT write hooks installed");
});
