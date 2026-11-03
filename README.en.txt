DBGUARDIAN
Open source database monitor, agentless
Author: Quirino Scaramastra

DBGuardian is a monitor for MySQL, MariaDB, PostgreSQL and Microsoft SQL Server.
It installs nothing on the monitored servers: it connects via JDBC, in read-only
mode, and collects what the database exposes.

Experimental lab, Alpha version. No support, no warranty, no scheduled maintenance.
Do not use it in production without testing.


WHAT IT DOES
------------

- Dashboard with all servers, clusters, replicas and standalone servers, with a status for each.
- InnoDB and Galera clusters: primary node, members and, when a cluster is down, the candidate node for recovery.
- Daily Insight: a score from 0 to 100 for each server, with checks, results and recommendations.
- Optional AI comment on the daily report, with Anthropic, OpenAI or both.
- Telegram notifications for events, with acknowledgement.
- Database search by name across all visible servers.
- Execution plan (EXPLAIN) of slow queries, without running the query.
- Per-server configuration: checks to run, environment, site, participation in Insight.
- Users with profiles and assigned servers.

It does not modify the monitored databases and does not collect operating system metrics.


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

   APP_SECRET_KEY encrypts the passwords of the monitored servers. It is not stored in
   the database: if it is lost, the saved passwords can no longer be read. Keep a copy
   in a safe place, and do not change it after the first start.

3. Open http://localhost:8080 and sign in with:

       user: admin
       password: admin

   At the first sign-in the monitor requires you to change the password.
   The DB_PASSWORD and DB_ROOT_PASSWORD values (in .env) are the passwords of the
   monitor database. The default "pippo" is only meant for a local lab.


WHERE THE DATA IS
-----------------

The monitor database uses the Docker volume db_data. The data is neither in the project
folder nor inside the container: Docker keeps it separately, so it survives when the
container is recreated or updated.

    docker compose down          stops and removes the containers, data is kept
    docker compose down -v       also removes the volume: all data is deleted

Volume backup (Linux and macOS):

    docker run --rm -v mon_monitor_db_data:/dati -v "$PWD":/backup alpine tar czf /backup/dbguardian-db.tgz -C /dati .

Volume backup (Windows, PowerShell):

    docker run --rm -v mon_monitor_db_data:/dati -v "${PWD}:/backup" alpine tar czf /backup/dbguardian-db.tgz -C /dati .

The volume name depends on the project folder: if the folder is called dbguardian,
the volume is dbguardian_db_data. Check it with "docker volume ls".

Restore: with the monitor stopped, recreate the volume and extract the archive into it
with the same command, using "tar xzf /backup/dbguardian-db.tgz -C /dati".


SHUTDOWN
--------

    docker compose down


ADDING A SERVER
---------------

In the "Server da monitorare" tab (administrators only):

- name, type (MySQL or MariaDB, PostgreSQL, SQL Server, router, monitor database);
- host and port reachable from the monitor container;
- a read-only user (see PERMESSI.en.txt);
- environment, site and, if any, the Galera cluster;
- the test button checks the connection before saving;
- "Includi nel report Insight" (Include in the Insight report) decides whether the server enters the daily report.

The monitor container must be able to reach the server hosts. If the servers are on
another Docker network, connect the monitor to that network in docker-compose.yml.


TELEGRAM (OPTIONAL)
-------------------

1. Create a bot with @BotFather and copy its token.
2. Add the bot to the group or channel that will receive the notifications.
3. In the "Telegram" tab, enter the token and the chat ID (for a group it is a negative number).
4. Enable it and send the test message.

Each alert has an "acknowledge" button. Until it is confirmed, the same event is not
sent again. The monitor receives button clicks by polling Telegram, without opening
any inbound port: only one process at a time can use the same token.


AI COMMENT (OPTIONAL)
---------------------

In the "Insight IA" tab, choose the provider (Anthropic, OpenAI or both), enter the keys
and the models, and use "Test connection".

Data sent to the provider: server names, versions, scores and check results.
Network addresses, users, passwords and connection strings are not sent.
Check results may contain user, database or table names.


APPLICATION MANUAL
------------------

The "Manuale" tab of the application describes each function, and the "About" tab
shows the project name, the author and the license.
