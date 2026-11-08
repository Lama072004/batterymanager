import 'package:dynamic_color/dynamic_color.dart';
import 'package:flutter/material.dart';

import 'core/backend.dart';
import 'core/controller.dart';
import 'features/onboarding.dart';
import 'features/overview.dart';
import 'ui/theme.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(BatteryApp(backend: AndroidBackend()));
}

class BatteryApp extends StatefulWidget {
  const BatteryApp({super.key, required this.backend});
  final BatteryBackend backend;

  @override
  State<BatteryApp> createState() => _BatteryAppState();
}

class _BatteryAppState extends State<BatteryApp> {
  late final AppController c = AppController(widget.backend);

  @override
  void initState() {
    super.initState();
    c.start();
  }

  @override
  void dispose() {
    c.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (light, dark) => MaterialApp(
        title: 'Batterymanager',
        debugShowCheckedModeBanner: false,
        themeMode: ThemeMode.system,
        locale: const Locale('de', 'DE'),
        theme: buildTheme(light?.primary ?? kSeed, Brightness.light),
        darkTheme: buildTheme(dark?.primary ?? kSeed, Brightness.dark),
        home: ListenableBuilder(
          listenable: c,
          builder: (context, _) {
            if (c.loading) return const Scaffold(body: SizedBox.shrink());
            if (!(c.state?.onboarded ?? false)) {
              return OnboardingScreen(c: c, onDone: () => setState(() {}));
            }
            return OverviewScreen(c: c);
          },
        ),
      ),
    );
  }
}
