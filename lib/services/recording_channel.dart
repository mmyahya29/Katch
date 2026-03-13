import 'package:flutter/services.dart';
import '../models/recording.dart';

/// Wraps the [MethodChannel] used to communicate with the Android recording service.
class RecordingChannel {
  static const _channel = MethodChannel('com.example.katch/recording');

  /// Requests screen-capture permission and starts the recording service.
  ///
  /// Returns `true` when the recording has started, `false` if the user denied
  /// the screen-capture permission.
  static Future<bool> startRecording({
    required bool useMic,
    required bool useDeviceAudio,
    required String videoResolution,
    required int videoBitrate,
    required int frameRate,
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('startRecording', {
        'useMic': useMic,
        'useDeviceAudio': useDeviceAudio,
        'videoResolution': videoResolution,
        'videoBitrate': videoBitrate,
        'frameRate': frameRate,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      throw Exception('Failed to start recording: ${e.message}');
    }
  }

  /// Stops the active recording and saves the file.
  static Future<void> stopRecording() async {
    await _channel.invokeMethod<void>('stopRecording');
  }

  /// Pauses the active recording (Android 7.0+).
  static Future<void> pauseRecording() async {
    await _channel.invokeMethod<void>('pauseRecording');
  }

  /// Resumes a paused recording (Android 7.0+).
  static Future<void> resumeRecording() async {
    await _channel.invokeMethod<void>('resumeRecording');
  }

  /// Returns `{isRecording: bool, isPaused: bool}`.
  static Future<Map<String, dynamic>> getStatus() async {
    final result =
        await _channel.invokeMapMethod<String, dynamic>('getStatus');
    return result ?? {'isRecording': false, 'isPaused': false};
  }

  /// Returns all recordings saved in the Katch directory.
  static Future<List<Recording>> getRecordings() async {
    final result = await _channel.invokeListMethod<Map>('getRecordings');
    if (result == null) return [];
    return result
        .map((m) => Recording.fromMap(Map<String, dynamic>.from(m)))
        .toList();
  }

  /// Deletes the recording at [path].
  static Future<void> deleteRecording(String path) async {
    await _channel.invokeMethod<void>('deleteRecording', {'path': path});
  }
}
