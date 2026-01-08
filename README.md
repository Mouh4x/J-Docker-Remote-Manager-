# J-Docker Remote Manager

Projet GTR 3 : gestion distante d'un moteur Docker via une architecture distribuée Java (daemon serveur + client CLI).

# AUTEURS DU GROUPE :
- MOUHAMMAD Hassani Hamadi
- DJIGUEMDE Norbert

## 1. Objectif du projet

L'objectif est de piloter Docker à distance à travers une **console Java** simplifiée, sans utiliser directement la CLI `docker`. 

- Le **serveur Java** (daemon) tourne sur la machine où Docker est installé.
- Le **client Java CLI** s'y connecte via TCP et envoie des commandes haut niveau.
- Le serveur traduit ces commandes en appels à l'API Docker (via la bibliothèque `docker-java`) et renvoie des réponses structurées.

## 2. Architecture générale

### 2.1. Vue d'ensemble

- Client CLI Java : `com.jdocker.client.DockerClientCLI`
- Serveur Java (daemon) : `com.jdocker.server.DockerServer`
- Gestion Docker : `com.jdocker.server.DockerService`
- Modèle de messages : `com.jdocker.common.Request` / `com.jdocker.common.Response`

Flux :

1. Le **client** ouvre une socket TCP vers le serveur (par défaut `host=127.0.0.1`, `port=5000`).
2. L'utilisateur saisit une commande textuelle (`images`, `run nginx web1`, `logs web1`, ...).
3. Le client transforme cette commande en **JSON** (`Request`) et l'envoie au serveur (une ligne JSON par requête).
4. Le **serveur** lit la ligne JSON, la parse avec Jackson en `Request`, puis route vers `DockerService`.
5. `DockerService` appelle l'API Docker via `docker-java` (en se connectant à `tcp://localhost:2375`).
6. Le serveur renvoie une `Response` JSON au client.
7. Le client affiche un message lisible pour l'utilisateur.

### 2.2. Architecture réseau

- **Client ↔ Serveur Java** :
  - Protocole : TCP
  - Port : `5000`
  - Messages : JSON ligne par ligne

- **Serveur Java ↔ Docker Engine** :
  - Protocole : HTTP sur TCP (via `docker-java`)
  - Hôte : `tcp://localhost:2375`
  - Nécessite d'exposer le daemon Docker sur ce port.

Cette architecture répond directement au **Conseil n°1** de l'énoncé : ne pas utiliser directement le socket Unix/Windows, mais configurer Docker pour écouter sur un port TCP.

## 3. Protocole d'échange JSON

### 3.1. Format des requêtes (client → serveur)

Une requête est un objet JSON sérialisé dans la classe `Request` :

```json
{
  "action": "NOM_DE_L_ACTION",
  "payload": "{ ... JSON dépendant de l'action ... }"
}
```

- `action` : chaîne qui identifie l'opération demandée.
- `payload` : chaîne JSON (optionnelle) contenant les paramètres.

Les messages sont envoyés **une par ligne**, terminée par `\n`.

### 3.2. Format des réponses (serveur → client)

Le serveur renvoie des objets `Response` sérialisés en JSON :

```json
{
  "status": "OK" | "ERROR",
  "message": "Texte lisible",
  "data": "{ ... JSON métier ... }" | null
}
```

- `status` :
  - `OK` : la commande s'est déroulée correctement.
  - `ERROR` : une erreur est survenue (mauvais paramètres, erreur Docker, conteneur introuvable, etc.).
- `message` : résumé lisible.
- `data` : JSON (sous forme de chaîne) avec les données utiles (liste d'images, conteneurs, etc.).

### 3.3. Actions supportées

#### LIST_IMAGES

- **But** : lister les images Docker disponibles sur l'hôte.
- **Requête** :
  ```json
  { "action": "LIST_IMAGES", "payload": null }
  ```
- **Réponse (data)** :
  ```json
  {
    "images": [
      {
        "repository": "ubuntu",
        "tag": "latest",
        "id": "sha256:...",
        "size": 123.4
      },
      ...
    ]
  }
  ```

#### LIST_CONTAINERS

- **But** : lister les conteneurs (tous, y compris arrêtés).
- **Requête** :
  ```json
  { "action": "LIST_CONTAINERS", "payload": null }
  ```
- **Réponse (data)** :
  ```json
  {
    "containers": [
      {
        "id": "...",
        "name": "/monnginx",
        "image": "nginx:latest",
        "state": "running" | "exited" | "created" | "paused"
      },
      ...
    ]
  }
  ```

#### PULL_IMAGE

- **But** : télécharger une image officielle depuis Docker Hub.
- **Requête** :
  ```json
  {
    "action": "PULL_IMAGE",
    "payload": "{\"image\":\"nginx\",\"tag\":\"latest\"}"
  }
  ```
- **Réponse (data)** :
  ```json
  { "image": "nginx", "tag": "latest", "status": "pulled" }
  ```

En cas d'image introuvable / erreur réseau, `status = "ERROR"` et `message` contient la cause.

#### RUN_CONTAINER

- **But** : créer **et démarrer** un conteneur (comportement proche de `docker run`).
- **Requête** :
  ```json
  {
    "action": "RUN_CONTAINER",
    "payload": "{\"image\":\"nginx\",\"name\":\"monnginx2\"}"
  }
  ```
- **Réponse (data)** :
  ```json
  {
    "id": "<container-id>",
    "name": "monnginx2",
    "image": "nginx",
    "status": "running"
  }
  ```

#### STOP_CONTAINER

- **But** : arrêter proprement un conteneur.
- **Requête** :
  ```json
  {
    "action": "STOP_CONTAINER",
    "payload": "{\"idOrName\":\"monnginx2\"}"
  }
  ```
- **Réponse (data)** :
  ```json
  { "id": "<container-id>", "status": "stopped" }
  ```

Si le conteneur n'est pas en cours d'exécution, Docker peut renvoyer un code `304` (Not Modified) qui est propagé sous forme d'erreur lisible.

#### REMOVE_CONTAINER

- **But** : supprimer un conteneur (qu'il soit arrêté ou non, avec `force=true`).
- **Requête** :
  ```json
  {
    "action": "REMOVE_CONTAINER",
    "payload": "{\"idOrName\":\"monnginx2\"}"
  }
  ```
- **Réponse (data)** :
  ```json
  { "id": "<container-id>", "status": "removed" }
  ```

#### STREAM_LOGS

- **But** : streamer les logs d'un conteneur en temps réel.
- **Requête** :
  ```json
  {
    "action": "STREAM_LOGS",
    "payload": "{\"idOrName\":\"monnginx2\"}"
  }
  ```
- **Réponses** :
  - Accusé de réception :
    ```json
    { "status": "OK", "message": "Log streaming started", "data": null }
    ```
  - Puis, pour chaque ligne de log :
    ```json
    { "status": "OK", "message": "LOG_LINE", "data": "<ligne de log>" }
    ```

## 4. Commandes du client CLI

Le client CLI (`DockerClientCLI`) fournit une interface texte simple.

### 4.1. Lancement du client

Depuis la racine du projet :

```bash
mvn exec:java -Dexec.mainClass="com.jdocker.client.DockerClientCLI"
```

Par défaut, il se connecte à `127.0.0.1:5000`. On peut aussi spécifier un autre hôte/port :

```bash
mvn exec:java -Dexec.mainClass="com.jdocker.client.DockerClientCLI" -Dexec.args="192.168.1.10 5000"
```

### 4.2. Liste des commandes

- `images`
  - Liste les images Docker disponibles.

- `containers`
  - Liste les conteneurs (tous les états).

- `pull <image>[:tag]`
  - Exemples :
    - `pull ubuntu:latest`
    - `pull nginx`

- `run <image> <name>`
  - Crée **et démarre** un conteneur.
  - Exemple : `run nginx monnginx2`.

- `stop <nameOrId>`
  - Arrête proprement le conteneur.
  - Exemple : `stop monnginx2`.

- `rm <nameOrId>`
  - Supprime le conteneur.
  - Exemple : `rm monnginx2`.

- `logs <nameOrId>`
  - Stream en temps réel les logs du conteneur.
  - Exemple : `logs monnginx2`.

- `exit`
  - Quitte le client.

## 5. Mise en place et exécution

### 5.1. Prérequis

- **Docker Desktop** installé sur Windows.
- **Java 17** (ou compatible) et **Maven** installés.
- Docker configuré pour exposer l'API sur `tcp://localhost:2375` **sans TLS**.

Sur Docker Desktop, cela correspond à cocher :

> Settings → General → "Expose daemon on tcp://localhost:2375 without TLS"

ou via le fichier de configuration Docker Engine (suivant la version).

### 5.2. Build du projet

À la racine du projet :

```bash
mvn clean package
```

### 5.3. Lancement du serveur

Dans un premier terminal :

```bash
mvn exec:java -Dexec.mainClass="com.jdocker.server.DockerServer"
```

Sortie attendue :

```text
[SERVER] Listening on port 5000
```

Le serveur est multithreadé : chaque connexion client est gérée par un `ClientHandler` dédié.

### 5.4. Lancement du client

Dans un deuxième terminal :

```bash
mvn exec:java -Dexec.mainClass="com.jdocker.client.DockerClientCLI"
```

Exemple de scénario complet :

```text
jdocker> images
jdocker> pull nginx:latest
jdocker> run nginx monnginx2
jdocker> containers
jdocker> logs monnginx2
jdocker> stop monnginx2
jdocker> rm monnginx2
jdocker> exit
```

## 6. Gestion des erreurs et robustesse

### 6.1. Erreurs Docker

Les erreurs remontées par Docker (image inexistante, nom de conteneur déjà utilisé, conteneur introuvable, statut 304/409, etc.) sont capturées par le serveur :

- Le serveur ne plante pas.
- Il renvoie une `Response` avec :
  - `status = "ERROR"`
  - `message` contenant la cause (ex. "Container not found: ...", "The container name is already in use", etc.).

Côté client, ces messages sont affichés clairement.

### 6.2. Déconnexions clientes

- Si le client se déconnecte brutalement (Ctrl+C, fermeture du terminal), le thread `ClientHandler` associé se termine proprement.
- Le serveur principal (`DockerServer`) continue d'écouter et peut accepter d'autres clients.

### 6.3. Logs en streaming

- Le streaming utilise un **thread dédié** par requête `STREAM_LOGS`.
- En cas d'erreur réseau ou de fermeture de la socket, le thread est simplement terminé.

## 7. Aspects réseau et sécurité

### 7.1. Port 5000 (serveur Java)

- Le serveur Java écoute sur `0.0.0.0:5000`.
- Toute machine du même réseau (si le pare-feu l'autorise) peut se connecter au serveur et piloter Docker via le client CLI.

### 7.2. Port 2375 (API Docker)

- L'API Docker est exposée sur `tcp://localhost:2375` **sans TLS**, ce qui est pratique pour un projet de TP mais dangereux en production.
- En environnement réel, il faudrait :
  - restreindre l'écoute à `127.0.0.1:2375` ou
  - activer TLS/authentification sur l'API Docker ou
  - filtrer par pare-feu.

Dans ce projet, cette configuration est assumée comme un choix pédagogique pour simplifier la communication serveur ↔ Docker conformément à l'énoncé.

---

Ce README peut servir de base au rapport écrit :
- description de l'architecture,
- protocole d'échange,
- cycle de vie des conteneurs,
- aspects réseau (latence, sécurité),
- et validation fonctionnelle (scénarios de test).
