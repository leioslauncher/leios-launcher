# Classes Android starts by name (activities, services, receivers in the manifest) are kept
# automatically. These are the extra ones R8 can't see:

# The device-admin and reset receivers are nested classes named in the manifest; keep their
# names exactly as written there.
-keep class tv.leios.launcher.SleepTimer$* { *; }
-keep class tv.leios.launcher.Lock$Reset { *; }

# Binder calls to JMGO's service are built by hand (descriptor strings and transaction codes),
# not through reflection, so nothing else needs keeping.
