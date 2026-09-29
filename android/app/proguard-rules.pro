# R8 rules for the app module.
#
# Only :app and :shura-source-host are dexed here. The two ABI jars are shipped as assets and
# unpacked at first run, so eu.kanade.tachiyomi.*, app.shura.abi.* and keiyoushi.source.* are
# never inputs to R8 and need no rules in this file.

# The extension facing contract. An extension's bridge is loaded at runtime and calls these, so
# every member has to survive obfuscation and the class names have to stay stable.
-keep class app.shura.source.api.** { *; }
-keep interface app.shura.source.api.SourceProvider { *; }

# Keep the exception types identifiable when an error crosses the classloader boundary and is
# mapped onto a host error, which matches on the class.
-keepnames class app.shura.source.api.SourceException
-keepnames class app.shura.source.api.UnsupportedSourceOperationException
-keepnames class app.shura.source.api.SourceAuthenticationException
-keepnames class app.shura.source.api.SourceEntryNotFoundException

# ExtensionAbi is compared by ordinal and rendered by name, both of which R8 would otherwise
# not be free to change.
-keepclassmembers enum app.shura.source.api.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries();
}
