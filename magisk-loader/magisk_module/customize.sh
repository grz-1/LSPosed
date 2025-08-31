#
# This file is part of LSPosed.
#
# LSPosed is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# LSPosed is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with LSPosed.  If not, see <https://www.gnu.org/licenses/>.
#
# Copyright (C) 2020 EdXposed Contributors
# Copyright (C) 2021 LSPosed Contributors
#

# shellcheck disable=SC2034
SKIPUNZIP=1

enforce_install_from_magisk_app() {
  if $BOOTMODE; then
    ui_print "- Installing from Magisk app"
  else
    ui_print "*********************************************************"
    ui_print "! Install from recovery is NOT supported"
    ui_print "! Some recovery has broken implementations, install with such recovery will finally cause Riru or Riru modules not working"
    ui_print "! Please install from Magisk app"
    abort "*********************************************************"
  fi
}

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- LSPosed version ${VERSION}"

# Extract verify.sh
ui_print "- Extracting verify.sh"
unzip -o "$ZIPFILE" 'verify.sh' -d "$TMPDIR" >&2
if [ ! -f "$TMPDIR/verify.sh" ]; then
  ui_print "*********************************************************"
  ui_print "! Unable to extract verify.sh!"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
fi
. "$TMPDIR/verify.sh"

# Base check
do_extract false "$ZIPFILE" 'customize.sh' "$TMPDIR"
do_extract false "$ZIPFILE" 'verify.sh' "$TMPDIR"
do_extract false "$ZIPFILE" 'util_functions.sh' "$TMPDIR"
. "$TMPDIR/util_functions.sh"
check_android_version
if [ -z "$APATCH" ] && [ -z "$KSU" ]; then
  check_magisk_version
fi
check_incompatible_module

enforce_install_from_magisk_app

# Check architecture
if [ "$ARCH" != "arm" ] && [ "$ARCH" != "arm64" ] && [ "$ARCH" != "x86" ] && [ "$ARCH" != "x64" ] && [ "$ARCH" != "riscv64" ]; then
  abort "! Unsupported platform: $ARCH"
else
  ui_print "- Device platform: $ARCH"
fi

rm -f /data/adb/lspd/manager.apk

# Extract libs
ui_print "- Extracting module files"

extract "machizako.$ARCH" "" "machizako"

mkdir -p "$MODPATH/zygisk"
mkdir -p "$MODPATH/lib"
mkdir -p "$MODPATH/bin"

ABI32=""
ABI64=""
if [ "$ARCH" = "arm" ]; then
  ABI32="armeabi-v7a"
  PRIMARY_ABI=$ABI32
elif [ "$ARCH" = "arm64" ]; then
  ABI32="armeabi-v7a"
  ABI64="arm64-v8a"
  PRIMARY_ABI=$ABI64
elif [ "$ARCH" = "x86" ]; then
  ABI32="x86"
  PRIMARY_ABI=$ABI32
elif [ "$ARCH" = "x64" ]; then
  ABI32="x86"
  ABI64="x86_64"
  PRIMARY_ABI=$ABI64
elif [ "$ARCH" = "riscv64" ]; then
  ABI64="riscv64"
  PRIMARY_ABI=$ABI64
fi

extract 'module.prop'
extract 'action.sh'
extract 'post-fs-data.sh'
extract 'uninstall.sh'
extract 'sepolicy.rule'
extract 'framework/lspd.dex'
extract 'daemon.apk'
extract 'daemon'
extract 'lspd'
extract 'manager.apk'
extract "mazako"

ui_print "- Extracting libraries"

if [ -n "$ABI64" ]; then
  extract "lib/$ABI64/liblspd.so" "zygisk" "$ABI64.so"
  extract "lib/$ABI64/libpreload.so" "lib" "libpreload64.so"
fi

if [ -n "$ABI32" ]; then
  extract "lib/$ABI32/liblspd.so" "zygisk" "$ABI32.so"
  extract "lib/$ABI32/libpreload.so" "lib" "libpreload32.so"
fi

ui_print "- Extracting dex2oat binary"
extract "bin/$PRIMARY_ABI/dex2oat" "bin"

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm_recursive "$MODPATH/bin" 0 2000 0755 0755
chmod 0744 "$MODPATH/daemon"
chmod 0744 "$MODPATH/lspd"

ln -s "post-fs-data.sh" "$MODPATH/post-mount.sh"

ui_print "- Welcome to LSPosed!"
