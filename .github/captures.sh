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
# L'émulateur vient de démarrer : pas de fenêtre « … ne répond pas » par-dessus les captures,
# et le clavier de l'écran s'affiche même avec le clavier de l'ordinateur.
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher || true
adb shell settings put secure anr_show_background 0 || true
adb shell settings put global hide_error_dialogs 1 || true
adb shell settings put secure show_ime_with_hard_keyboard 1 || true
sleep 20
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
  adb shell input swipe 540 1800 540 600 600
  sleep 2
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "capture : $1"
}

# Prend la capture de l'écran tel qu'il est.
now() {
  sleep "${2:-2}"
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "capture : $1"
}

# Le tout premier lancement (vraie appli, sans démo) : la demande d'autorisation des rappels.
shot 00-premier-lancement 6
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
shot 00-premier-accueil 4

# Tous les écrans, avec le préfixe $1 dans le nom des captures.
screens() {
  local p=$1
  shot "${p}01-accueil" 5 --ez demo true --ei tab 0
  down "${p}01-accueil-bas"
  shot "${p}02-route-vocabulaire" 4 --ez demo true --ei tab 1 --ei line 0
  shot "${p}03-route-compliquees" 4 --ez demo true --ei tab 1 --ei line 2
  shot "${p}04-lexique" 4 --ez demo true --ei tab 2
  shot "${p}05-badges" 4 --ez demo true --ei tab 3
  down "${p}05-badges-bas"
  shot "${p}06-reglages" 4 --ez demo true --es demoStep SETTINGS

  shot "${p}07-lecon" 4 --ez demo true --es demoStep LESSON
  down "${p}07-lecon-bas"
  shot "${p}08-nouveau-mot" 4 --ez demo true --es demoStep NEW
  shot "${p}09-choisir" 4 --ez demo true --es demoStep CHOOSE_CEB
  shot "${p}10-choisir-juste" 5 --ez demo true --es demoStep CHOOSE_CEB --es demoAnswer right
  shot "${p}11-choisir-faux" 5 --ez demo true --es demoStep CHOOSE_FR --es demoAnswer wrong
  shot "${p}12-trou" 4 --ez demo true --es demoStep GAP
  shot "${p}13-relier" 5 --ez demo true --es demoStep MATCH --es demoAnswer partial
  shot "${p}14-ordre" 4 --ez demo true --es demoStep ORDER
  shot "${p}15-ordre-juste" 5 --ez demo true --es demoStep ORDER --es demoAnswer right
  shot "${p}16-ecrire" 5 --ez demo true --es demoStep WRITE
  shot "${p}17-ecrire-presque" 5 --ez demo true --es demoStep WRITE --es demoAnswer typo
  shot "${p}18-ecrire-faux" 5 --ez demo true --es demoStep WRITE --es demoAnswer wrong

  # La fenêtre « Arrêter la séance ? »
  shot "${p}19-arreter" 4 --ez demo true --es demoStep CHOOSE_FR
  adb shell input keyevent KEYCODE_BACK
  now "${p}19-arreter"
}

screens ""

# Le rappel du soir dans les notifications
shot 20-notification 3 --ez demo true --es demoStep NOTIF
adb shell cmd statusbar expand-notifications
now 20-notification 3
adb shell cmd statusbar collapse

# L'écran de fin : il termine la journée de démo (3 séances sur 3).
shot 21-fin 5 --ez demo true --es demoStep END
down 21-fin-bas

# Un petit téléphone (360 dp de large, comme beaucoup de téléphones)
adb shell wm density 480
sleep 3
screens "petit-"
shot petit-21-fin 5 --ez demo true --es demoStep END
down petit-21-fin-bas
adb shell wm density reset

adb logcat -d -b crash > "$OUT/plantages.txt" || true
adb logcat -d -s AndroidRuntime:E > "$OUT/erreurs.txt" || true
ls -la "$OUT"
