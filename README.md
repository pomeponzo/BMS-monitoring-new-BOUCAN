# Boucan battery monitor

Application **Android native**, en français et sans compte ni service cloud, pour consulter en BLE les données du BMS `DL-BOUCAN battery`. Projet ouvrable avec **Android Studio**.

> **Statut : version initiale 0.1 / lecture seule.** La compatibilité matérielle doit être validée sur la batterie réelle. Aucun paramètre de sécurité, état MOSFET ni seuil de protection n'est modifié.

## Ce que l’application montre
- État de charge (SOC), capacité restante et capacité nominale **configurée dans le BMS** ;
- tension du pack, courant et puissance, températures ;
- quatre cellules ou tout autre nombre communiqué par le BMS, et écart en mV ;
- état des autorisations de charge/décharge, indicateurs d'alarme bruts ;
- courbe de tension des 90 derniers relevés (mémoire vive, non persistante) ;
- sélection manuelle du BMS BLE depuis Android.

Il **n'y a pas de valeurs factices** à l'écran en l'absence de liaison BLE. L'application cesse d’interroger le BMS à la déconnexion et n'effectue pas d'acquisition en arrière-plan.

## Installer depuis Android Studio

1. Ouvrir ce dépôt dans Android Studio : **File → Open** et choisir le dossier du projet.
2. Utiliser un SDK Android 35 et un JDK 17. Le projet utilise Android Gradle Plugin 8.9.2, Kotlin 2.1.20 et Gradle 8.11.1.
3. Le dépôt inclut le **Gradle Wrapper 8.11.1** (`gradlew`, `gradlew.bat` et le JAR) : Android Studio peut synchroniser le projet sans installation Gradle séparée.
4. Synchroniser Gradle, puis **Build → Build APK(s)** ; résultat `app/build/outputs/apk/debug/app-debug.apk`.
5. Transférer cet APK vers ton téléphone, autoriser l’installation depuis la source utilisée, puis installer. Ou utiliser **Run** avec le débogage USB.
6. Fermer/déconnecter l’application du fabricant (une seule application peut généralement utiliser le canal GATT à la fois). Activer Bluetooth et accorder « Appareils à proximité ». Sur Android 8–11, autoriser la localisation pour le scan BLE.
7. Appuyer sur **CONNECTER** et choisir `DL-BOUCAN battery` dans la liste.

**Sans Android Studio** : la CI GitHub Actions fabrique automatiquement un APK de débogage. Dans l’onglet **Actions → Android · build & tests**, ouvrir une exécution réussie et télécharger l’artefact `Boucan-battery-monitor-debug-apk`. Les APK de débogage sont installables pour essai, mais ne sont pas des builds de publication signés pour le Play Store.

## Protocole effectivement observé (capture Android du 23/11/2026)

| Fonction | Valeur |
|---|---|
| Nom BLE | `DL-BOUCAN battery` |
| Service GATT | `0000fff0-0000-1000-8000-00805f9b34fb` |
| Notifications | `0000fff1-0000-1000-8000-00805f9b34fb` |
| Requêtes | `0000fff2-0000-1000-8000-00805f9b34fb` |
| Lecture du statut, 62 registres | `D2 03 00 00 00 3E D7 B9` |
| Lecture paramètres, 41 registres | `D2 03 00 80 00 29 96 5F` |
| Trames | Modbus RTU, CRC-16 Modbus, big-endian pour les registres |

Les notifications BLE peuvent fragmenter les réponses Modbus : le `ModbusFrameAssembler` reconstitue la trame et vérifie le CRC avant décodage.

Offsets de données de statut (dans **124 octets** de réponse après le préfixe `D2 03 7C`) : cellules +0,+2,+4,+6 en mV ; sondes à +64,+66 en °C = brut − 40 ; tension +80 ÷10 ; courant +82 = (brut − 30000) ÷10 ; SOC +84 ÷10 ; capacité restante +96 ÷10 ; nombre de cellules +98 ; nombre de sondes +100 ; cycles +102 ; MOS charge +106 ; MOS décharge +108 ; alarmes +116…123.

Une réponse de paramètres à partir de `0x0080` permet de lire une capacité configurée exprimée en dixièmes d'Ah. La capture précédente donnait **280 Ah configurés**, alors que la batterie avait été annoncée à **300 Ah**. L'app affiche ce qu'elle reçoit, sans corriger ni modifier la valeur.

**Limites :** un seul modèle/protocole de batterie a été étudié. La présence de `FFF0/FFF1/FFF2` n'est pas suffisante pour garantir le décodage d'un autre BMS. Les bits d'alarme sont affichés en hexadécimal lorsqu'ils ne sont pas nuls, sans inventer de libellés. Cette application n'est pas une instrumentation de sécurité indépendante.

## Direction visuelle

Identité **instrument nautique / atelier** plutôt qu'un kit de tableau de bord génératif : encre pin, fond craie, accent terre cuite réservé aux valeurs et états significatifs, typographie système **condensée + monospacée**, alignements fermes, séparateurs fins, un seul panneau plein pour le SOC. Pas de dégradés violets, de verre dépoli, de cartes uniformément arrondies, ni d'icône monogramme. L'icône originale représente quatre cellules et une ligne de courant.

Cette direction évite plusieurs motifs répétitifs fréquemment attribués aux interfaces générées automatiquement (empilement de cartes à ombres douces, pastilles et gradients, Inter/Poppins par défaut). Ce sont des conventions visuelles, **pas une méthode permettant de prouver l'origine d'un design**.

## Architecture

```
app/src/main/java/nc/boucan/batterymonitor/
├── MainActivity.kt       Permissions et sélection BLE
├── BmsBleClient.kt       GATT, CCCD, cadence des lectures (5 s)
├── BmsProtocol.kt        CRC, reconstitution, décodage des registres
└── DashboardUi.kt        Interface, graphe, états sans données simulées
```

La couche protocole ne dépend pas de l'interface. Elle pourra ensuite être partagée conceptuellement avec un lecteur Raspberry Pi.

## Tester

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Les tests unitaires vérifient le CRC de la commande issue de la capture, les champs de mesures, la reconstruction des réponses découpées ou concaténées, le rejet de CRC invalide et la capacité nominale.

## Confidentialité et sécurité

L'application n'a **pas** d'autorisation réseau. Les mesures restent sur le téléphone ; seules les 90 dernières tensions restent en mémoire. Ne pas modifier le BMS pendant la mise au point. Les captures HCI Android ne sont pas stockées dans ce dépôt public.
