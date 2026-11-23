# Protocole Bluetooth de la Hohem iSteady V3

Déduit des journaux de Hohem Joy 2.00.10 (bibliothèque native `liblink`, « blink V1.2.2.007 »), sur deux captures :
la première avec la télécommande de la nacelle, la seconde avec le suivi de sujet de l'appli.

## Liaison

- Bluetooth LE. La nacelle s'annonce sous un nom de la forme `SV3-XXXXXXX`.
- Notifications sur les caractéristiques `FFE2` et `FFE4` (service `FFE0`).
- Écriture sur une caractéristique de la même famille `FFEx` (Topp la détecte d'après ses propriétés).
- Une seule appli à la fois : fermer Hohem Joy avant d'utiliser Topp.

## Format des trames

```
AA 55 | LEN | 00 | SRC | CMD | DONNÉES… | CHK | FF
```

- `LEN` = nombre d'octets après `AA 55`, `FF` compris (= taille des données + 6).
- `CHK` = somme des octets de `LEN` jusqu'au dernier octet de données, modulo 256.
- `SRC` : `00` nacelle ou appli, `07` état de la nacelle, `A1` télécommande.

Règle vérifiée sur toutes les trames capturées (189 dans la première capture, plus de 1 300 dans la seconde).

## Commandes envoyées à la nacelle

| CMD | Données | Rôle |
|-----|---------|------|
| `49` | aucune | État (la nacelle en envoie aussi une chaque seconde) |
| `84` | aucune | Angles des moteurs (voir plus bas) |
| `57` | aucune | Informations, dont le mode de travail |
| `7C` | `00 00 <mode> 01` | Changer de mode. Accusé : `AA 55 06 00 00 7C 82 FF` |
| `60` | voir plus bas | Suivi : position du cadre dans l'image |
| `34 00`, `35`, `53`, `54`, `56`, `9A …`, `C1 03 …` | | Requêtes envoyées par Hohem Joy à la connexion |

Ordre des modes avec le bouton de la nacelle : `1 → 3 → 2 → 0 → 1`.

### Suivi (`60`)

Environ 33 trames par seconde pendant le suivi. Données : `01 02 03`, un mot de 40 bits big-endian, puis 4 octets à zéro.

```
pan (10 bits, signé) | tilt (10 bits, signé) | 0000 | h (8 bits) | v (8 bits)
```

- `pan = trunc(-x × 510)`, `tilt = trunc(y × 510)` : décalage du centre du cadre par rapport au centre de l'image.
- `h = trunc(largeur × 255)`, `v = trunc(hauteur × 255)`.
- Fin du suivi : `01 02 03` suivi de 9 octets à zéro.

L'encodeur officiel masque le tilt sur 12 bits au lieu de 10, ce qui force à 1 les deux bits de poids faible du pan quand le tilt est négatif. Avec ce défaut reproduit, Topp retrouve exactement les 1 312 trames capturées ; il envoie lui-même la version correcte.

## Messages de la nacelle

| CMD | Données | Sens |
|-----|---------|------|
| `84` | 6 × int16 big-endian, en 0,1° | Gyroscope tilt/roll/pan, puis codeurs tilt/roll/pan, puis couples |
| `57` | 16 octets | L'octet d'index 14 est le mode de travail |
| `A5` | `01` | Appui sur le déclencheur |
| `B0` | `01` / `02` | Joystick de la télécommande touché / relâché (sans direction) |
| `B1` | … | Demande de recentrage depuis la nacelle |
| `98` | `01` … | Recentrage terminé |
| `4A` | `01` / `02` | Passage avant-plan / arrière-plan |

Aucune commande « recentrer » envoyée par l'appli n'a été observée. Topp recentre donc en lisant les angles des codeurs et en ramenant la caméra vers sa position de repos avec la commande de suivi.
