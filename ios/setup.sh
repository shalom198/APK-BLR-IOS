#!/bin/bash
# ============================================================
# HeatMind iOS — סקריפט התקנה אוטומטי
# מוריד את XcodeGen (בלי Homebrew ובלי הרשאות admin),
# יוצר את פרויקט ה-Xcode, ופותח אותו.
# הרצה:   bash setup.sh
# ============================================================
set -e

cd "$(dirname "$0")"            # עובר לתיקיית ios/
echo "==> תיקיית עבודה: $(pwd)"

XCODEGEN_VERSION="2.42.0"
TMP="$HOME/.heatmind-xcodegen"
ZIP="$TMP/xcodegen.zip"

echo "==> מוריד את XcodeGen $XCODEGEN_VERSION ..."
mkdir -p "$TMP"
curl -L --fail -o "$ZIP" \
  "https://github.com/yonaskolb/XcodeGen/releases/download/$XCODEGEN_VERSION/xcodegen.zip"

echo "==> מחלץ ..."
rm -rf "$TMP/xcodegen"
unzip -q -o "$ZIP" -d "$TMP"

BIN="$TMP/xcodegen/bin/xcodegen"
chmod +x "$BIN" 2>/dev/null || true

echo "==> יוצר את פרויקט ה-Xcode מתוך project.yml ..."
"$BIN" generate --spec project.yml

echo "==> פותח את Xcode ..."
open HeatMind.xcodeproj

echo ""
echo "✅ סיום! Xcode אמור להיפתח עכשיו עם הפרויקט HeatMind."
echo "   בתוך Xcode:  לשונית Signing & Capabilities → בחר Team → לחץ Run (▶)."
