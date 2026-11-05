# Soleil & Batterie

Appli Android avec widget : production solaire prévue (2 champs de panneaux d'orientation différente) et recharge estimée de la batterie. Données météo : [Open-Meteo](https://open-meteo.com) (gratuit, sans clé).

## Installer sur le téléphone

À chaque modification poussée sur `main`, GitHub compile l'APK tout seul (onglet **Actions**, ~5 min).

Lien direct vers la dernière version :
`https://github.com/<ton-compte>/soleil-batterie/releases/latest/download/SoleilBatterie.apk`

1. Ouvre ce lien dans Chrome sur le téléphone.
2. Ouvre le fichier téléchargé et autorise l'installation depuis Chrome.
3. Lance l'appli une fois, vérifie les réglages, puis appui long sur l'écran d'accueil → Widgets → Soleil & Batterie.

## Calcul

`kWh = (irradiance champ 1 × kWc 1 + irradiance champ 2 × kWc 2) × 0,82 × facteur de correction`

La batterie se remplit avec ce qui reste après la conso de la maison en journée. Le facteur de correction se règle en entrant une production réelle mesurée.
