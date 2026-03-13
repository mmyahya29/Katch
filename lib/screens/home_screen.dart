import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../models/recording.dart';
import '../services/recording_channel.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  List<Recording> _recordings = [];
  bool _isRecording = false;
  bool _isPaused = false;
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadData();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _loadData();
    }
  }

  Future<void> _loadData() async {
    await _refreshStatus();
    await _loadRecordings();
  }

  Future<void> _refreshStatus() async {
    try {
      final status = await RecordingChannel.getStatus();
      if (mounted) {
        setState(() {
          _isRecording = status['isRecording'] as bool? ?? false;
          _isPaused = status['isPaused'] as bool? ?? false;
        });
      }
    } catch (_) {}
  }

  Future<void> _loadRecordings() async {
    try {
      final recordings = await RecordingChannel.getRecordings();
      if (mounted) {
        setState(() => _recordings = recordings);
      }
    } catch (_) {}
  }

  Future<bool> _requestPermissions() async {
    // Microphone permission
    final micStatus = await Permission.microphone.request();
    if (micStatus.isPermanentlyDenied) {
      await openAppSettings();
      return false;
    }

    // Post-notification permission (Android 13+)
    await Permission.notification.request();

    return true;
  }

  Future<void> _startRecording() async {
    setState(() => _isLoading = true);

    try {
      final granted = await _requestPermissions();
      if (!granted) return;

      final prefs = await SharedPreferences.getInstance();
      final useMic = prefs.getBool('use_microphone') ?? true;
      final useDeviceAudio = prefs.getBool('use_device_audio') ?? true;
      final videoResolution = prefs.getString('video_resolution') ?? '1080p';
      final videoBitrate = prefs.getInt('video_bitrate') ?? 8000000;
      final frameRate = prefs.getInt('frame_rate') ?? 30;

      final started = await RecordingChannel.startRecording(
        useMic: useMic,
        useDeviceAudio: useDeviceAudio,
        videoResolution: videoResolution,
        videoBitrate: videoBitrate,
        frameRate: frameRate,
      );

      if (!mounted) return;
      if (started) {
        setState(() {
          _isRecording = true;
          _isPaused = false;
        });
      } else {
        _showSnack('Screen capture permission denied.');
      }
    } on Exception catch (e) {
      _showSnack(e.toString());
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _stopRecording() async {
    try {
      await RecordingChannel.stopRecording();
      setState(() {
        _isRecording = false;
        _isPaused = false;
      });
      await _loadRecordings();
    } on Exception catch (e) {
      _showSnack(e.toString());
    }
  }

  Future<void> _togglePause() async {
    try {
      if (_isPaused) {
        await RecordingChannel.resumeRecording();
      } else {
        await RecordingChannel.pauseRecording();
      }
      setState(() => _isPaused = !_isPaused);
    } on Exception catch (e) {
      _showSnack(e.toString());
    }
  }

  Future<void> _confirmDelete(Recording recording) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Delete Recording'),
        content: Text('Delete "${recording.name}"?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            child:
                const Text('Delete', style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );

    if (confirmed == true) {
      await RecordingChannel.deleteRecording(recording.path);
      await _loadRecordings();
    }
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
        .showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Katch'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            tooltip: 'Settings',
            onPressed: () async {
              await Navigator.pushNamed(context, '/settings');
              _refreshStatus();
            },
          ),
        ],
      ),
      body: Column(
        children: [
          if (_isRecording) _buildRecordingBanner(),
          Expanded(
            child: _recordings.isEmpty
                ? _buildEmptyState()
                : _buildRecordingsList(),
          ),
        ],
      ),
      floatingActionButton: _isRecording
          ? null
          : FloatingActionButton.extended(
              onPressed: _isLoading ? null : _startRecording,
              icon: _isLoading
                  ? const SizedBox(
                      width: 20,
                      height: 20,
                      child: CircularProgressIndicator(strokeWidth: 2),
                    )
                  : const Icon(Icons.fiber_manual_record),
              label: const Text('Start Recording'),
            ),
    );
  }

  Widget _buildRecordingBanner() {
    final cs = Theme.of(context).colorScheme;
    return Container(
      color: cs.errorContainer,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Icon(
            _isPaused ? Icons.pause_circle_outline : Icons.radio_button_on,
            color: _isPaused ? Colors.orange : Colors.red,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              _isPaused ? 'Recording Paused' : 'Recording…',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
          TextButton(
            onPressed: _togglePause,
            child: Text(_isPaused ? 'Resume' : 'Pause'),
          ),
          TextButton(
            onPressed: _stopRecording,
            child: const Text('Stop',
                style: TextStyle(color: Colors.redAccent)),
          ),
        ],
      ),
    );
  }

  Widget _buildEmptyState() {
    final cs = Theme.of(context).colorScheme;
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.videocam_off_outlined,
              size: 72, color: cs.onSurface.withAlpha(76)),
          const SizedBox(height: 16),
          Text(
            'No recordings yet',
            style: Theme.of(context)
                .textTheme
                .titleMedium
                ?.copyWith(color: cs.onSurface.withAlpha(153)),
          ),
          const SizedBox(height: 8),
          Text(
            'Tap "Start Recording" to begin',
            style: Theme.of(context)
                .textTheme
                .bodySmall
                ?.copyWith(color: cs.onSurface.withAlpha(102)),
          ),
          const SizedBox(height: 80),
        ],
      ),
    );
  }

  Widget _buildRecordingsList() {
    return RefreshIndicator(
      onRefresh: _loadRecordings,
      child: ListView.builder(
        padding: const EdgeInsets.only(bottom: 80),
        itemCount: _recordings.length,
        itemBuilder: (context, index) {
          final rec = _recordings[index];
          return Card(
            margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: ListTile(
              leading: CircleAvatar(
                backgroundColor:
                    Theme.of(context).colorScheme.primaryContainer,
                child: const Icon(Icons.videocam),
              ),
              title: Text(rec.name, maxLines: 1, overflow: TextOverflow.ellipsis),
              subtitle: Text(
                '${_formatDate(rec.createdAt)}'
                '${rec.formattedDuration != '--:--' ? '  •  ${rec.formattedDuration}' : ''}'
                '${rec.formattedSize.isNotEmpty ? '  •  ${rec.formattedSize}' : ''}',
              ),
              trailing: IconButton(
                icon: const Icon(Icons.delete_outline),
                onPressed: () => _confirmDelete(rec),
              ),
            ),
          );
        },
      ),
    );
  }

  String _formatDate(DateTime date) {
    return '${date.year}-'
        '${date.month.toString().padLeft(2, '0')}-'
        '${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:'
        '${date.minute.toString().padLeft(2, '0')}';
  }
}
