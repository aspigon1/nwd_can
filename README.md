# nwd_can — live CAN data for the radio dashboard

Reads live vehicle data straight from your head unit's **own** CAN service
(`com.nwd.can.setting` / `CanService`) over the vendor's published third‑party
SDK channel. No wires, no OBD dongle — the unit is already talking to the CAN
box; this just asks it for the data.

Works on the **real head unit**. On a PC emulator the service won't exist (or
has no CAN hardware behind it), so you'll get a `status: connected:false` or no
data — that's expected, not a bug.

## What you get on the stream

Each event is a `Map` with a `type`:

| type      | key fields |
|-----------|-----------|
| `status`  | `connected` (bool), `error?` |
| `carInfo` | `instantSpeed` (km/h), `engineSpeed` (RPM), `fuelLevel` (0–100 %), `batteryVoltage` (V), `gear`, `leftTurn`, `rightTurn`, `handbrake`, `driveTime1`, `avgOil1`, door locks, lights … |
| `ac`      | `acSwitch`, `fanLevel`, `tempLeft`, `tempRight`, `frontDefog`, `recirculate` … |
| `door`    | `frontLeft`, `frontRight`, `rearLeft`, `rearRight`, `trunk`, `bonnet` (true = open) |
| `radar`   | 8 × park-sensor levels |
| `raw`     | `hex` + optional `sub` — frames we haven't mapped yet (used to find coolant/outside temp on your Golf) |

> Coolant temp and outside/ambient temp are vehicle‑specific and may arrive on a
> frame we haven't mapped. The `raw` events let us read the real bytes off your
> Golf and add them. `fuelLevel` and the rest are already mapped.

## Add it to FlutterFlow (git dependency)

1. Put this `nwd_can/` folder in a GitHub repo (public is simplest), e.g.
   `https://github.com/<you>/nwd_can`.
2. In FlutterFlow: **Settings & Integrations → App Settings → Project
   Dependencies → Custom Pub Dependencies** and add:
   ```yaml
   nwd_can:
     git:
       url: https://github.com/<you>/nwd_can.git
       ref: main
   ```
3. In your dashboard custom widget, import and listen:
   ```dart
   import 'package:nwd_can/nwd_can.dart';

   final _can = NwdCan();
   StreamSubscription? _canSub;

   @override
   void initState() {
     super.initState();
     _canSub = _can.stream.listen((d) {
       if (!mounted) return;
       setState(() {
         switch (d['type']) {
           case 'carInfo':
             _rpm        = (d['engineSpeed'] ?? -1).toDouble();
             _fuelPct    = (d['fuelLevel']   ?? -1).toDouble(); // top fuel bar
             _canSpeed   = (d['instantSpeed']?? -1).toDouble();
             _battV      = (d['batteryVoltage'] ?? -1).toDouble();
             break;
           case 'ac':
             _acTempL = d['tempLeft'];  _acTempR = d['tempRight'];
             break;
           case 'status':
             _canOk = d['connected'] == true;
             break;
         }
       });
     });
     _can.start();
   }

   @override
   void dispose() { _canSub?.cancel(); _can.stop(); super.dispose(); }
   ```

That's it — no Firebase, no permissions prompt, no extra setup on the unit.
