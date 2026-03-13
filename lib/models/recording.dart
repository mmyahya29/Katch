/// Represents a saved screen recording.
class Recording {
  final String path;
  final String name;
  final DateTime createdAt;
  final int? durationMs;
  final int? fileSizeBytes;

  const Recording({
    required this.path,
    required this.name,
    required this.createdAt,
    this.durationMs,
    this.fileSizeBytes,
  });

  factory Recording.fromMap(Map<String, dynamic> map) {
    return Recording(
      path: map['path'] as String,
      name: map['name'] as String,
      createdAt: DateTime.fromMillisecondsSinceEpoch(map['createdAt'] as int),
      durationMs: map['durationMs'] as int?,
      fileSizeBytes: map['fileSizeBytes'] as int?,
    );
  }

  String get formattedDuration {
    if (durationMs == null) return '--:--';
    final duration = Duration(milliseconds: durationMs!);
    final hours = duration.inHours;
    final minutes = duration.inMinutes.remainder(60).toString().padLeft(2, '0');
    final seconds = duration.inSeconds.remainder(60).toString().padLeft(2, '0');
    if (hours > 0) {
      return '$hours:$minutes:$seconds';
    }
    return '$minutes:$seconds';
  }

  String get formattedSize {
    if (fileSizeBytes == null) return '';
    final kb = fileSizeBytes! / 1024;
    if (kb < 1024) return '${kb.toStringAsFixed(1)} KB';
    final mb = kb / 1024;
    return '${mb.toStringAsFixed(1)} MB';
  }
}
