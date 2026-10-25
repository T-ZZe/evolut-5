# SkillTree 2.0 (Paper 26.3)

5 voies × 3 paliers : **Vitesse, Force, Résistance, Fermier, Camouflage**.
Le dernier palier de chaque voie débloque une capacité active, utilisable par clic droit avec le Sceptre.

| Voie | Capacité (palier 3) |
|---|---|
| Vitesse | **Dash** : 3 utilisations / 30 s |
| Force | **Onde de choc** : dégâts + repoussement autour de toi, puis Force III 8 s (+ Wither 10 % en frappant) |
| Résistance | **Bastion** : Résistance III 6 s (+ 3 cœurs permanents) |
| Fermier | **Bénédiction de la Terre** : toutes les cultures autour de toi arrivent à maturité |
| Camouflage | **Voile d'ombre** : invisibilité 12 s, les monstres t'oublient, frapper rompt le voile |

Passifs Camouflage : palier 1 = vision nocturne + accroupi, les monstres ne te voient que de près ;
palier 2 = accroupi et immobile 3 s => invisible.

## Compiler
Prérequis : JDK 25 + Maven.
    mvn package
Le jar est dans `target/SkillTree-2.0.jar` -> à mettre dans `plugins/`.

## Resource pack intégré
Le pack est dans le jar (`SkillTreePack.zip`). Au premier lancement, ouvre `plugins/SkillTree/config.yml` :
- `resource-pack.host` : IP publique ou domaine du serveur
- `resource-pack.port` : port TCP (8123 par défaut) à ouvrir sur le pare-feu / la box
Le plugin héberge alors le pack lui-même et l'envoie à chaque connexion (`/skills pack` pour le renvoyer).
Sans hébergement possible : `mode: external` + `external-url`.
Sans le pack, tout fonctionne avec les icônes vanilla.

## Commandes
/skills, /skills wand, /skills points, /skills pack
Admin (skills.admin) : /skills give <joueur> <n>, /skills reset <joueur>, /skills reload
