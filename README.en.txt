DBGUARDIAN
Open source database monitor, agentless
Author: Quirino Scaramastra

DBGuardian is a monitor for MySQL, MariaDB, PostgreSQL, Microsoft SQL Server and MongoDB.
It installs nothing on the monitored servers: it connects with database drivers, in read-only
mode, and collects what the database exposes.

Experimental lab, Alpha version. No support, no warranty, no scheduled maintenance.
Do not use it in production without testing.


WHAT IT DOES
------------

The application interface is in Italian: labels are quoted as they appear in the application.

- Dashboard with all servers, clusters, replicas and standalone servers, with a status for each.
- InnoDB and Galera clusters: primary node, members and, when a cluster is down, the candidate
  node for recovery.
- MongoDB: replica set status, lag, oplog window, long-running operations, cache and connections.
- Daily Insight: a score from 0 to 100 for each server, with checks, results and recommendations.
  The checks you can select depend on the server technology.
- On-demand AI functions and an optional AI comment on the daily report, with Anthropic, OpenAI,
  both, or a local model through Ollama. The AI functions only produce text: they do not run
  anything on the databases.
- Telegram notifications for events, with acknowledgement.
- Database search by name across all visible servers.
- Execution plan (EXPLAIN) of slow queries, without running the query.
- Optional host metrics (CPU and memory), read from node_exporter.
- "Termina sessione" button for blocking transactions, with a separate user that has only the
  permission to terminate sessions (see PERMESSI.en.txt).
- Per-server configuration: checks to run, environment, site, participation in Insight.
  A server with "Sospendi monitoraggio" is not connected at all.
- Users with profiles and assigned servers.

It does not modify the monitored databases. Operating system metrics are read only if you
enter the node_exporter address in the server settings.


INSTALLATION AND START
----------------------

1. Check the requirements in PREREQUISITES.en.txt.

2. Start with the script for your system, from the project folder.

   Linux and macOS:

       ./avvia.sh

   Windows (PowerShell, with Docker Desktop running):

       .\avvia.ps1

   The first time, the script creates the .env file from .env.example, generates
   APP_SECRET_KEY and starts the containers with "docker compose up -d --build".

   APP_SECRET_KEY encrypts the passwords of the monitored servers and the API keys. It is not
   stored in the database: if it is lost, the saved passwords can no longer be read. Keep a copy
   in a safe place, and do not change it after the first start.

3. Open http://localhost:8080 and sign in with:

       user: admin
       password: admin

   At the first sign-in the monitor requires you to change the password.
   The DB_PASSWORD and DB_ROOT_PASSWORD values (in .env) are the passwords of the monitor
   database. The defaults "pippo" and "admin" are only meant for a local lab: change them
   before any use beyond a test.


WHERE THE DATA IS
-----------------

The monitor database uses the Docker volume db_data. The data is neither in the project
folder nor inside the container: Docker keeps it separately, so it survives when the
container is recreated or updated.

    docker compose down          stops and removes the containers, data is kept
    docker compose down -v       also removes the volume: all data is deleted

Volume backup (Linux and macOS):

    docker run --rm -v dbguardian_db_data:/dati -v "$PWD":/backup alpine tar czf /backup/dbguardian-db.tgz -C /dati .

Volume backup (Windows, PowerShell):

    docker run --rm -v dbguardian_db_data:/dati -v "${PWD}:/backup" alpine tar czf /backup/dbguardian-db.tgz -C /dati .

The volume name depends on the project folder: if the folder is called dbguardian,
the volume is dbguardian_db_data. Check it with "docker volume ls".

Restore: with the monitor stopped, recreate the volume and extract the archive into it with
the same command, using "tar xzf /backup/dbguardian-db.tgz -C /dati".


STOPPING
--------

    docker compose down


ADDING A SERVER
---------------

In the "Server da monitorare" page (administrator only):

- name, type (MySQL or MariaDB, PostgreSQL, SQL Server, MongoDB, router, monitor database);
- host and port reachable from the monitor container (MongoDB uses 27017 by default);
- a read-only user (see PERMESSI.en.txt);
- environment, site and, if any, the Galera cluster;
- the test button checks the connection before saving;
- "Includi nel report Insight" decides whether the server appears in the daily report.

The monitor container must reach the server hosts. If the servers are on another Docker
network, attach the monitor to that network in docker-compose.yml, in the mon_monitor service,
with the same syntax used for the "default" network.


TELEGRAM (OPTIONAL)
-------------------

1. Create a bot with @BotFather and copy its token.
2. Add the bot to the group or channel that will receive the notifications.
3. In the "Telegram" page enter the token and the chat ID (for a group it is a negative number).
4. Enable it and send the test message.

Each alert has a "Presa visione" button. Until it is confirmed, the same event is not sent
again. The monitor receives button clicks by polling Telegram, without opening any inbound
port: only one process at a time can use the same token.


AI (OPTIONAL)
-------------

On the "Insight IA" page choose the provider (Anthropic, OpenAI, both, or Ollama running on
the machine that hosts Docker), enter the keys and models, and use "Prova connessione". With
Ollama the container reaches the host service as host.docker.internal.

Data sent to the provider: server names, versions, scores and check results.
No network addresses, users, passwords or connection strings are sent.
Results may contain user, database or table names.


MANUAL INSIDE THE APPLICATION
-----------------------------

The "Manuale" page of the application describes the individual functions, and the
"About" page shows the project name, the author and the license.
