// Widget smoke tests for the Katch screen recording app.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:katch/main.dart';

void main() {
  testWidgets('App renders KatchApp without crashing', (WidgetTester tester) async {
    await tester.pumpWidget(const KatchApp());
    // The app should display the title in the AppBar.
    expect(find.text('Katch'), findsWidgets);
  });

  testWidgets('Home screen shows Start Recording button', (WidgetTester tester) async {
    await tester.pumpWidget(const KatchApp());
    await tester.pump();
    expect(find.text('Start Recording'), findsOneWidget);
  });

  testWidgets('Home screen shows Settings icon', (WidgetTester tester) async {
    await tester.pumpWidget(const KatchApp());
    await tester.pump();
    expect(find.byIcon(Icons.settings_outlined), findsOneWidget);
  });
}

