import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _useMicrophone = true;
  bool _useDeviceAudio = true;
  String _videoResolution = '1080p';
  int _videoBitrate = 8000000;
  int _frameRate = 30;

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _useMicrophone = prefs.getBool('use_microphone') ?? true;
      _useDeviceAudio = prefs.getBool('use_device_audio') ?? true;
      _videoResolution = prefs.getString('video_resolution') ?? '1080p';
      _videoBitrate = prefs.getInt('video_bitrate') ?? 8000000;
      _frameRate = prefs.getInt('frame_rate') ?? 30;
    });
  }

  Future<void> _saveBool(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(key, value);
  }

  Future<void> _saveString(String key, String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(key, value);
  }

  Future<void> _saveInt(String key, int value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(key, value);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        children: [
          _sectionHeader('Audio'),
          SwitchListTile(
            secondary: const Icon(Icons.mic_outlined),
            title: const Text('Microphone'),
            subtitle: const Text(
              'Record mic audio. Uses VOICE_COMMUNICATION mode so other apps '
              'can still access the microphone simultaneously.',
            ),
            value: _useMicrophone,
            onChanged: (value) {
              setState(() => _useMicrophone = value);
              _saveBool('use_microphone', value);
            },
          ),
          SwitchListTile(
            secondary: const Icon(Icons.volume_up_outlined),
            title: const Text('Device Audio'),
            subtitle: const Text(
              'Capture internal audio (requires Android 10+).',
            ),
            value: _useDeviceAudio,
            onChanged: (value) {
              setState(() => _useDeviceAudio = value);
              _saveBool('use_device_audio', value);
            },
          ),
          _sectionHeader('Video Quality'),
          ListTile(
            leading: const Icon(Icons.high_quality_outlined),
            title: const Text('Resolution'),
            trailing: DropdownButton<String>(
              value: _videoResolution,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(value: '720p', child: Text('720p')),
                DropdownMenuItem(value: '1080p', child: Text('1080p')),
                DropdownMenuItem(value: '1440p', child: Text('1440p')),
              ],
              onChanged: (value) {
                if (value != null) {
                  setState(() => _videoResolution = value);
                  _saveString('video_resolution', value);
                }
              },
            ),
          ),
          ListTile(
            leading: const Icon(Icons.speed_outlined),
            title: const Text('Frame Rate'),
            trailing: DropdownButton<int>(
              value: _frameRate,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(value: 24, child: Text('24 fps')),
                DropdownMenuItem(value: 30, child: Text('30 fps')),
                DropdownMenuItem(value: 60, child: Text('60 fps')),
              ],
              onChanged: (value) {
                if (value != null) {
                  setState(() => _frameRate = value);
                  _saveInt('frame_rate', value);
                }
              },
            ),
          ),
          ListTile(
            leading: const Icon(Icons.data_usage_outlined),
            title: const Text('Video Bitrate'),
            trailing: DropdownButton<int>(
              value: _videoBitrate,
              underline: const SizedBox(),
              items: const [
                DropdownMenuItem(value: 4000000, child: Text('4 Mbps')),
                DropdownMenuItem(value: 8000000, child: Text('8 Mbps')),
                DropdownMenuItem(value: 16000000, child: Text('16 Mbps')),
              ],
              onChanged: (value) {
                if (value != null) {
                  setState(() => _videoBitrate = value);
                  _saveInt('video_bitrate', value);
                }
              },
            ),
          ),
          _sectionHeader('About'),
          const ListTile(
            leading: Icon(Icons.info_outline),
            title: Text('Katch Screen Recorder'),
            subtitle: Text('Version 1.0.0'),
          ),
        ],
      ),
    );
  }

  Widget _sectionHeader(String title) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: Theme.of(context).textTheme.labelLarge?.copyWith(
              color: Theme.of(context).colorScheme.primary,
            ),
      ),
    );
  }
}
