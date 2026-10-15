import 'dart:async';
import 'package:flutter/services.dart';

/// Live CAN-bus data from an NWD / Digital Star head unit's own CanService.
///
/// Usage:
///   final can = NwdCan();
///   can.stream.listen((d) { ... d['type'] == 'carInfo' ... });
///   await can.start();
class NwdCan {
  static const MethodChannel _m = MethodChannel('nwd_can/methods');
  static const EventChannel _e = EventChannel('nwd_can/events');

  Stream<Map<String, dynamic>>? _stream;

  /// Broadcast stream of decoded frames. Each event is a map with a 'type':
  ///   'status'  -> {connected: bool, error?: String}
  ///   'carInfo' -> {instantSpeed, engineSpeed, fuelLevel, batteryVoltage,
  ///                 gear, leftTurn, rightTurn, handbrake, driveTime1, ...}
  ///   'ac'      -> {acSwitch, fanLevel, tempLeft, tempRight, ...}
  ///   'door'    -> {frontLeft, frontRight, rearLeft, rearRight, trunk, bonnet}
  ///   'radar'   -> {frontLeftLvl, ... rearRightLvl}
  ///   'raw'     -> {hex: '6e02...', sub?: int}   (unmapped frames)
  Stream<Map<String, dynamic>> get stream {
    _stream ??= _e.receiveBroadcastStream().map<Map<String, dynamic>>(
          (e) => Map<String, dynamic>.from(e as Map),
        );
    return _stream!;
  }

  /// Bind the CAN service and start streaming. Returns true if the bind call
  /// was accepted (actual connection confirmed via a 'status' event).
  Future<bool> start() async {
    final ok = await _m.invokeMethod<bool>('start');
    return ok ?? false;
  }

  Future<void> stop() => _m.invokeMethod('stop');

  Future<bool> isConnected() async =>
      (await _m.invokeMethod<bool>('isConnected')) ?? false;
}
