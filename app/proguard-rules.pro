# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-FileCopyrightText: 2026 The ArkUI Project
# SPDX-License-Identifier: Apache-2.0

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# The separately installed device tests exercise these APIs in the real gallery process.
# Preserve their ABI while keeping the UI and third-party libraries fully optimized.
-keep class org.lineageos.glimpse.home.GalleryFolders { public *; }
-keep class org.lineageos.glimpse.home.GalleryFolder { public *; }
-keep class org.lineageos.glimpse.home.GalleryFolderResult { public *; }
-keep class org.lineageos.glimpse.home.GalleryFolderError { public *; }
-keep class org.lineageos.glimpse.home.GalleryPhoto { public *; }
-keep class org.lineageos.glimpse.home.GalleryLibrary { public *; }
-keep class org.lineageos.glimpse.home.GalleryQuery { public *; }
-keep class org.lineageos.glimpse.home.GalleryStore { public *; }
