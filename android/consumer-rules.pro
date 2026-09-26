# Applied to every app that uses rfid_kit.
#
# The Zebra RFID and Link-OS SDKs resolve many of their own types by reflection
# and reference optional classes that are not on the classpath, so R8 must
# neither strip them nor fail on the missing references.
-keep class com.zebra.** { *; }
-dontwarn com.zebra.**
-dontwarn com.qti.**
-dontwarn org.apache.commons.collections4.**

# The plugin's own bridge classes are reached from Flutter's registrant.
-keep class dev.devcenter.rfid_kit.** { *; }
