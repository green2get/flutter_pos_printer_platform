import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:dart_ping/dart_ping.dart';
import 'package:flutter/material.dart';
import 'package:flutter_pos_printer_platform/src/models/printer_device.dart';
import 'package:network_info_plus/network_info_plus.dart';
import 'package:flutter_pos_printer_platform/discovery.dart';
import 'package:flutter_pos_printer_platform/printer.dart';
import 'package:ping_discover_network_forked/ping_discover_network_forked.dart';

class TcpPrinterInput extends BasePrinterInput {
  final String ipAddress;
  final int port;
  final Duration timeout;
  TcpPrinterInput({
    required this.ipAddress,
    this.port = 9100,
    this.timeout = const Duration(seconds: 5),
  });
}

class TcpPrinterInfo {
  String address;
  TcpPrinterInfo({
    required this.address,
  });
}

class TcpPrinterConnector implements PrinterConnector<TcpPrinterInput> {
  TcpPrinterConnector._();
  static TcpPrinterConnector _instance = TcpPrinterConnector._();

  static TcpPrinterConnector get instance => _instance;

  TcpPrinterConnector();
  Socket? _socket;
  TCPStatus status = TCPStatus.none;

  Stream<TCPStatus> get _statusStream => _statusStreamController.stream;
  final StreamController<TCPStatus> _statusStreamController = StreamController.broadcast();

  /// Bytes sent back by the printer, e.g. replies to ESC/POS status requests
  Stream<List<int>> get dataReceived => _dataStreamController.stream;
  final StreamController<List<int>> _dataStreamController = StreamController.broadcast();

  static Future<List<PrinterDiscovered<TcpPrinterInfo>>> discoverPrinters({String? ipAddress, int? port, Duration? timeOut}) async {
    final List<PrinterDiscovered<TcpPrinterInfo>> result = [];
    final defaultPort = port ?? 9100;

    String? deviceIp;
    if (Platform.isAndroid || Platform.isIOS) {
      deviceIp = await NetworkInfo().getWifiIP();
    } else if (ipAddress != null) deviceIp = ipAddress;
    if (deviceIp == null) return result;

    final String subnet = deviceIp.substring(0, deviceIp.lastIndexOf('.'));
    // final List<String> ips = List.generate(255, (index) => '$subnet.$index');

    final stream = NetworkAnalyzer.discover2(
      subnet,
      defaultPort,
      timeout: timeOut ?? Duration(milliseconds: 4000),
    );

    await for (var addr in stream) {
      if (addr.exists) {
        result.add(PrinterDiscovered<TcpPrinterInfo>(name: "${addr.ip}:$defaultPort", detail: TcpPrinterInfo(address: addr.ip)));
      }
    }

    return result;
  }

  /// Starts a scan for network printers.
  Stream<PrinterDevice> discovery({TcpPrinterInput? model}) async* {
    final defaultPort = model?.port ?? 9100;

    String? deviceIp;
    if (Platform.isAndroid || Platform.isIOS) {
      deviceIp = await NetworkInfo().getWifiIP();
    } else if (model?.ipAddress != null) {
      deviceIp = model!.ipAddress;
    } else {
      return;
      // throw Exception('No IP address provided');
    }

    final String subnet = deviceIp!.substring(0, deviceIp.lastIndexOf('.'));
    // final List<String> ips = List.generate(255, (index) => '$subnet.$index');
    try {
      final stream = NetworkAnalyzer.discover2(subnet, defaultPort);

      await for (var data in stream.map((message) => message)) {
        if (data.exists) {
          yield PrinterDevice(name: "${data.ip}:$defaultPort", address: data.ip);
        }
      }
    } catch (e) {}
  }

  @override
  Future<bool> send(List<int> bytes) async {
    if (_socket == null) return false;
    try {
      // final _socket = await Socket.connect(_host, _port, timeout: _timeout);
      _socket?.add(Uint8List.fromList(bytes));
      await Future.delayed(Duration(milliseconds: 100));
      // await _socket?.flush();
      // _socket?.destroy();
      return true;
    } catch (e) {
      _socket?.destroy();
      return false;
    }
  }

  @override
  Future<bool> connect(TcpPrinterInput model) async {
    _socket = await Socket.connect(model.ipAddress, model.port, timeout: model.timeout);
    debugPrint('socket connected'); //if opened you will get it here
    listenSocket();
    return true;
  }

  Future<bool> connectAndKeep(TcpPrinterInput model) async {
    try {
      if (status == TCPStatus.none) {
        _socket = await Socket.connect(model.ipAddress, model.port, timeout: model.timeout);
        status = TCPStatus.connected;
        debugPrint('socket connected'); //if opened you will get it here
        _statusStreamController.add(status);

        // Create ping object with desired args
        if (!Platform.isIOS) {
          final ping = Ping('${model.ipAddress}', interval: 3, timeout: 7);

          // Begin ping process and listen for output
          ping.stream.listen((PingData data) {
            if (data.error != null) {
              debugPrint(' ----- ping error ${data.error}');
              _socket?.destroy();
              status = TCPStatus.none;
              _statusStreamController.add(status);
            }
          });
        }

        listenSocket();
      }
      return true;
    } catch (e) {
      _socket?.destroy();
      status = TCPStatus.none;
      _statusStreamController.add(status);
      return false;
    }
  }

  /// [delayMs]: milliseconds to wait after destroying the socket
  @override
  Future<bool> disconnect({int? delayMs}) async {
    // await _socket?.flush();
    _socket?.destroy();
    // Reset right away so the next connectAndKeep opens a new socket
    // instead of reusing the destroyed one
    _socket = null;
    status = TCPStatus.none;
    // _statusStreamController.add(status);
    if (delayMs != null) {
      await Future.delayed(Duration(milliseconds: delayMs), () => null);
    }
    return true;
  }

  /// Gets the current state of the TCP module
  Stream<TCPStatus> get currentStatus async* {
    yield* _statusStream.cast<TCPStatus>();
  }

  void listenSocket() {
    final socket = _socket;
    socket?.listen(
      (Uint8List message) {
        _dataStreamController.add(message);
      },
      onDone: () {
        debugPrint('socket closed'); //if closed you will get it here
        socket.destroy();
        // A late close of an old socket must not reset a newer connection
        if (identical(_socket, socket)) {
          _socket = null;
          status = TCPStatus.none;
        }
        // ping.stop();
        // _statusStreamController.add(status);
      },
      onError: (error) {
        debugPrint('socket error $error');
        socket.destroy();
        if (identical(_socket, socket)) {
          _socket = null;
          status = TCPStatus.none;
        }
        // ping.stop();
        // _statusStreamController.add(status);
      },
    );
  }

  @override
  Future<bool> printLogo() async {
    throw UnimplementedError();
  }

  @override
  Future<bool> image(Uint8List image, {int threshold = 150}) {
    throw UnimplementedError();
  }
}
