// ignore_for_file: avoid_print, avoid_relative_lib_imports
import 'dart:async';
import '../lib/host/host_client.dart';

/// 直接压宿主客户端（不经过 Flutter）：用来区分"协议/客户端"与"界面"的问题。
Future<void> main() async {
  print('starting host...');
  final client = await HostClient.start();
  print('started');
  print('ping: ${await client.call('ping').timeout(const Duration(seconds: 8))}');
  print('settings: ${await client.call('settings').timeout(const Duration(seconds: 8))}');
  try {
    final r = await client.call('resolve', {'link': 'https://speed.cloudflare.com/__down?bytes=1000'})
        .timeout(const Duration(seconds: 8));
    print('resolve: $r');
  } catch (e) {
    print('resolve error: $e');
  }
  await client.close();
  print('closed');
}
