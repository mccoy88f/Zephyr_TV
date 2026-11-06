# libtorrent's native code calls back into its Java side (SWIG's directors) and reads it by name: kept whole.
-keep class org.libtorrent4j.** { *; }
