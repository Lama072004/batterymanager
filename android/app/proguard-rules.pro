# Release: Debug-/Verbose-Logs vollständig entfernen (Tuner: keine Log-Kosten im Hintergrund).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
