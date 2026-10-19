#!/usr/bin/env bash
# Captures d'écran de BiBisaya sur un émulateur, en mode démo (une fausse progression de 12 jours).
# Lancé par GitHub Actions : bash .github/captures.sh BiBisaya.apk captures
set -u
APK=${1:-BiBisaya.apk}
OUT=${2:-captures}
PKG=com.thomas.bibisaya
mkdir -p "$OUT"

adb wait-for-device
adb install -r "$APK"
adb shell svc power stayon true
adb shell wm dismiss-keyguard || true
adb logcat -c || true

# Ouvre l'appli (en partant de zéro), attend, puis prend la capture.
shot() {
  local name=$1 wait=$2
  shift 2
  adb shell am start -S -W -n "$PKG/.MainActivity" "$@" > /dev/null
  sleep "$wait"
  adb exec-out screencap -p > "$OUT/$name.png"
  echo "capture : $name"
}

# Fait défiler l'écran vers le bas, puis prend la capture.
down() {
  adb shell input swipe 540 1900 540 700 400
  sleep 2
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "capture : $1"
}

# Le tout premier lancement (vraie appli, sans démo) : la demande d'autorisation des rappels.
shot 00-premier-lancement 5
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
shot 00-premier-accueil 4

shot 01-accueil 5 --ez demo true --ei tab 0
down 01-accueil-bas
shot 02-route-vocabulaire 4 --ez demo true --ei tab 1 --ei line 0
shot 03-route-compliquees 4 --ez demo true --ei tab 1 --ei line 2
shot 04-lexique 4 --ez demo true --ei tab 2
shot 05-badges 4 --ez demo true --ei tab 3
shot 06-reglages 4 --ez demo true --es demoStep SETTINGS

shot 07-lecon 4 --ez demo true --es demoStep LESSON
down 07-lecon-bas
shot 08-nouveau-mot 4 --ez demo true --es demoStep NEW
shot 09-choisir 4 --ez demo true --es demoStep CHOOSE_CEB
shot 10-choisir-juste 5 --ez demo true --es demoStep CHOOSE_CEB --es demoAnswer right
shot 11-choisir-faux 5 --ez demo true --es demoStep CHOOSE_FR --es demoAnswer wrong
shot 12-trou 4 --ez demo true --es demoStep GAP
shot 13-relier 5 --ez demo true --es demoStep MATCH --es demoAnswer partial
shot 14-ordre 4 --ez demo true --es demoStep ORDER
shot 15-ordre-juste 5 --ez demo true --es demoStep ORDER --es demoAnswer right
shot 16-ecrire 5 --ez demo true --es demoStep WRITE
shot 17-ecrire-presque 5 --ez demo true --es demoStep WRITE --es demoAnswer typo
shot 18-ecrire-faux 5 --ez demo true --es demoStep WRITE --es demoAnswer wrong

# La fenêtre « Arrêter la séance ? »
shot 19-arreter 4 --ez demo true --es demoStep CHOOSE_FR
adb shell input keyevent KEYCODE_BACK
sleep 2
adb exec-out screencap -p > "$OUT/19-arreter.png"

# Le rappel du soir dans les notifications
shot 20-notification 3 --ez demo true --es demoStep NOTIF
adb shell cmd statusbar expand-notifications
sleep 3
adb exec-out screencap -p > "$OUT/20-notification.png"
adb shell cmd statusbar collapse

# L'écran de fin en dernier : il termine la journée de démo.
shot 21-fin 5 --ez demo true --es demoStep END
down 21-fin-bas

adb logcat -d -b crash > "$OUT/plantages.txt" || true
adb logcat -d -s AndroidRuntime:E > "$OUT/erreurs.txt" || true
ls -la "$OUT"
