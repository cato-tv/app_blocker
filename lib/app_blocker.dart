import 'dart:async';
import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

typedef Future<dynamic> MessageHandler(String message);

void _appBlockerSetupBackgroundChannel(
    {MethodChannel backgroundChannel =
        const MethodChannel(AppBlocker.APP_CHANNEL_BACKGROUND)}) async {
// Setup Flutter state needed for MethodChannels.
  WidgetsFlutterBinding.ensureInitialized();

// This is where the magic happens and we handle background events from the
// native portion of the plugin.
  backgroundChannel.setMethodCallHandler((MethodCall call) async {
    if (call.method == 'handleBackgroundMessage') {
      final CallbackHandle handle =
          CallbackHandle.fromRawHandle(call.arguments['handle']);
      final Function handlerFunction =
          PluginUtilities.getCallbackFromHandle(handle);
      try {
        await handlerFunction(call.arguments['appPackage']);
      } catch (e) {
        print('Unable to handle incoming background message.');
        print(e);
      }
      return Future<void>.value();
    }
  });

// Once we've finished initializing, let the native portion of the plugin
// know that it can start scheduling handling messages.
  backgroundChannel.invokeMethod<void>('AppBlockerService#initialized');
}

enum WEEKDAYS {
  NONE,
  SUNDAY,
  MONDAY,
  TUESDAY,
  WEDNESDAY,
  THURSDAY,
  FRIDAY,
  SATURDAY
}

class AppBlocker {
  static const APP_CHANNEL = 'club.cato/app_blocker';
  static const APP_CHANNEL_BACKGROUND = 'club.cato/app_blocker_background';

  factory AppBlocker() => _instance;

  @visibleForTesting
  AppBlocker.private(MethodChannel channel) : _channel = channel;

  static final AppBlocker _instance =
      AppBlocker.private(const MethodChannel(APP_CHANNEL));

  final MethodChannel _channel;

  MessageHandler _onResume;
  MessageHandler _onBackgroundMessage;

  void configure({
    MessageHandler onResume,
    MessageHandler onBackgroundMessage,
  }) {
    _onResume = onResume;

    _channel.setMethodCallHandler(_handleMethod);
    _channel.invokeMethod<void>('configure');

    if (onBackgroundMessage != null) {
      _onBackgroundMessage = onBackgroundMessage;
      final CallbackHandle backgroundSetupHandle =
          PluginUtilities.getCallbackHandle(_appBlockerSetupBackgroundChannel);

      final CallbackHandle backgroundMessageHandle =
          PluginUtilities.getCallbackHandle(_onBackgroundMessage);

      if (backgroundMessageHandle == null) {
        throw ArgumentError('''
          Failed to setup background message handler!
          ''');
      }

      _channel.invokeMethod<bool>(
        'AppBlockerService#start',
        <String, dynamic>{
          'setupHandle': backgroundSetupHandle.toRawHandle(),
          'backgroundHandle': backgroundMessageHandle.toRawHandle()
        },
      );
    }
  }

  Future<dynamic> _handleMethod(MethodCall call) async {
    switch (call.method) {
      case 'onResume':
        {
          print("onResume called with args ${call.arguments.toString()}");
          return _onResume(call.arguments.toString());
        }

      default:
        throw UnsupportedError('Unrecognized JSON message');
    }
  }

  /// Enables the appBlocker to block the apps
  Future<bool> enableAppBlocker() async {
    return await _channel.invokeMethod('enableAppBlocker');
  }

  /// Disables the appBlocker
  Future<bool> disableAppBlocker() async {
    return await _channel.invokeMethod('disableAppBlocker');
  }

  /// Set the restriction time window for the appBlocker.
  ///
  /// The appBlocker will only block apps between time [startTime] and [endTime]
  /// startTime and endTime must be in the format "HH:MM:SS" in 24 hours time.
  Future<bool> setRestrictionTime(String startTime, String endTime) async {
    return await _channel.invokeMethod(
      'setTime',
      {'startTime': startTime, 'endTime': endTime},
    );
  }

  /// Resets the restriction time window for the appBlocker to block app
  /// anytime of the day.
  Future<bool> resetRestrictionTime() async {
    return await setRestrictionTime('-1', '-1');
  }

  /// Set the weekdays when you want to appBlocker to block apps.
  Future<bool> setRestrictionWeekDays(List<WEEKDAYS> weekDays) async {
    List<String> weekDaysInt = weekDays.map((e) => e.index.toString()).toList();
    return await _channel.invokeMethod('setWeekDays', weekDaysInt);
  }

  /// Resets the weekdays to be restricted to restrict without weekdays
  Future<bool> resetRestrictionWeekDays() async {
    return await setRestrictionWeekDays(List());
  }

  /// Blocks the given list of packages.
  ///
  /// To reset the blocked packages send an empty list.
  /// Each time send a full list of packages to be blocked.
  Future<bool> updateBlockedPackages(List<String> packages) async {
    return await _channel.invokeMethod('updateBlockedPackages', packages);
  }

  Future<List<String>> getBlockedPackages(String packageName) async {
    return await _channel.invokeListMethod('getBlockedPackages');
  }

  void bringAppToFront() {
    _channel.invokeMethod("bringAppToForeground");
  }
}
