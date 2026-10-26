#!/usr/bin/env bash
# Essai automatique de l'appli de test sur un émulateur Android (lancé par GitHub Actions).
# Il appuie sur les boutons comme une personne, lance le service de mesure, puis vérifie
# que l'appli n'a jamais planté. Le journal de l'appli est affiché à la fin.
# Usage : bash essai-emulateur.sh sensor-maps-test.apk
set -euo pipefail
APK="$1"
P=io.github.loscripts.sensormaps.test

# Donne le centre du bouton dont le texte est donné, s'il est visible à l'écran
trouver() {
  adb shell uiautomator dump /sdcard/ecran.xml >/dev/null
  adb pull /sdcard/ecran.xml ecran.xml >/dev/null
  python3 -c '
import re, sys, xml.etree.ElementTree as ET
for n in ET.parse("ecran.xml").iter("node"):
    # Sans tenir compte des majuscules : certains thèmes écrivent les boutons en capitales
    if (n.get("text") or "").casefold() == sys.argv[1].casefold():
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
' "$1"
}

# Touche le bouton dont le texte est donné. S'il est hors de l'écran, on remonte en haut
# de la page, puis on descend petit à petit, comme avec le doigt.
toucher() {
  local xy taille l h essai
  taille=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -n 1)
  l=${taille%x*}
  h=${taille#*x}
  for essai in 1 2 3 4 5 6 7; do
    if xy=$(trouver "$1"); then
      echo "On touche « $1 »"
      adb shell input tap $xy
      return 0
    fi
    if [ "$essai" -eq 1 ]; then
      for _ in 1 2 3; do adb shell input swipe $((l / 2)) $((h * 3 / 10)) $((l / 2)) $((h * 8 / 10)) 200; done
    else
      adb shell input swipe $((l / 2)) $((h * 7 / 10)) $((l / 2)) $((h * 4 / 10)) 400
    fi
    sleep 1
  done
  echo "Bouton introuvable : $1"
  return 1
}

adb install -r "$APK"
adb shell pm grant $P android.permission.ACTIVITY_RECOGNITION
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
# Grande mémoire pour les messages système : rien ne doit être perdu pendant l'essai
adb logcat -G 16M || true
adb logcat -c

adb shell am start -W -n $P/.EcranPrincipal
sleep 5
toucher "Démarrer le test"
# Le premier réveil de 15 minutes part tout de suite : il dure 10 secondes
sleep 25
toucher "Mesurer maintenant (10 secondes)"
sleep 15

# Le service de mesure, lancé directement (sur un émulateur, adb peut le faire en mode root)
adb root >/dev/null
adb wait-for-device
sleep 3
adb shell am start-foreground-service -n $P/.ServiceMesure
sleep 15

# Redémarrage simulé : la détection d'immobilité doit se relancer
adb shell am broadcast -a android.intent.action.BOOT_COMPLETED -p $P
sleep 5

adb shell am start -W -n $P/.EcranPrincipal
sleep 3
toucher "Arrêter le test"
sleep 2
# La confirmation qui évite d'arrêter le test par erreur
toucher "Arrêter"
sleep 3

echo "===== Journal de l'appli ====="
adb logcat -d -s SensorMapsTest:I

echo "===== Vérifications ====="
journal=$(adb logcat -d -s SensorMapsTest:I)
manque=0
for attendu in "Test démarré" "Réveil 15 min" "Mesure à la main" "Mesure par le service" "immobilité relancée" "Test arrêté"; do
  if echo "$journal" | grep -q "$attendu"; then
    echo "Vu : $attendu"
  else
    echo "MANQUE : $attendu"
    manque=1
  fi
done
if adb logcat -d | grep -A 20 "FATAL EXCEPTION" | grep -q "$P"; then
  adb logcat -d | grep -A 30 "FATAL EXCEPTION"
  echo "L'appli a planté."
  exit 1
fi
if [ "$manque" -ne 0 ]; then
  echo "Une étape attendue n'apparaît pas dans le journal."
  exit 1
fi
echo "Aucun plantage, toutes les étapes sont dans le journal."
